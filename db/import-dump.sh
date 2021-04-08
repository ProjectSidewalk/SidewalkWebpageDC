#!/bin/bash
dbname=${1:-sidewalk}

psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<-EOSQL
    SELECT pg_terminate_backend(pg_stat_activity.pid)
    FROM pg_stat_activity
    WHERE (pg_stat_activity.datname = '$dbname')
    AND pid <> pg_backend_pid();

    DROP DATABASE IF EXISTS "$dbname";
    DROP SCHEMA IF EXISTS sidewalk;

    DO \$\$
    BEGIN
      CREATE USER sidewalk WITH PASSWORD 'sidewalk';
      EXCEPTION WHEN DUPLICATE_OBJECT THEN
      RAISE NOTICE 'role sidewalk already exists, skipping';
    END
    \$\$;

    CREATE DATABASE "$dbname" WITH OWNER=sidewalk TEMPLATE template0;
    GRANT ALL PRIVILEGES ON DATABASE "$dbname" to sidewalk;

    ALTER USER sidewalk SUPERUSER;
    GRANT ALL PRIVILEGES ON DATABASE "$dbname" TO sidewalk;

    CREATE SCHEMA sidewalk;
    GRANT ALL ON ALL TABLES IN SCHEMA sidewalk TO sidewalk;
    ALTER DEFAULT PRIVILEGES IN SCHEMA sidewalk GRANT ALL ON TABLES TO sidewalk;
    ALTER DEFAULT PRIVILEGES IN SCHEMA sidewalk GRANT ALL ON SEQUENCES TO sidewalk;
EOSQL

pg_restore -U sidewalk -d $dbname /opt/$dbname-dump
