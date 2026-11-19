# ADR-02 — Disposal is owner-local and batch-based

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

Record owners control their own authoritative data and lifecycle transactions. Central cross-service writes or a generic RLC delete endpoint would violate that ownership boundary.

## Decision

If disposal is separately authorized in the future, RLC may coordinate a bounded batch, but each source owner performs its own local operation. No service accesses another service's database, and no cross-service foreign key is introduced.

## Consequences

- Batch membership and the owner's local record snapshot must be explicit and reconcilable.
- Partial outcomes must be reported and reconciled; delivery success is not proof of disposal.
- Archive transfer and disposal execution remain deferred until their decisions and contracts are resolved.

This project direction does not authorize implementation of a disposal workflow.
