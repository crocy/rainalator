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

# TODO

## General

* [ ] Can this project be deployed as an "infrastructure as code" project?
* [ ] Is it possible to get historical rain radar data from ARSO in case either our or their service gets offline for a while so that we don't have a gap in the data?

## Frontend

* [ ] can this frontend be served as a static site either via AWS's S3 or CloudFront?

## Backend

* [ ] Implement retry logic in case the DB isn't reachable. Store the data in a temporary file until the DB is reachable again. Once it is, upload the data from the file to the DB and
 remove the temporary file.
* [ ] Would storing "raw"/source SRD3 files be a good idea? That would allow us reprocessing them again later if ever needed.
  * [ ] Also, how much more space would that take in the DB? Would it make sense to store them in a compressed format?
* [ ] what do you think about using `scan_time` in the `radar_scans` table as the primary key (and removing the current ID column)?
* [ ] explain how the `bbox` index works
* [ ] explain how the `ST_Clip()` + `ST_SummaryStats()` query works
* [ ] can this backend run on ARM64 architecture (to be used on AWS's t4g EC2 instance)?