# ADR-10 - Time semantics

**Status:** Decided under delegated technical authority (ADR-10, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision: All timestamps are stored as UTC TIMESTAMPTZ. A policy's effective_from is inclusive and effective_to is exclusive, as the existing RetentionEligibilityCalculator tests assert. Expiry equal to the evaluation instant counts as eligible. Evaluation uses an injected Clock, never the system clock directly.

Closes: S8-06A-TIM-01, TIM-02, TIM-03.
