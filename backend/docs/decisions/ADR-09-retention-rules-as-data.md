# ADR-09 - Retention rules are data, with an empty default schedule

**Status:** Decided under delegated technical authority (ADR-09, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision: Retention rules live in the versioned policy registry as data, not in code. The shipped default schedule is empty, so with no schedule loaded nothing is eligible and every evaluation fails closed. Real retention values and legal citations are "data pending from Legal/Records Management".

Standard: ISO 15489 (records management).

Closes: S8-06A-POL-01, POL-03, POL-04, TIM-02, DSP-01 (design side only).
