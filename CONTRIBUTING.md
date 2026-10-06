# Development and review standards

Use Java 17-compatible features, four-space indentation, explicit imports, descriptive names, and UTF-8. Format with IntelliJ's standard Java formatter. Keep temporal validation in model objects, rules in the evaluator, authorization/workflows in the service, SQL in persistence, and rendering in Swing.

## Change workflow

1. Create feature/<name> or fix/<name> from the reviewed base.
2. Implement the behavior and meaningful rule/data/security/recovery tests.
3. Run **mvn clean verify** and relevant graphical workflows.
4. Open a pull request using the supplied template; include behavior, sources, and validation.
5. Obtain peer review and passing required checks before merging.

The repository owner should enable protected main/master, at least one approving review, and the Verify capstone status checks. Workflow files do not themselves change GitHub branch protection.

## Standards

- Cite primary eCFR/FAA sources and declare assumptions for rule changes.
- Test exact limits, one-minute exceedances, window intersections, midnight, and clock changes where relevant.
- Keep flight, FDP, and release distinct; preserve unknown data as unknown.
- Bind user data as SQL parameters. Dynamic identifiers must be fixed internal names.
- Apply operational mutations through the service/journal transaction boundary; preserve revisions and command IDs.
- Exclude credentials from journals, reports, audit detail, and logs.
- Run GUI SQL/evaluation outside the event-dispatch thread.
- Use isolated temporary databases; verification must not delete operational databases.

Checkstyle gates naming, explicit/unused imports, structure, empty statements, tabs, and final newlines. JUnit checks rules, persistence, roles, outages, conflicts, and exports. The optional Swing workflow exercises actual controls and records component previews.

Run IDE and Maven builds sequentially when both use target/classes. Review migrations and packaged startup after persistence or dependency changes.
