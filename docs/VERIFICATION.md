# Final verification evidence

Verified October 5, 2026 on macOS with OpenJDK 27 (27+35-2325). Maven compiles against the Java 17 API and produces Java 17-compatible classes. Java 17/21 GitHub verification is configured; a remote CI run has not been performed.

## Results

| Check | Result |
| --- | --- |
| IntelliJ project build | Passed; no compilation problems |
| Checkstyle gate | Passed; zero violations |
| Full Maven verification with graphical workflow enabled | 87 tests; zero failures, errors, or skips |
| Dependency-inclusive executable JAR | Built successfully |
| Packaged startup, new demo database | Passed; eight accounts initialized |
| Packaged startup, new --no-demo database | Passed; only the initial administrator created |
| Git diff whitespace check | Passed |

All database checks use temporary or isolated verification files. Existing operational data and the submitted milestone documents were not changed.

## Test coverage

| Suite | Cases | Evidence |
| --- | ---: | --- |
| DutyPeriodTest | 2 | Duration and invalid interval behavior |
| FatigueRuleEngineTest | 6 | Rest boundaries, duty totals, overlap, and evaluator behavior |
| Part117RulesTest | 50 | Table A/B examples, exact/exceeded limits, clipped and peak totals, calendar window, extensions, postflight rest, missing data, special-operation review |
| TemporalValidationTest | 5 | Strict dates, DST ambiguity/gaps, elapsed time, flight containment, sleep bounds |
| DatabaseManagerTest | 3 | SQL initialization, seed and relational record persistence |
| PersistenceWorkflowTest | 8 | CRUD, revisions/cascades/audit, alert review/resolution, account constraints, preview/save distinction, parameterized SQL, legacy migration |
| AuthAndRecoverySystemTest | 10 | Role-scoped data, forged replay rejection, salted/legacy credentials, session changes, throttling, outage/restart, actual SQLite write lock, conflict retention |
| ReportExporterTest | 2 | CSV metadata, quoting/multiline values, spreadsheet formula protection |
| DesktopSmokeTest | 1 | Production Swing layout/search/filter, actual DutyEditor input, service persistence and table refresh |

The graphical test creates native Swing peers in a graphical session and captures only application components. It is optional in ordinary headless builds: 86 non-graphical cases run, and the graphical case is skipped. The final local verification enabled it.

## Reproduce

```sh
mvn clean verify
# In a graphical session, include the Swing workflow:
FRMS_UI_TEST=true mvn verify
java -jar target/flight-duty-frms-1.0.0.jar --headless --database target/verification/demo.sqlite
```

On this Mac, replace mvn with the quoted path below when Maven is not on PATH:

```sh
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn'
```

Run IntelliJ and Maven builds sequentially because they share target/classes. Test summaries and XML reports are written to target/surefire-reports/. Generated graphical captures are written to target/ui-preview/.

## Delivery

- Executable application: target/flight-duty-frms-1.0.0.jar; SQLite and runtime dependencies included.
- Submission archive: target/frms-capstone-1.0.0.zip; source, tests, build/check configuration, documentation, screenshots, JAR, and final test reports.
- Local implementation branch: feature/capstone-finalization.

Final JAR SHA-256:

```text
03bfcd91a771381441b0caad0c39d67e16bc9e672a7602664b5393583b29361d
```

The checksum identifies this build; rebuilding can change it. Maven clean removes generated target artifacts, including the submission archive.

Screenshots: [overview](images/overview.png), [duty records](images/duty-records.png), [duty editor](images/duty-editor.png), and [sign-in](images/sign-in.png).

## Practical limits

This evidence supports the implemented capstone workflows and [declared regulatory model](REGULATORY_SCOPE.md). It does not certify operational dispatch decisions, unmodeled Part 117 provisions, independent human peer review, or remote deployment. The supplied PR template, standards, and CI configuration support a subsequent repository review.
