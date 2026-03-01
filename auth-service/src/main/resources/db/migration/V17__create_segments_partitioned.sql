-- V17: Video segments (fMP4) - partitioned by month for performance at scale

CREATE TABLE segments (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    created_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    tenant_id           UUID NOT NULL,
    device_id           UUID NOT NULL,
    session_id          UUID NOT NULL,

    sequence_num        INTEGER NOT NULL,

    s3_bucket           VARCHAR(255) NOT NULL,
    s3_key              VARCHAR(1024) NOT NULL,

    size_bytes          BIGINT NOT NULL,
    duration_ms         INTEGER NOT NULL,
    checksum_sha256     VARCHAR(64) NOT NULL,

    status              VARCHAR(20) NOT NULL DEFAULT 'uploaded'
        CHECK (status IN ('uploaded', 'confirmed', 'indexed', 'failed')),

    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,

    PRIMARY KEY (id, created_ts)
) PARTITION BY RANGE (created_ts);

-- Monthly partitions for 2026
CREATE TABLE segments_2026_01 PARTITION OF segments
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE segments_2026_02 PARTITION OF segments
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE segments_2026_03 PARTITION OF segments
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE segments_2026_04 PARTITION OF segments
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE segments_2026_05 PARTITION OF segments
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE segments_2026_06 PARTITION OF segments
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE segments_2026_07 PARTITION OF segments
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE segments_2026_08 PARTITION OF segments
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE segments_2026_09 PARTITION OF segments
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE segments_2026_10 PARTITION OF segments
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE segments_2026_11 PARTITION OF segments
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE segments_2026_12 PARTITION OF segments
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE INDEX idx_segments_tenant ON segments(tenant_id, created_ts);
CREATE INDEX idx_segments_device ON segments(device_id, created_ts);
CREATE INDEX idx_segments_session ON segments(session_id, sequence_num);
CREATE INDEX idx_segments_status ON segments(status, created_ts);
CREATE INDEX idx_segments_s3_key ON segments(s3_key);

COMMENT ON TABLE segments IS 'Video segments (fMP4). Partitioned monthly by created_ts for 10k+ device scale';
COMMENT ON COLUMN segments.s3_key IS 'S3 path: {tenant_id}/{device_id}/{session_id}/{seq_num}.mp4';
