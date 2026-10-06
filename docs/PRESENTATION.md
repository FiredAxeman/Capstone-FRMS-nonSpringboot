# Final capstone demonstration

Build with **mvn clean verify**, then use a new sample database:

```sh
java -jar target/flight-duty-frms-1.0.0.jar --database demo/presentation.sqlite
```

Use a new filename if that database already exists so sample intervals reflect the presentation date. Sign in as **admin / admin** for a newly seeded database.

## Five-to-eight-minute walkthrough

1. Explain the spreadsheet burden: separate flight/FDP hours, rolling windows, rest, and management visibility. Introduce the declared capstone scope.
2. Show overview metrics, search, status filters, and pilot utilization. Alex demonstrates rest findings; Casey approaches cumulative duty; Riley needs specialist review; Morgan is still resting.
3. Open a duty assessment. Explain report, FDP end, release, exact flight legs, and individual rule findings.
4. Use Evaluate assignment for Jamie after qualifying rest. Confirm sleep/fitness and add a flight leg. Compare a modeled passing scenario with a daily flight exceedance. No actual record is created.
5. Demonstrate a hypothetical 07:00-report, one-segment, 16-hour FDP: a 14-hour Table B base plus a conditional two-hour extension needs unforeseen circumstances, both concurrence inputs, notes, cumulative headroom, and no repeat major extension since qualifying rest.
6. Show actual/rest editors, alert review, record correction, and the audit trail. Actual findings remain in history.
7. Sign in as **alex.morgan / pilot-demo** to show the personal scope, then **dispatcher / dispatch-demo** for fleet oversight without account administration.
8. Explain local journaling, retries, conflict checks, and restart idempotency. Show verification evidence and the packaged JAR; finish with the operational limits.

## Suggested slides

| Slide | Content |
| --- | --- |
| 1 | Title, student/course, business problem |
| 2 | Final workflows and overview screenshot |
| 3 | Inheritance, interface, service/UI/database architecture |
| 4 | Flight/FDP/release/rest timeline |
| 5 | Tables, rolling totals, rest, extension example |
| 6 | Roles, persistent findings, audit |
| 7 | Journal, retry, conflict, restart recovery |
| 8 | Test evidence, runnable delivery, scope |

Code anchors: User/Pilot for OOP; DutyPeriod/FlightLeg/RestPeriod for temporal validation; FatigueRuleEngine for evaluation; DatabaseManager for SQL; FrmsService/DurableOutbox for permissions/recovery; ComplianceDashboard for rendering; test/ for verification.

Describe the included CI and PR infrastructure without claiming an unpublished GitHub run or completed human peer review.
