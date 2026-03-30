# Rainalator — Project Instructions

## Overview
Radar rainfall data collection and visualization app. Ingests ARSO (Slovenian meteorological agency) radar data (SRD-3 format), stores as PostGIS rasters, web UI for querying accumulated rainfall over map-drawn polygons.

## Tech Stack
- **Backend**: Kotlin + Quarkus, plain JDBC (no Hibernate), Flyway migrations
- **Database**: PostgreSQL 16 + PostGIS 3.4 (raster storage)
- **Frontend**: Angular 19 + Leaflet + leaflet-draw
- **Build**: Gradle (backend), npm (frontend)
- **Deployment**: Docker Compose

## Development Approach
- **TDD**: Write tests first, then implement. Every module gets tests before production code.
- **Local-first**: Test everything with Docker Compose locally before AWS deployment.
- **Data retention**: Indefinite — no cleanup/retention scheduler.

## Build & Test Commands
```bash
# Backend
cd backend && ./gradlew quarkusDev          # Dev mode with hot reload
cd backend && ./gradlew test                 # Unit tests
cd backend && ./gradlew quarkusIntTest       # Integration tests (needs Docker for Testcontainers)

# Frontend
cd frontend && npm start                     # Dev server (proxies /api to :8080)
cd frontend && npm test                      # Unit tests

# Database
docker compose up db                         # Local PostGIS
```

## Key Domain Knowledge
- SRD-3 `unit DBR/H` is decibel rain rate — convert to mm/h via `R = 10^(dBR/10)`, NOT Marshall-Palmer
- Grid: 401x301, 1km cells, LCC projection (origin 14.815°E, 46.120°N)
- Store rasters in WGS84 (SRID 4326), convert from LCC at ingestion time
- One PostGIS raster row per 5-min scan (not 120K point rows)

## Code Conventions
- Package: `si.rainalator`
- No Hibernate/JPA — use Agroal DataSource with raw SQL for PostGIS raster operations
- Quarkus extensions only — avoid Spring dependencies
- Integration tests use Testcontainers with `postgis/postgis:16-3.4` image
