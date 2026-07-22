# rainalator-backend

Backend service for Rainalator — a radar rainfall data collection and visualization system. Ingests ARSO (Slovenian Environment Agency) radar data every 5 minutes, stores it as PostGIS rasters, and serves spatial analysis queries and map overlay images via REST.

Built with **Kotlin**, **Quarkus**, and **PostgreSQL/PostGIS**.

## Why these technology choices

### Quarkus over Spring Boot

Quarkus was chosen for its container-native design: fast startup, low memory footprint, and first-class support for GraalVM native compilation. The application runs as a long-lived scheduled service in Docker, where these properties matter. Quarkus also provides a minimal, convention-light approach — there's no need for Spring's extensive auto-configuration when the backend only has three endpoints and one scheduled job.

### Kotlin over Java

Kotlin's data classes, null safety, and concise syntax reduce boilerplate significantly in a codebase that's mostly data transformation (parsing SRD text → domain objects → SQL parameters → JSON responses). The `allOpen` plugin is required because Quarkus (via ArC CDI) needs to subclass beans annotated with `@Path`, `@ApplicationScoped`, etc. — Kotlin classes are `final` by default.

### Raw JDBC over Hibernate/JPA

The entire data model is a single PostGIS `RASTER` column. No ORM can map raster types — operations like `ST_SetValues()`, `ST_Clip()`, `ST_SummaryStats()`, and `ST_DumpValues()` are pure SQL with no object-relational equivalent. Using raw `DataSource` + `PreparedStatement` via Quarkus Agroal gives full control over these spatial queries without fighting an ORM's abstraction.

### PostGIS rasters over point geometries

Each radar scan is a 401×301 grid (120,701 cells). Storing this as a single `RASTER` row instead of 121K point rows means:
- **Storage**: One row per scan, rasters compress natively
- **Queries**: `ST_Clip(raster, polygon)` + `ST_SummaryStats()` runs in a single SQL call — no GROUP BY over 121K rows
- **Indexing**: A GiST index on the bounding box geometry filters scans spatially before expensive raster operations

### proj4j for coordinate conversion

ARSO's SRD-3 format uses a Lambert Conformal Conic (LCC) projection with custom parameters (origin 14.815°E, 46.120°N, grid shift offsets). The `proj4j` library converts grid cell indices to WGS84 coordinates in pure Java. PostGIS `ST_Transform()` can't be used here because it operates on stored geometries, not on grid-index-to-coordinate mapping before data reaches the database.

### Flyway for schema migrations

Schema changes are versioned SQL files that run automatically on startup (`quarkus.flyway.migrate-at-start=true`). This ensures every environment — local dev, Docker Compose, CI — gets the same schema without manual intervention.

## Architecture

Data flows through four packages: **ingestion → storage → analysis → api**.

```
ARSO Server (meteo.arso.gov.si)
    │ HTTP GET every 5 minutes
    ▼
RadarDataScheduler (@Scheduled)
    │
    ▼
SrdParser (pure function: SRD-3 text → RadarScan)
    │
    ▼
RasterStorageService
    ├── ProjectionConverter (grid indices → WGS84 via proj4j)
    ├── ST_MakeEmptyRaster + ST_AddBand + ST_SetValues (build raster in SQL)
    └── INSERT INTO radar_scans
         │
         ▼
    PostgreSQL + PostGIS
         │
         ▼
RainfallResource (REST endpoints)
    ├── POST /api/rainfall/query    → ST_Clip + ST_SummaryStats per scan
    ├── GET  /api/rainfall/scans    → scan_time list for time range
    └── GET  /api/rainfall/overlay  → PNG image + geo-bounds headers
```

### Package breakdown

```
si.rainalator/
├── ingestion/
│   ├── RadarDataScheduler    # @Scheduled(every="5m"), fetches SRD from ARSO
│   ├── SrdParser             # Pure parser: SRD-3 ASCII → RadarScan(header, FloatArray)
│   ├── SrdHeader             # 26 metadata fields (projection params, grid dimensions)
│   ├── RadarScan             # Header + 401×301 float values (row-major)
│   └── ProjectionConverter   # proj4j: LCC grid cell (col,row) → WGS84 (lat,lon)
├── storage/
│   ├── RadarScanRepository   # Interface: insert(), findScanTimes(), countScans()
│   └── RasterStorageService  # JDBC implementation, builds SQL array literals for ST_SetValues
├── analysis/
│   ├── RainfallAnalysisService  # Raw SQL: ST_Clip, ST_SummaryStats, ST_DumpValues
│   ├── RainfallColorScale       # 16-band ARGB color mapping (matches ARSO radar legend)
│   └── RainfallResult           # ScanAnalysis, OverlayImage, GeoBounds data classes
├── api/
│   ├── RainfallResource         # JAX-RS @Path("/api/rainfall"), 3 endpoints
│   └── dto/                     # Request/response data classes
└── config/
    └── AppConfig                # @ConfigMapping(prefix="rainalator"), type-safe config
```

