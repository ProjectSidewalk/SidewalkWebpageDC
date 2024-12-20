# --- !Ups
-- Making a table to hold old sv_image_x/y so that we can roll back if we find an issue.
CREATE TABLE old_label_metadata (
    label_id INTEGER NOT NULL,
    old_sv_image_x INT NOT NULL,
    old_sv_image_y INT NOT NULL,
    FOREIGN KEY (label_id) REFERENCES label(label_id)
);

-- Store the old data.
INSERT INTO old_label_metadata (label_id, old_sv_image_x, old_sv_image_y)
SELECT label_point.label_id, label_point.sv_image_x, label_point.sv_image_y
FROM label
INNER JOIN label_point ON label.label_id = label_point.label_id;

-- Update the sv_image_x/y values in the database. The math is essentially calling this in https://github.com/ProjectSidewalk/SidewalkWebpage/blob/develop/public/javascripts/common/UtilitiesPanomarker.js:
-- calculatePanoXYFromPov(calculatePovIfCentered({heading: heading, pitch: pitch, zoom: zoom}, canvas_x, canvas_y, 720, 480), photographer_heading, image_width, image_height).
UPDATE label_point
SET sv_image_x = (image_width + ROUND(image_width * (((atan2((360 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END) * PI() / 180)) * cos((pitch * PI() / 180.0)) * sin((heading * PI() / 180.0)) + (canvas_x - 360) * SIGN(cos((pitch * PI() / 180.0))) * cos((heading * PI() / 180.0)) + (240 - canvas_y) * -sin((pitch * PI() / 180.0)) * sin((heading * PI() / 180.0)), (360 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END) * PI() / 180)) * cos((pitch * PI() / 180.0)) * cos((heading * PI() / 180.0)) + (canvas_x - 360) * -SIGN(cos((pitch * PI() / 180.0))) * sin((heading * PI() / 180.0)) + (240 - canvas_y) * -sin((pitch * PI() / 180.0)) * cos((heading * PI() / 180.0))) * 180.0 / PI())::DECIMAL % 360 + 360)::DECIMAL % 360 - (new_photographer.photographer_heading + 180)::DECIMAL % 360) / 360)) % image_width,
    sv_image_y = (image_height / 2) - ROUND((image_height / 2) * ((asin(((0.5 * 720 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END) * PI() / 180)) * sin((pitch * PI() / 180.0)) + (480 / 2 - canvas_y) * cos((pitch * PI() / 180.0))) / sqrt(((0.5 * 720 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END)*PI()/180)) * cos((pitch * PI() / 180.0)) * sin((heading * PI() / 180.0)) + (canvas_x - 720 / 2) * SIGN(cos((pitch * PI() / 180.0))) * cos((heading * PI() / 180.0)) + (480 / 2 - canvas_y) * -sin((pitch * PI() / 180.0)) * sin((heading * PI() / 180.0)))^2 + ((0.5 * 720 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END)*PI()/180)) * cos((pitch * PI() / 180.0)) * cos((heading * PI() / 180.0)) + (canvas_x - 720 / 2) * -SIGN(cos((pitch * PI() / 180.0))) * sin((heading * PI() / 180.0)) + (480 / 2 - canvas_y) * -sin((pitch * PI() / 180.0)) * cos((heading * PI() / 180.0)))^2 + ((0.5 * 720 / tan(0.5 * (CASE WHEN zoom = 1 THEN 89.75 WHEN zoom = 2 THEN 53 ELSE 195.93 / 1.92^3 END)*PI()/180)) * sin((pitch * PI() / 180.0)) + (480 / 2 - canvas_y) * cos((pitch * PI() / 180.0)))^ 2)) * 180.0 / PI()) / 90))
FROM label
INNER JOIN gsv_data ON label.gsv_panorama_id = gsv_data.gsv_panorama_id
INNER JOIN (
    SELECT gsv_panorama_id, photographer_heading
    FROM (
        SELECT gsv_panorama_id, photographer_heading, row_number() OVER (PARTITION BY gsv_panorama_id ORDER BY label_id DESC)
        FROM label
    ) temp WHERE row_number = 1
) new_photographer ON label.gsv_panorama_id = new_photographer.gsv_panorama_id
WHERE label_point.label_id = label.label_id
    AND gsv_data.image_width IS NOT NULL
    AND gsv_data.image_height IS NOT NULL;

# --- !Downs
UPDATE label_point
SET sv_image_x = old_sv_image_x,
    sv_image_y = old_sv_image_y
FROM old_label_metadata
WHERE label_point.label_id = old_label_metadata.label_id;

DROP TABLE old_label_metadata;
