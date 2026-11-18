# ADR-06 — Disposal audit evidence is append-only

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

If disposal is authorized in the future, the authorization and outcome must remain traceable. The Audit service's `audit_event` record is protected as append-only; it is not a disposal mechanism.

## Decision

Any future disposal action must emit auditable, append-only evidence of the authorization and outcome. That evidence requires its own approved retention category, source/owner, start rule, and retention schedule so its lifetime is not circularly determined by the record it describes.

## Consequences

- No update/delete/truncate path may be added to `audit_event` as part of disposal.
- The disposal event contract and evidence retention rule must be decided before implementation.
- DSP-06 remains a readiness traceability gap without a matching original S8-06A decision row.

This project direction does not define or approve the disposal event schema or schedule.
