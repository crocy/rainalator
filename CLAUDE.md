# CLAUDE.md

Intentionally high-level. Detailed commands, architecture rationale, schema, and API specifics live in `backend/README.md` and `frontend/README.md`.

## Repository overview

Radar rainfall collection and visualization app: ingests ARSO (Slovenian met agency) radar data (SRD-3 format) every 5 min, stores it as PostGIS rasters, and serves a web UI for querying accumulated rainfall over map-drawn polygons.

- `backend/` — Kotlin + Quarkus REST service and ingestion scheduler (see `backend/README.md`)
- `frontend/` — Angular 19 + Leaflet UI, standalone components (see `frontend/README.md`)
- `infra/` — Terraform (AWS) + frontend deploy script
- `docker-compose.yml` / `docker-compose.prod.yml` — local stack (db on :5432, user/pass/db all `rainalator`) and prod stack

## Architecture

Backend data flow: `ingestion → storage → analysis → api` (packages under `si.rainalator`). One PostGIS raster row per 5-min scan in `radar_scans` (not 120K point rows); rasters stored in WGS84 (SRID 4326), converted from LCC at ingestion. Flyway migrations in `backend/src/main/resources/db/migration/`. Frontend talks to three REST endpoints under `/api/rainfall`; `npm start` dev server proxies `/api` → `:8080`.

## Development approach

- **TDD**: write tests first, then implement. Every module gets tests before production code.
- **Local-first**: verify with Docker Compose / Testcontainers before any AWS deployment.
- **Data retention**: indefinite — do not add cleanup/retention schedulers.

## Conventions and gotchas

- SRD-3 `unit DBR/H` is decibel rain rate — convert via `R = 10^(dBR/10)`, **NOT** Marshall-Palmer.
- SRD char decoding: `'@'` (64) = below threshold (0.0 mm/h), `'~'` (126) = nodata (NaN).
- No Hibernate/JPA — raw SQL via Agroal DataSource for all PostGIS raster operations.
- Quarkus extensions only — no Spring dependencies.
- All timestamps UTC, ISO-8601 strings over HTTP.
- Config via the `@ConfigMapping` interface (`AppConfig.kt`), not string keys.
- Backend integration tests need Docker (Testcontainers, real PostGIS — no mocking of spatial SQL).
- `infra/terraform/` contains real deployment values: `terraform.tfvars` and `backend.hcl` are gitignored — never work around that; `tfplan` is not ignored, so never `git add` it.
