# --- !Ups
# --- !Ups
ALTER TABLE gsv_data
    ADD COLUMN origin_heading FLOAT,
    ADD COLUMN origin_pitch FLOAT,
    DROP COLUMN imagery_type,
    ALTER COLUMN image_width DROP NOT NULL,
    ALTER COLUMN image_height DROP NOT NULL,
    ALTER COLUMN tile_width DROP NOT NULL,
    ALTER COLUMN tile_height DROP NOT NULL;

-- We were just recording constants for these values before, so set to null. Need to fix them outside the application.
UPDATE gsv_data SET image_width = NULL, image_height = NULL, tile_width = NULL, tile_height = NULL;

-- Add a table to keep track of versioning.
CREATE TABLE version
(
    version_id TEXT NOT NULL,
    version_start_time TIMESTAMP NOT NULL,
    description TEXT,
    PRIMARY KEY (version_id)
);

INSERT INTO version VALUES ('5.0.0', now(), 'Makes DC server API only.');

# --- !Downs
DROP TABLE version;

UPDATE gsv_data
SET image_width = 13312, image_height = 6656, tile_width = 512, tile_height = 512
WHERE image_width IS NULL OR image_height IS NULL OR tile_width IS NULL OR tile_height IS NULL;

ALTER TABLE gsv_data
    DROP COLUMN origin_heading,
    DROP COLUMN origin_pitch,
    ADD COLUMN imagery_type INTEGER;

UPDATE gsv_data SET imagery_type = 1;
ALTER TABLE gsv_data ALTER COLUMN imagery_type SET NOT NULL;
