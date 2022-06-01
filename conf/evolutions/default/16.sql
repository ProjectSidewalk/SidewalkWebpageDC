# --- !Ups
INSERT INTO version VALUES ('5.0.1', now(), 'Adds label metadata API endpoint for CV projects.');

# --- !Downs
DELETE FROM version WHERE version_id = '5.0.1';
