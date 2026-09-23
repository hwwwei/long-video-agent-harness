# Long Video Harness Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task by task. Keep the PR project's git history while replacing its tracked application files.

**Goal:** Build a Java 17 interview demo for long-video upload, governed agent processing, benchmark evaluation, and feedback-driven evolution.

**Architecture:** One Spring Boot codebase runs as API and Worker profiles. PostgreSQL stores authoritative state and an outbox; Kafka carries run notifications; Redis tracks upload progress and limits; Redisson coordinates content processing. FFmpeg handles media metadata/audio; deterministic transcript-backed agents make local demos repeatable.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Maven, Spring JDBC, Flyway, Kafka, Redis, Redisson, PostgreSQL, FFmpeg, Micrometer, Docker Compose.

## Tasks

- [ ] 1. Create Maven application, SQL migration, and tests for upload/session persistence.
- [ ] 2. Implement resumable chunk upload, SHA-256 deduplication, transcript API, and media adapter.
- [ ] 3. Implement Kafka outbox, worker claim/lease/checkpoints, budgets, cancellation, resume, and traces.
- [ ] 4. Implement planning, parallel segment agents, blind critic, bounded rework, and structured report.
- [ ] 5. Implement benchmark fixtures, deterministic quality metrics, feedback, evolution gates, activation, and rollback.
- [ ] 6. Replace Dashboard and README; add Compose and CI integration smoke tests, then rename the GitHub repository after a green run.

## Global Constraints

- Mock mode needs user-supplied timestamped transcript for arbitrary video and must label it simulated.
- Upload uses 8 MiB chunks and defaults to a 2 GiB maximum; no GB performance claim without a GB test.
- MCP means an allowlisted read-only adapter only; no remote transport claim.
- Kafka is at-least-once; PostgreSQL state and node-attempt uniqueness provide idempotency.
- Validation runs before Holdout; all four quality metrics must not regress and at least one must improve before activation.

## Review Focus

- Repeated chunk with different bytes returns a conflict while original bytes remain intact.
- Redis loss does not make an uploaded chunk disappear from status.
- Redelivered Kafka event cannot repeat completed agent nodes.
- Cancellation cannot be overwritten by a late worker result.
- Mock summary cites only supplied transcript segments and clearly reports simulated transcription.
