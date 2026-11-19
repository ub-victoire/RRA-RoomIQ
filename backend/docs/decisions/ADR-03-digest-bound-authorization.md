# ADR-03 — Digest-bound authorization and separation of duties

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

A batch can change between review and execution. An approval that is not bound to the reviewed contents could authorize a different set of records.

## Decision

Any future disposal authorization must bind to an immutable batch snapshot and its digest, policy/version references, hold fence, and approval sequence. Authorization and execution require distinct authorized identities; independent verification is separate where required.

## Consequences

- A changed snapshot invalidates the prior authorization and requires a new review.
- Approvals, cancellations, and revocations need durable, idempotent evidence.
- The authorization roles, quorum, delegation, and independent-verifier requirements remain open RRA decisions.

This project direction does not establish roles, permissions, or disposal authority.
