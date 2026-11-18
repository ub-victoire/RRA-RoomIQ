# ADR-07 — Audit evidence outbox uses JDBC

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

The Audit source-evidence outbox is operational delivery state, not an immutable audit record. Its schema and relay are implemented with Flyway SQL and JDBC.

## Decision

Keep the outbox JDBC-managed. Do not add a JPA entity for `AUDIT_SOURCE_EVIDENCE_OUTBOX`. Permit the runtime role's required `SELECT`, `INSERT`, and `UPDATE` privileges; do not add mutation-rejection triggers to this outbox. The separate `audit_event` record remains append-only.

## Consequences

- Flyway migration and JDBC repository remain the authoritative persistence mapping.
- `audit_event_id` remains unique, with no physical cross-service foreign key.
- ERD documentation records SQL widths, nullability, defaults, checks, and the actual due-index name.
- Runtime outbox `DELETE` and `TRUNCATE` remain denied by grants; mutable delivery-state updates remain supported.

This project direction documents the implementation boundary and does not change privileges or migrations.
