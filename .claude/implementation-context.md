# Implementation Context — TODO Items 4, 5, 6

Read this file at the start of a new session to resume implementation.
The full plan is at: `C:\Users\tehcr\.claude\plans\kind-rolling-sphinx.md`

## Implementation Order: Task 6 → Task 4 → Task 5

## Task 6: Make `scan_time` the Primary Key

**What**: Remove `id BIGSERIAL`, make `scan_time TIMESTAMPTZ` the PK. Drop all existing data (rewrite V2 migration directly).

**Files to modify (5)**:
- `backend/src/main/resources/db/migration/V2__create_radar_scans.sql` — rewrite: remove `id`, PK on `scan_time`, remove explicit UNIQUE constraint and scan_time index (PK covers both), keep GiST bbox index
- `backend/src/test/kotlin/si/rainalator/storage/RasterStorageServiceTest.kt` (lines 50-62) — update DDL in `setupSchema()`
- `backend/src/test/kotlin/si/rainalator/ingestion/RadarDataSchedulerTest.kt` (lines 48-58) — same
- `backend/src/test/kotlin/si/rainalator/analysis/RainfallAnalysisServiceTest.kt` (lines 54-66) — same
- `backend/src/test/kotlin/si/rainalator/api/RainfallResourceTest.kt` (lines 56-68) — same

**After**: Drop + recreate local DB (`docker compose down db -v && docker compose up db -d`), run `./gradlew test`.

---

## Task 4: Retry Logic + Spill Directory + Health Check

**What**: On DB insert failure, save raw SRD3 text to a configurable local dir. On each scheduler tick, drain pending spill files first. Health check reports pending count.

**Config additions**:
- `AppConfig.kt`: add `fun spillDir(): String` with default `"data/spill"`
- `application.properties`: add `rainalator.spill-dir=data/spill`

**New files (4)**:
- `backend/src/main/kotlin/si/rainalator/ingestion/SpillService.kt` — `@ApplicationScoped`, file I/O for spill dir (save/list/delete/count), create dir in constructor, filename format: `2026-04-05T12-00-00Z.srd` (hyphens not colons for Windows)
- `backend/src/main/kotlin/si/rainalator/ingestion/SpillHealthCheck.kt` — `@Readiness` health check, always UP, includes `pending-files` count as data
- `backend/src/test/kotlin/si/rainalator/ingestion/SpillServiceTest.kt` — unit tests with `@TempDir`
- `backend/src/test/kotlin/si/rainalator/ingestion/SpillHealthCheckTest.kt` — unit test

**Modified files (1)**:
- `backend/src/main/kotlin/si/rainalator/ingestion/RadarDataScheduler.kt` — inject `SpillService`, restructure `fetchAndStore()`: (1) drain spill dir first (oldest-first, break on first failure), (2) fetch+parse, (3) try insert, on failure spill raw body. Handle PSQLState `23505` (duplicate PK) during drain as success.

**Key code insight**: Raw SRD3 text is the `body` variable in `fetchAndStore()` (returned by `fetchSrdFile()` at line 36). Save THIS string, not the parsed `RadarScan`. On retry, read file → parse → insert.

---

## Task 5: Raw SRD3 Archival — Local + Daily S3 Upload

**What**: Save every fetched SRD3 file to a date-partitioned local dir. At midnight, compress previous day into `.tar.gz`, upload to S3, delete local only on S3 success.

**New dependencies** (`build.gradle.kts`):
```kotlin
implementation("io.quarkiverse.amazonservices:quarkus-amazon-s3:2.22.0")
implementation("software.amazon.awssdk:url-connection-client")
implementation("org.apache.commons:commons-compress:1.27.1")
testImplementation("org.testcontainers:localstack:1.20.4")
```

**Config additions**:
- `AppConfig.kt`: add `rawArchiveDir()` (default `"data/raw-archive"`), `s3Bucket()` (default `"rainalator-raw-archive"`), `s3Prefix()` (default `"srd3/"`)
- `application.properties`: add those + `quarkus.s3.*` config

**New files (4)**:
- `backend/src/main/kotlin/si/rainalator/ingestion/RawArchiveService.kt` — `@ApplicationScoped`, saves to `{rawArchiveDir}/{date}/{timestamp}.srd`. SEPARATE from SpillService (different lifecycle).
- `backend/src/main/kotlin/si/rainalator/ingestion/S3ArchivalScheduler.kt` — `@Scheduled(cron = "0 0 0 * * ?")` midnight daily. Compresses yesterday's dir → tar.gz, uploads to S3, deletes local on success.
- `backend/src/test/kotlin/si/rainalator/ingestion/RawArchiveServiceTest.kt` — unit tests with `@TempDir`
- `backend/src/test/kotlin/si/rainalator/ingestion/S3ArchivalSchedulerTest.kt` — integration test with LocalStack

**Modified files (1)**:
- `backend/src/main/kotlin/si/rainalator/ingestion/RadarDataScheduler.kt` — inject `RawArchiveService`, call `archiveService.saveRawSrd(scan.header.time, body)` after parse, ALWAYS (regardless of DB outcome)

---

## Key Codebase Facts

- Package: `si.rainalator`
- Backend: Kotlin + Quarkus 3.34.1, Java 25, Gradle (Kotlin DSL)
- No Hibernate/JPA — raw SQL via Agroal DataSource
- Config: `@ConfigMapping(prefix = "rainalator")` interface `AppConfig.kt`
- Tests: `@Testcontainers` + `@TestInstance(PER_CLASS)`, container `postgis/postgis:16-3.4`, manual schema in `@BeforeAll`, `DELETE FROM` in `@BeforeEach`, AssertJ assertions
- TDD approach: write tests first
- `RadarDataScheduler.fetchAndStore()` flow: `fetchSrdFile()` → `parser.parse(body)` → `storageService.insert(scan)`
- `RadarScan` = `data class RadarScan(val header: SrdHeader, val values: FloatArray)`
- SRD3 file: ~120KB, 401×301 ASCII grid, 288 scans/day (~34MB/day raw)
- Dev servers: DB via `docker compose up db`, backend `./gradlew quarkusDev` (needs JAVA_HOME=`C:\Users\tehcr\.jdks\openjdk-25.0.2`), frontend `npm start` from `frontend/`
