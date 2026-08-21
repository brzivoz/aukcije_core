-- Issue #16: the spatial extension every later GIS migration depends on.
-- Deliberately not IF NOT EXISTS-only guarded away: on a database without the
-- PostGIS extension available this migration fails, which is the negative
-- control that proves the integration tests really run on PostGIS.
CREATE EXTENSION IF NOT EXISTS postgis;
