# Implementation Plan — TODO Items 4, 5, 6

## Context

The README has 9 TODO items. Based on user feedback:
- **#1 (IaC)**: Deploy BE+DB as Docker containers on a single EC2 t4g.small (started on boot), FE to S3/CloudFront. Deferred — not in this implementation round.
- **#2 (ARSO history)**: User will investigate ARSO's API themselves.
- **#3 (S3/CloudFront FE)**: Covered by #1, obsolete as separate item.
- **#4 (Retry logic)**: **Implement now** — spill to disk on DB failure, retry on next tick, health indicator.
- **#5 (Raw SRD3 archival)**: **Implement now** — save locally per day, compress + upload to S3 daily, delete local on success.
- **#6 (scan_time as PK)**: **Implement now** — rewrite V2 migration, drop existing data.
- **#7–9**: Documentation/knowledge items, no code changes.

Implementation order: **Task 6 → Task 4 → Task 5** (schema first, then features that build on it).

---

## Task 6: Make `scan_time` the Primary Key

Remove `id BIGSERIAL`, promote `scan_time` to PK. Drop existing data (rewrite V2 directly).

### Files to modify

**`backend/src/main/resources/db/migration/V2__create_radar_scans.sql`** — Rewrite:
- Remove `id BIGSERIAL PRIMARY KEY`
- Add `PRIMARY KEY` to `scan_time` column
- Remove `ALTER TABLE ... ADD CONSTRAINT uq_scan_time UNIQUE` (PK implies unique)
- Remove `CREATE INDEX idx_radar_scans_scan_time` (PK creates btree automatically)
- Keep `CREATE INDEX idx_radar_scans_bbox ON radar_scans USING GIST (bbox)`

**4 test files** — Update `setupSchema()` DDL to match new migration:
- `backend/src/test/kotlin/si/rainalator/storage/RasterStorageServiceTest.kt` (lines 50-62)
- `backend/src/test/kotlin/si/rainalator/ingestion/RadarDataSchedulerTest.kt` (lines 48-58)
- `backend/src/test/kotlin/si/rainalator/analysis/RainfallAnalysisServiceTest.kt` (lines 54-66)
- `backend/src/test/kotlin/si/rainalator/api/RainfallResourceTest.kt` (lines 56-68)

**Reset Flyway**: Drop + recreate local DB so Flyway re-runs the rewritten V2.

### Verify
Run `./gradlew test` — all existing tests should pass unchanged (no Kotlin code references `id`).

---

## Task 4: Retry Logic with Spill Directory + Health Indicator

When DB insert fails, save raw SRD3 text to a local spill directory. On each scheduler tick, drain pending spill files before fetching new data. Health check reports pending file count.

### Config changes

**`backend/src/main/kotlin/si/rainalator/AppConfig.kt`** — Add:
```kotlin
@WithDefault("data/spill")
fun spillDir(): String
```

**`backend/src/main/resources/application.properties`** — Add:
```properties
rainalator.spill-dir=data/spill
```

### New files

**`backend/src/main/kotlin/si/rainalator/ingestion/SpillService.kt`** — `@ApplicationScoped` class:
- `saveToSpillDir(scanTime: ZonedDateTime, rawBody: String)` — writes to `{spillDir}/{timestamp}.srd` (colons → hyphens for Windows)
- `listPendingFiles(): List<Path>` — sorted oldest-first
- `deleteSpillFile(path: Path)` — remove after successful insert
- `pendingCount(): Int` — for health check
- Constructor creates spill dir via `Files.createDirectories`

**`backend/src/main/kotlin/si/rainalator/ingestion/SpillHealthCheck.kt`** — `@Readiness` health check:
- Reports UP always, includes `pending-files` count as data when > 0
- Visible at `/q/health/ready`, monitorable without failing readiness probes

### Modified files

**`backend/src/main/kotlin/si/rainalator/ingestion/RadarDataScheduler.kt`** — Restructure `fetchAndStore()`:
1. Call `drainSpillDir()` first — retry pending files oldest-first, break on first failure
2. Fetch + parse as before
3. Wrap `storageService.insert(scan)` in try-catch — on failure, call `spillService.saveToSpillDir(scan.header.time, body)`
4. Handle PK duplicate (PSQLState `23505`) during drain as success (delete spill file)

### Tests

**`backend/src/test/kotlin/si/rainalator/ingestion/SpillServiceTest.kt`** — Unit tests with `@TempDir`:
- Creates file with correct name format and content
- Creates directory if missing
- Lists files sorted oldest-first
- Returns correct pending count
- Deletes file

**`backend/src/test/kotlin/si/rainalator/ingestion/SpillHealthCheckTest.kt`** — Unit test:
- Reports UP with 0 pending
- Reports UP with pending-files data when > 0

### Verify
Run `./gradlew test`. Manually test: stop DB container, trigger scheduler, verify spill file created, restart DB, trigger scheduler, verify spill drained and data in DB.