## Database schema

Two Flyway migrations in `src/main/resources/db/migration/`:

**V1** enables PostGIS extensions:
```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_raster;
```

**V2** creates the single data table:
```sql
CREATE TABLE radar_scans (
    id            BIGSERIAL PRIMARY KEY,
    scan_time     TIMESTAMPTZ NOT NULL,      -- UTC timestamp of the radar scan
    ingested_at   TIMESTAMPTZ DEFAULT NOW(),  -- when we stored it
    source_radars TEXT[],                     -- radar station identifiers
    raster_data   RASTER NOT NULL,           -- 401×301 float32, SRID 4326
    bbox          GEOMETRY(Polygon, 4326),   -- bounding box for spatial index
    scan_metadata JSONB                      -- reserved for future use
);
```

**Indexes and constraints:**
- `UNIQUE(scan_time)` — prevents duplicate ingestion of the same scan
- B-tree on `scan_time` — fast range queries for time-based lookups
- GiST on `bbox` — spatial index, used by `ST_Intersects()` to quickly filter scans overlapping a query polygon

The raster is stored in WGS84 (SRID 4326) after coordinate conversion from LCC at ingestion time. Each pixel is a 32-bit float representing rainfall in mm/h. `NaN` marks nodata pixels.

## Configuration

All config lives in `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `quarkus.datasource.jdbc.url` | `jdbc:postgresql://localhost:5432/rainalator` | Database connection (overridden in Docker via env var) |
| `quarkus.datasource.password` | `${DB_PASSWORD:rainalator}` | Supports env var override for production |
| `quarkus.datasource.jdbc.max-size` | `8` | Connection pool ceiling — sufficient for 3 endpoints + 1 scheduler |
| `quarkus.flyway.migrate-at-start` | `true` | Auto-apply migrations on every boot |
| `rainalator.arso-url` | ARSO si0-rrg.srd URL | SRD-3 file location to fetch |
| `rainalator.fetch-interval` | `5m` | Scheduler interval, matches ARSO's radar update cadence |
| `quarkus.jackson.serialization-inclusion` | `non-null` | Omit null fields from JSON responses |

The `@ConfigMapping(prefix="rainalator")` interface (`AppConfig.kt`) provides type-safe access to the `rainalator.*` properties. Quarkus validates these at startup — missing values cause a fast failure rather than a runtime NPE.

## Docker infrastructure

### Docker Compose (project root)

Three services with health-check-based dependency ordering:

1. **db** (`postgis/postgis:16-3.4`) — PostgreSQL 16 with PostGIS 3.4. Health checked via `pg_isready`. Data persisted in a named volume (`pgdata`).
2. **backend** — Built from `Dockerfile.jvm`. Waits for `db` to be healthy. Health checked via `/q/health/ready` (SmallRye Health). Database connection configured via `QUARKUS_DATASOURCE_*` environment variables pointing to the `db` container.
3. **frontend** — Waits for `backend` to be healthy. Serves the Angular app and proxies API calls.

This ordering ensures the database is accepting connections before the backend starts (and Flyway migrates), and the backend is ready before the frontend starts serving traffic.

### Dockerfile.jvm

The production JVM image uses Red Hat UBI 9 with OpenJDK 25 (`ubi9/openjdk-25-runtime:1.24`). Key design choices:

- **Four COPY layers** (`lib/`, `*.jar`, `app/`, `quarkus/`) — Quarkus's layered JAR output means dependency JARs (which rarely change) are cached in earlier Docker layers, so application code changes only rebuild the last layer.
- **Non-root user** (UID 185) — standard security practice.
- **`run-java.sh` entrypoint** — Red Hat's script that auto-tunes JVM heap, GC, and diagnostics based on container memory limits. Configurable via `JAVA_OPTS_APPEND` without modifying the Dockerfile.

