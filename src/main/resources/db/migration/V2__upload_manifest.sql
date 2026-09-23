CREATE TABLE upload_chunks (
  upload_id VARCHAR(36) NOT NULL,
  chunk_index INTEGER NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (upload_id, chunk_index)
);
CREATE INDEX ix_upload_chunks_upload ON upload_chunks(upload_id);
