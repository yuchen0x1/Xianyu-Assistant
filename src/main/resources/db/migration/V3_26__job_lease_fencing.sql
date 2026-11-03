-- Job lease fencing: each RUNNING claim gets a unique token so stale workers
-- cannot renew or overwrite the state after the lease has been recovered.
ALTER TABLE job_definition ADD COLUMN lease_token VARCHAR(100);
