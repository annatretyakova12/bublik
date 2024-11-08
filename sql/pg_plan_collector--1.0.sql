-- pg_plan_collector--1.0.sql

BEGIN;

CREATE UNLOGGED TABLE IF NOT EXISTS query_plans (
    id BIGSERIAL PRIMARY KEY,
    query_text TEXT,
    plan_text TEXT,
    queryid NUMERIC,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

COMMIT;