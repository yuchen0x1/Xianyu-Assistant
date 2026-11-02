-- Job P0: unique idempotency key and worker lease support.
-- Keep the oldest definition when legacy data contains duplicate keys.
-- 先清除空字符串占位，避免 '' 重复导致唯一索引创建失败。
UPDATE job_definition SET idempotency_key = NULL WHERE idempotency_key = '';
DELETE FROM job_definition
WHERE idempotency_key IS NOT NULL
  AND id NOT IN (
      SELECT MIN(id)
      FROM job_definition
      WHERE idempotency_key IS NOT NULL
      GROUP BY idempotency_key
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_job_definition_idempotency_key
    ON job_definition(idempotency_key);

ALTER TABLE job_definition ADD COLUMN lease_until DATETIME;
