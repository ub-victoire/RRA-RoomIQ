# ADR-11 - Legal holds always win

**Status:** Decided under delegated technical authority (ADR-11, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision: An active, unknown or unreachable hold blocks eligibility-to-disposal progress. Hold release requires acknowledgement from the owning service (already implemented through RecordsHoldAcknowledgementService). Any doubt about hold state is treated as "held". Archive and backup copies inherit the hold of their source record.

Standard: Fail-closed design.

Closes: S8-06A-HOLD-01 to HOLD-05 (design side).
