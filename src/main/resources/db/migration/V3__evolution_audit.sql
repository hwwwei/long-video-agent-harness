ALTER TABLE evolution_versions ADD COLUMN baseline_version VARCHAR(64);
ALTER TABLE evolution_versions ADD COLUMN activated_at TIMESTAMP;
CREATE INDEX ix_evolution_status ON evolution_versions(status);
