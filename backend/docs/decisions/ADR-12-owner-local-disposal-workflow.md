# ADR-12 - Disposal is separate from eligibility and is owner-local

**Status:** Decided under delegated technical authority (ADR-12, 2026-11-19).

**Authority:** Technical decision authority for RoomIQ Stage 8 is delegated by the RRA CTO. Reference: verbal delegation by the RRA CTO on 12 November 2026; written confirmation pending (action owner: Victoire B. Ushindi).

Decision:
1. Eligibility is read-only and never authorizes disposal.
2. Disposal is a workflow run by the source-owning service with distinct steps: prepare, review, authorize, execute, reconcile. RLC coordinates status and references only and never touches another service's database.
3. Authorization binds to a fixed batch snapshot and its SHA-256 digest. Any change to the batch invalidates the authorization.
4. Separation of duties: the preparer, reviewer and authorizer must be distinct people. Authorization expires after a configured window.
5. Immediately before execution, inside the owner's transaction, re-check policy eligibility and active holds. Fail closed on any doubt or error.
6. Dry-run is the default mode. The executor is behind a feature flag that is OFF until a retention schedule is loaded.
7. Media sanitization follows NIST SP 800-88 where physical or replica data applies.
8. Execution is idempotent and records per-record outcomes for reconciliation.

Standard: NIST SP 800-88, separation of duties, least privilege.

Closes: S8-06A-DSP-01 to DSP-05 (design side), TECH-02, TECH-03, OWN-01, OWN-02.
