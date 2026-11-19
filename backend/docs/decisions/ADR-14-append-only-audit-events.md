# ADR-14 - Audit events are append-only and exempt from this disposal workflow

**Status:** Decided under delegated technical authority (ADR-14, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision: Audit events are never disposed of by this workflow. Their retention is at least the longest retention of any source record they describe, which avoids circular deletion. Disposal authorization and disposal outcome each emit an append-only Audit event containing batch digest, actor identities, policy version and result, with no personal data beyond identifiers.

Standard: Audit-trail integrity.

Closes: S8-06A-AUD-01. Covers the DSP-06 criterion (design side).

Note: DSP-06 has no original decision ID.
