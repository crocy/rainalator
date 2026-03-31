# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview
Radar rainfall data collection and visualization app. Ingests ARSO (Slovenian meteorological agency) radar data (SRD-3 format), stores as PostGIS rasters, web UI for querying accumulated rainfall over map-drawn polygons.

## Build & Test Commands
```bash
# Backend
cd backend && ./gradlew quarkusDev                    # Dev mode (hot reload, localhost:8080)
cd backend && ./gradlew test                           # Unit tests (JUnit 5 + AssertJ)
cd backend && ./gradlew test --tests '*SrdParserTest'  # Single test class
cd backend && ./gradlew quarkusIntTest                 # Integration tests (needs Docker for Testcontainers)
cd backend && ./gradlew build                          # Production JAR (build/quarkus-app/)
cd backend && ./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true  # Native binary

# Frontend
cd frontend && npm start                     # Dev server (localhost:4200, proxies /api → :8080)
cd frontend && npm test                      # Unit tests (Karma + Jasmine, ChromeHeadless)
cd frontend && npm run build                 # Production build (dist/frontend/)

# Database
docker compose up db                         # Local PostGIS (localhost:5432, user/pass/db: rainalator)
```

## Development Approach
- **TDD**: Write tests first, then implement. Every module gets tests before production code.
- **Local-first**: Test everything with Docker Compose / Testcontainers locally before AWS deployment.
- **Data retention**: Indefinite — no cleanup/retention scheduler.

## Key Domain Knowledge
- SRD-3 `unit DBR/H` is decibel rain rate — convert to mm/h via `R = 10^(dBR/10)`, NOT Marshall-Palmer
- Grid: 401x301, 1km cells, LCC projection (origin 14.815°E, 46.120°N), grid shift -4km W, -6km S
- Store rasters in WGS84 (SRID 4326), convert from LCC at ingestion time
- One PostGIS raster row per 5-min scan (not 120K point rows)
- SRD char decoding: '@' (64) = below threshold (0.0 mm/h), '~' (126) = nodata (NaN)

## Architecture

### Backend (Kotlin + Quarkus 3.34.1)
Four packages under `si.rainalator`, data flows: ingestion → storage → analysis → api

- **ingestion**: `SrdParser` (pure, parses SRD-3 text → `RadarScan`), `ProjectionConverter` (proj4j LCC→WGS84), `RadarDataScheduler` (`@Scheduled` every 5 min, fetches from ARSO)
- **storage**: `RasterStorageService` implements `RadarScanRepository` — builds PostgreSQL array literals for `ST_SetValues()`, inserts one raster row per scan
- **analysis**: `RainfallAnalysisService` — raw SQL with `ST_Clip`, `ST_SummaryStats`, `ST_DumpValues` (returns `double precision[][]`, 2D not flat). `RainfallColorScale` maps mm/h → ARGB. PNG overlay via Java2D `BufferedImage(TYPE_INT_ARGB)`
- **api**: `RainfallResource` — REST endpoints: `POST /api/rainfall/query`, `GET /api/rainfall/scans`, `GET /api/rainfall/overlay/{timestamp}` (returns PNG with `X-Bounds-*` headers for Leaflet)

### Frontend (Angular 19 + Leaflet)
All standalone components (no NgModules). Communication via `@Input`/`@Output` through `AppComponent`.

- **AppComponent**: Layout (sidebar + map + timeline overlay), orchestrates data flow between children
- **MapComponent**: Leaflet map, leaflet-draw for polygon drawing, emits WKT via `polygonDrawn`. `setOverlay(url, bounds)` / `clearOverlay()` called by parent for image overlays
- **QueryPanelComponent**: Date range inputs + analyze button, calls `RainfallService`, emits `queryResult` and `scanTimesLoaded`
- **TimelineComponent**: Scan timeline slider with play/pause (500ms/frame), emits `scanSelected(timestamp)`
- **RainfallService**: HttpClient wrapper for all 3 backend endpoints

### Database (PostgreSQL 16 + PostGIS 3.4)
Table `radar_scans`: `scan_time TIMESTAMPTZ UNIQUE`, `raster_data RASTER` (401×301 float32), `bbox GEOMETRY(Polygon, 4326)`, `scan_metadata JSONB`. Indexes on `scan_time` and `bbox` (GiST). Flyway migrations in `backend/src/main/resources/db/migration/`.

## Code Conventions
- Package: `si.rainalator`
- No Hibernate/JPA — Agroal DataSource with raw SQL for all PostGIS raster operations
- Quarkus extensions only — no Spring dependencies
- All timestamps UTC (`ZoneOffset.UTC`), ISO-8601 strings over HTTP
- Config via `@ConfigMapping` interface (`AppConfig.kt`), not string keys

## Test Patterns
- **Backend integration tests**: `@Testcontainers` + `@TestInstance(PER_CLASS)`, container `postgis/postgis:16-3.4`, manual schema setup in `@BeforeAll`, `DELETE FROM` in `@BeforeEach`. Uses `@Nested` inner classes for grouping. AssertJ for assertions.
- **Frontend tests**: `TestBed.configureTestingModule` with `provideHttpClient()` + `provideHttpClientTesting()`. Mock HTTP via `HttpTestingController.expectOne()` + `req.flush()`. `afterEach` calls `httpTesting.verify()`.
