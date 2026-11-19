# ADR-13 - Archive behind a port; implementation deferred

**Status:** Decided under delegated technical authority (ADR-13, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision: Archiving is defined as an ArchiveStore port. Requirements for any adapter: versioned manifest; per-object and per-batch SHA-256; write-once (WORM or object-lock) storage; envelope encryption with managed keys; role-based access with MFA for human access; independent integrity verification; a restore drill as acceptance evidence; monitoring and alerting. Hosting must be RRA-controlled or Rwanda-resident infrastructure, subject to data-protection law on cross-border transfer. No provider or storage path is hard-coded. Only an in-memory test adapter is permitted until a real adapter is provisioned.

Standard: ISO 27001 controls, ISO 14721 (OAIS) concepts.

Closes: S8-06A-ARC-01 to ARC-07 (design side), SEC-01, DB-01, OPS-01, OPS-02.

Note: ARC-08 is a readiness criterion with no original decision ID.
