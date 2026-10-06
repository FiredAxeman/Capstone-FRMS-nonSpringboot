# CT1–CT7 requirements traceability

The submitted milestones define a functional prototype with programmed Part 117 parameters. Operational workflows and the explicitly modeled calculations are implemented below. This matrix does not claim certification or implementation of every operational exception.

| Commitment | Milestone | Implementation | Verification |
| --- | --- | --- | --- |
| Java application and relational SQL persistence | CT1, CT2 | Main, SQLite schema/migrations, dependency-inclusive JAR | Persistence and packaged startup checks |
| User superclass / Pilot subclass | CT2–CT4 | User, Pilot, Role; credentials and profile data | Authentication/role tests |
| Authentication and authorization | CT2, CT3 | Salted PBKDF2, legacy upgrade, throttling, session revalidation; pilot/dispatcher/admin scopes | AuthAndRecoverySystemTest |
| Flight, duty, and rest logging | CT1–CT6 | DutyPeriod, exact FlightLeg intervals, release from all duty, RestPeriod and sleep evidence | Temporal, CRUD, and real Swing workflows |
| Create/read/update/delete | CT2–CT7 | Full account/duty/rest workflows, cascades, and revision predicates | PersistenceWorkflowTest |
| ArrayLists and HashMaps | CT3, CT6 | Ordered histories; maps linking users, rests, reports, and commands | Bulk fleet-load workflows |
| Evaluator interface | CT3, CT4 | RegulationEvaluator implemented by FatigueRuleEngine; boolean and explanatory outputs | Unit/system tests |
| 30-hour rest / 168-hour window | CT1–CT7 | Occupied interval merging, clipped boundaries, postflight release | FatigueRuleEngineTest, Part117RulesTest |
| Maximum daily flight and FDP | CT1, CT2, CT6, CT7 | Tables A/B, reporting theater, segments, non-acclimated reduction | Independent table/boundary examples |
| Remaining flight availability | CT1–CT3, CT6, CT7 | Daily/rolling/calendar headroom using actual intervals; unknown on missing inputs | Partial-window and peak-total tests |
| Conditional duty extensions | CT1–CT4, CT6, CT7 | Up to 120 minutes, documented unforeseen circumstance, PIC/carrier concurrence, cumulative headroom, repeat restriction | Extension eligibility tests |
| Dashboard and automated warnings | CT1–CT7 | Metrics, search/filter/sort, detail/utilization, stored findings, four status colors, 30-second refresh | Snapshot and Swing workflows |
| Runtime state machine | CT3, CT4 | Authentication, ingestion, evaluation, rendering, offline/recovery states | Workflow tests and architecture diagram |
| Validation and local exception handling | CT5–CT7 | Strict dates, zones/DST, interval containment, overlap, type/length/password checks | Temporal/SQL rejection tests |
| Retry and local fallback | CT5–CT7 | Three attempts, atomic credential-free journal, cached view, checked replay, command idempotency | I/O failure, actual SQLite lock, restart, concurrency tests |
| Persistent alerts and traceability | CT2, CT3, CT7 | Findings, acknowledgements, resolution/reopening, transactional change audit | Alert persistence/recalculation tests |
| Unit, integration, system testing | CT6, CT7 | Deterministic JUnit cases, isolated real SQLite, optional actual Swing input/search/render | Maven verification |
| Shared standards and linters | CT5, CT7 | CONTRIBUTING, EditorConfig, Checkstyle, local gate and CI | Checkstyle and workflow artifacts |
| Feature branches, PRs, peer review | CT7 | feature/capstone-finalization; PR template and CI configuration | Local branch and review infrastructure |
| Delivery and presentation | CT2, CT7 | Runnable JAR, launch options, screenshots, architecture, scope, demo and verification guides | JAR and presentation workflow |

## Scope decisions

**SQL availability.** SQLite makes the desktop capstone runnable without a server. The proposals' network-loss fallback is implemented for embedded-database I/O failure, write locks, and access failures. A remote server adapter is not claimed.

**Operational history.** Malformed temporal input is rejected. Actual regulatory findings remain recorded so history, alerts, and audit remain accurate. Future scenarios use Evaluate assignment.

**Operating authority.** PIC/carrier concurrence is recorded as external operational evidence. The application does not grant approvals or submit FAA reports. See [REGULATORY_SCOPE.md](REGULATORY_SCOPE.md).

**Human review.** The branch, CI, checks, PR template, and standards are supplied. The repository owner must enable required approving reviews and status checks in GitHub branch protection. No remote publication or completed peer review is fabricated.
