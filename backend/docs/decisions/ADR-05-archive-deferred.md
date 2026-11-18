# ADR-05 — Archive implementation is deferred

- Date: 2026-11-18
- Decision status: Project direction recorded; not RRA approval.

## Context

The repository does not establish an approved archive destination, owner, residency boundary, format, key lifecycle, access model, integrity process, or tested recovery path.

## Decision

Do not implement archive transfer or select a production archive provider until the applicable RRA archive decisions and source-owner contracts are resolved. Existing source records remain under their current owners.

## Consequences

- There is no archive endpoint, adapter, manifest, or archive-based disposal prerequisite in this project direction.
- A later proposal should prefer an approved existing repository if it meets the approved controls; otherwise, evaluate a new RRA-approved destination.
- Archive reconciliation and recovery remain independent acceptance requirements.

This project direction does not mark any archive decision resolved.
