#!/usr/bin/env bash
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
  --set=project_password="$PROJECT_DB_PASSWORD" \
  --set=tracking_password="$TRACKING_DB_PASSWORD" \
  --set=registry_password="$REGISTRY_DB_PASSWORD" <<'SQL'
CREATE ROLE project LOGIN PASSWORD :'project_password';
CREATE ROLE tracking LOGIN PASSWORD :'tracking_password';
CREATE ROLE registry LOGIN PASSWORD :'registry_password';
CREATE DATABASE project OWNER project;
CREATE DATABASE tracking OWNER tracking;
CREATE DATABASE registry OWNER registry;
REVOKE CONNECT ON DATABASE project FROM PUBLIC;
REVOKE CONNECT ON DATABASE tracking FROM PUBLIC;
REVOKE CONNECT ON DATABASE registry FROM PUBLIC;
GRANT CONNECT ON DATABASE project TO project;
GRANT CONNECT ON DATABASE tracking TO tracking;
GRANT CONNECT ON DATABASE registry TO registry;
SQL