Alternative Dockerfiles are provided for legacy JAR, GraalVM native, and native-micro builds, but the JVM variant is the default for development.

## Dependencies and why each exists

### Quarkus extensions

| Extension | Why |
|---|---|
| `quarkus-scheduler` | Declarative `@Scheduled` for the 5-minute ARSO fetch loop. `ConcurrentExecution.SKIP` prevents overlapping fetches. |
| `quarkus-rest-jackson` | JAX-RS endpoints with Jackson JSON serialization. Lightweight alternative to RESTEasy Classic. |
| `quarkus-smallrye-health` | `/q/health/ready` endpoint used by Docker Compose health checks to gate service startup ordering. |
| `quarkus-flyway` | Automatic schema migration on startup. Two SQL files, no manual DDL. |
| `quarkus-kotlin` | Kotlin language support + integration with Quarkus's build-time optimizations. |
| `quarkus-agroal` | JDBC connection pool. Manages the 8-connection pool to PostgreSQL. |
| `quarkus-jdbc-postgresql` | PostgreSQL JDBC driver, managed by Quarkus BOM for version alignment. |
| `quarkus-arc` | CDI dependency injection. Quarkus's build-time DI container (lighter than runtime reflection-based alternatives). |

### Non-Quarkus libraries

| Library | Version | Why |
|---|---|---|
| `proj4j` | 1.3.0 | Converts ARSO's Lambert Conformal Conic grid coordinates to WGS84. Pure Java, no native dependencies. |
| `postgis-jdbc` | 2023.1.0 | Provides Java types for PostGIS geometries. Used when reading spatial query results. |

### Test libraries

| Library | Version | Why |
|---|---|---|
| `testcontainers` | 1.20.4 | Spins up real `postgis/postgis:16-3.4` containers for integration tests. No mocking of spatial SQL. |
| `assertj-core` | 3.27.3 | Fluent assertions — `assertThat(result.mean).isCloseTo(expected, within(0.01))`. |
| `rest-assured` | (Quarkus BOM) | HTTP endpoint testing — `given().body(request).post("/api/rainfall/query").then().statusCode(200)`. |

## Running the application

### Dev mode (hot reload)

```shell
./gradlew quarkusDev
```

Starts on `localhost:8080` with live coding enabled. Requires a running PostgreSQL — either via `docker compose up db` from the project root, or any PostgreSQL 16+ with PostGIS 3.4.

To seed the local DB with real data from prod (the local ingester only collects while running), use `infra/pull-prod-db.sh [--days N | --full]` from the project root — it copies `radar_scans` rows from the prod DB via SSM + an S3 bounce and merges them idempotently. Defaults to the last 30 days; needs AWS credentials and a local schema (run the backend once first so Flyway creates it).

### Production build

```shell
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

### Full stack via Docker Compose

```shell
# From project root
docker compose up
```

Starts database, backend, and frontend. Backend available on port 8080, frontend on port 80.

### Native executable

```shell
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./build/rainalator-backend-1.0.0-SNAPSHOT-runner
```

Requires GraalVM or uses a container-based build (no local GraalVM needed with `-Dquarkus.native.container-build=true`).

## Testing

```shell
./gradlew test                                    # All unit tests
./gradlew test --tests '*SrdParserTest'           # Single test class
./gradlew quarkusIntTest                          # Integration tests (needs Docker)
```

Tests use real PostgreSQL via Testcontainers — no mocking of database or spatial operations. Pattern: `@Testcontainers` + `@TestInstance(PER_CLASS)`, schema created in `@BeforeAll`, data cleaned via `DELETE FROM` in `@BeforeEach`.

Test resources include a tiny 5×3 SRD file for unit tests and a real 401×301 ARSO sample for integration tests.

## API endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/rainfall/query` | Analyze accumulated rainfall within a WKT polygon over a time range. Returns per-scan statistics (count, sum, mean, stddev, min, max). |
| `GET` | `/api/rainfall/scans?from=...&to=...` | List available scan timestamps in a time range. Used by the frontend timeline. |
| `GET` | `/api/rainfall/overlay/{timestamp}` | Render a single scan as a PNG image with ARSO-matching colors. Returns `X-Bounds-*` headers for Leaflet geo-positioning. |

All timestamps are UTC, ISO-8601 format.
