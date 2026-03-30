CREATE TABLE radar_scans (
    id            BIGSERIAL PRIMARY KEY,
    scan_time     TIMESTAMPTZ NOT NULL,
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_radars TEXT[],
    raster_data   RASTER NOT NULL,
    bbox          GEOMETRY(Polygon, 4326),
    scan_metadata JSONB
);

ALTER TABLE radar_scans ADD CONSTRAINT uq_scan_time UNIQUE (scan_time);

CREATE INDEX idx_radar_scans_scan_time ON radar_scans (scan_time);
CREATE INDEX idx_radar_scans_bbox ON radar_scans USING GIST (bbox);
