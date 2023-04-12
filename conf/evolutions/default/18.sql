# --- !Ups
INSERT INTO version VALUES ('5.1.0', now(), 'Data in APIs used for CV have been fixed.');

# --- !Downs
DELETE FROM version WHERE version_id = '5.1.0';