---

## Task 5: Raw SRD3 Archival — Local + Daily S3 Upload

Save every fetched SRD3 file locally in date-partitioned dirs. At midnight, compress previous day's files into `.tar.gz`, upload to S3, delete local only on success.

### Dependencies

**`backend/build.gradle.kts`** — Add:
```kotlin
implementation("io.quarkiverse.amazonservices:quarkus-amazon-s3:2.22.0")
implementation("software.amazon.awssdk:url-connection-client")
implementation("org.apache.commons:commons-compress:1.27.1")
testImplementation("org.testcontainers:localstack:1.20.4")
```

### Config changes

**`backend/src/main/kotlin/si/rainalator/AppConfig.kt`** — Add:
```kotlin
@WithDefault("data/raw-archive")
fun rawArchiveDir(): String

@WithDefault("rainalator-raw-archive")
fun s3Bucket(): String

@WithDefault("srd3/")
fun s3Prefix(): String
```

**`backend/src/main/resources/application.properties`** — Add:
```properties
rainalator.raw-archive-dir=data/raw-archive
rainalator.s3-bucket=rainalator-raw-archive
rainalator.s3-prefix=srd3/
quarkus.s3.endpoint-override=${S3_ENDPOINT:}
quarkus.s3.aws.region=${AWS_REGION:eu-central-1}
quarkus.s3.aws.credentials.type=default
quarkus.s3.path-style-access=true
```

### New files

**`backend/src/main/kotlin/si/rainalator/ingestion/RawArchiveService.kt`** — `@ApplicationScoped`:
- `saveRawSrd(scanTime: ZonedDateTime, rawBody: String)` — saves to `{rawArchiveDir}/{date}/{timestamp}.srd`
- Date subdirectory groups files for daily compression (e.g., `2026-04-05/`)
- Separate from SpillService — different lifecycle (spill = retry queue deleted on DB success; archive = S3 staging deleted on upload success)

**`backend/src/main/kotlin/si/rainalator/ingestion/S3ArchivalScheduler.kt`** — `@ApplicationScoped` with `@Scheduled(cron = "0 0 0 * * ?")`:
1. Look for yesterday's date directory in `rawArchiveDir`
2. Create `.tar.gz` of all `.srd` files using commons-compress `TarArchiveOutputStream` + `GZIPOutputStream`
3. Upload to S3 at key `{s3Prefix}{date}/{date}.tar.gz`
4. On success: delete local day directory + tar.gz
5. On failure: log error, delete tar.gz only, keep raw files for next retry

### Modified files

**`backend/src/main/kotlin/si/rainalator/ingestion/RadarDataScheduler.kt`** — Add archive step:
- After `parser.parse(body)` succeeds, call `archiveService.saveRawSrd(scan.header.time, body)` — **always**, regardless of DB outcome
- Inject `RawArchiveService`

### Tests

**`backend/src/test/kotlin/si/rainalator/ingestion/RawArchiveServiceTest.kt`** — Unit tests with `@TempDir`:
- Creates date subdirectory and file
- Multiple saves on same day → same directory
- Different days → different directories

**`backend/src/test/kotlin/si/rainalator/ingestion/S3ArchivalSchedulerTest.kt`** — Integration test with LocalStack:
- Compresses and uploads yesterday's files
- Deletes local files after successful upload
- Retains local files when S3 upload fails
- Handles empty/missing directory gracefully
- Produces valid tar.gz with correct contents

### Verify
Run `./gradlew test`. For manual E2E: let scheduler run for a cycle, check `data/raw-archive/{today}/` has `.srd` files. Set cron to fire soon (or call method directly), verify tar.gz appears in S3 (LocalStack or real), local files cleaned up.

---

## File Summary

| Action | File | Task |
|--------|------|------|
| Modify | `db/migration/V2__create_radar_scans.sql` | 6 |
| Modify | `RasterStorageServiceTest.kt` (lines 50-62) | 6 |
| Modify | `RadarDataSchedulerTest.kt` (lines 48-58) | 6 |
| Modify | `RainfallAnalysisServiceTest.kt` (lines 54-66) | 6 |
| Modify | `RainfallResourceTest.kt` (lines 56-68) | 6 |
| Modify | `AppConfig.kt` | 4, 5 |
| Modify | `application.properties` | 4, 5 |
| Modify | `RadarDataScheduler.kt` | 4, 5 |
| Modify | `build.gradle.kts` | 5 |
| Create | `SpillService.kt` | 4 |
| Create | `SpillHealthCheck.kt` | 4 |
| Create | `RawArchiveService.kt` | 5 |
| Create | `S3ArchivalScheduler.kt` | 5 |
| Create | `SpillServiceTest.kt` | 4 |
| Create | `SpillHealthCheckTest.kt` | 4 |
| Create | `RawArchiveServiceTest.kt` | 5 |
| Create | `S3ArchivalSchedulerTest.kt` | 5 |
