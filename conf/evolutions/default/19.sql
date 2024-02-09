# --- !Ups
INSERT INTO version VALUES ('5.1.1', now(), 'Updates JDBC driver.');

# --- !Downs
DELETE FROM version WHERE version_id = '5.1.1';
