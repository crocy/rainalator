CREATE TABLE radar_scans (
    scan_time     TIMESTAMPTZ NOT NULL PRIMARY KEY,
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_radars TEXT[],
    raster_data   RASTER NOT NULL,
    bbox          GEOMETRY(Polygon, 4326),
    scan_metadata JSONB
);

CREATE INDEX idx_radar_scans_bbox ON radar_scans USING GIST (bbox);
