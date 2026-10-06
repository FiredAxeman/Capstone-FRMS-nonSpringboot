# Regulatory basis and calculation assumptions

The rule table was checked against [14 CFR Part 117 on eCFR](https://www.ecfr.gov/current/title-14/chapter-I/subchapter-G/part-117) on October 5, 2026. FRMS is an educational unaugmented-operation prototype.

| Provision | Modeled behavior |
| --- | --- |
| Table A / §117.11(a)(1) | Eight flight hours at 00:00–04:59 and 20:00–23:59 report times; nine at 05:00–19:59 |
| Table B / §117.13(a) | All ten report bands and seven segment columns; seven or more segments use the last column |
| §117.13(b) | Non-acclimated FDP reduced 30 minutes; use the last acclimated theater's reference zone |
| §117.19(a) | Pre-takeoff extension up to two hours with unforeseen circumstances, PIC/carrier concurrence, and recorded notes |
| §117.19(a)(2/3) | Repeat major extension restriction since qualifying rest; cumulative FDP headroom constrains extension |
| §117.23(b) | 100 flight hours in 672 consecutive hours; 1,000 in 365 consecutive calendar days |
| §117.23(c) | 60 FDP hours in 168 hours; 190 in 672 hours |
| §117.25(b) | 30 consecutive hours free from recorded duty within the previous 168 hours |
| §117.25(e/f) | 10 hours since release from all duty and eight-hour uninterrupted sleep opportunity evidence |
| §117.5 | Fit-for-duty affirmation; missing legacy evidence requires review |
| Project warning policy | Approaching-limit warnings begin at 90% utilization |

[§117.19](https://www.ecfr.gov/current/title-14/chapter-I/subchapter-G/part-117/section-117.19) includes reporting requirements. FRMS displays the reminder but does not submit FAA reports or run corrective-action programs. A requested FDP extension never increases the flight ceiling.

## Time semantics

Report, FDP end, and release from all duty are distinct. Release includes postflight work for overlap/rest calculations. Flight legs use block-out/block-in times. Arithmetic uses resolved instants.

Ambiguous or nonexistent local times during daylight-saving changes are rejected; enter those instants in UTC. Acclimatization is supplied evidence, not inferred from a base.

Rolling totals clip actual intervals at window boundaries. Candidate assessments check interval and sliding-window breakpoints during the FDP, detecting a peak before old hours expire. The annual window includes the candidate's local date and the previous 364 calendar dates, with midnight transitions considered.

Remaining modeled flight time is the minimum daily, 672-hour, and 365-calendar-day headroom. FDP headroom uses daily authorized, 168-hour, and 672-hour ceilings. Missing/unsynchronized inputs require review.

## Completeness assumptions

The ledger must include relevant duty, release, and flight activity across applicable certificate holders/program managers. Opening lifetime hours cannot replace historical intervals. Missing flight legs produce unknown availability.

Gaps between recorded release and report are treated as free from duty. An empty profile assumes a complete, unoccupied lookback. Sleep and fitness require operational evidence.

Certificate and base fields are descriptive. [§117.1](https://www.ecfr.gov/current/title-14/chapter-I/subchapter-G/part-117/section-117.1) establishes air-carrier applicability; ordinary flight instruction is not automatically covered.

## Outside the model

Augmented Table C and in-flight accommodations, airport/short/long-call reserve, split duty, deadhead-specific rest, theater travel/return-to-base rest, consecutive nighttime accommodation, post-takeoff extensions, emergency/government exceptions, and operator-approved FRMS alternatives are not fully modeled.

Declare additional circumstances in the editor and obtain specialist review. The prototype does not establish operational dispatch authority.
