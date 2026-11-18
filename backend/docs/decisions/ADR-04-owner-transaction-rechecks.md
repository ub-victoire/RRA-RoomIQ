# ADR-04 — Owner-transaction rechecks fail closed

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

Eligibility, policy, source records, and legal holds can change after a batch is prepared or authorized. A central decision made earlier cannot guarantee the owner-side state at an irreversible operation.

## Decision

Before any future owner-local destructive step, the owner must recheck the exact authorized batch digest, applicable policy/version and eligibility evidence, and current hold state/fence in its own authoritative database transaction. Any missing, stale, conflicting, unavailable, or changed input blocks execution.

## Consequences

- The owner transaction is the final enforcement boundary.
- A held, uncertain, or changed record is excluded or causes the batch to stop for reconciliation.
- Concurrency, retries, release ordering, and the approved hold protocol remain prerequisites.

This project direction does not implement a destructive operation or replace formal hold approval.
