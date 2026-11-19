# ADR-01 — Retention eligibility is read-only

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

RLC can calculate eligibility from evidence and a policy version. Eligibility alone does not establish authority to archive, transfer, or destroy a record.

## Decision

Retention evaluation is a non-destructive calculation. It may return an explainable eligibility result and provenance, but it must not delete, archive, transfer, or authorize disposal.

## Consequences

- Any future disposal workflow must be separate, explicitly authorized, and owner-enforced.
- Missing, conflicting, stale, or unapproved inputs remain fail-closed.
- Evaluation history remains evidence of a calculation, not a destruction instruction.

This project direction does not validate the proposed retention schedule or close the open S8-06 decisions.
