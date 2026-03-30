# Rainalator

Radar rainfall data collection and visualization app for Slovenia.

Collects radar data from ARSO (Slovenian Environment Agency) every 5 minutes, stores it as PostGIS rasters, and provides a web UI to query accumulated rainfall over user-drawn map areas.

## Tech Stack

- **Backend**: Kotlin + Quarkus (GraalVM native)
- **Database**: PostgreSQL + PostGIS (raster storage)
- **Frontend**: Angular + Leaflet
- **Deployment**: Docker Compose

## Local Development

```bash
# Start database
docker compose up db

# Run backend in dev mode
cd backend && ./gradlew quarkusDev

# Run frontend in dev mode
cd frontend && npm start
```

## Project Structure

```
rainalator/
├── backend/          # Kotlin + Quarkus
├── frontend/         # Angular + Leaflet
├── docker-compose.yml
└── db/               # PostGIS init scripts
```
