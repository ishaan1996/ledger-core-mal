# WORKLOG.md

Real, timestamped record of how this was built. Times are UTC on 2026-09-30, read from the
machine clock (`date -u`) or from project message timestamps. **Human** is Ishaan Arora; **AI** is
Claude. Entries are written as the work happens. Mistakes the AI catches are logged, not hidden.

| Time | Who | What happened |
|---|---|---|
| 19:03:19 | Human | Asked to delete the previous build and start over in `~/code/ledger-core-mal`, discussing the design before writing code. |
| 19:36:33 | Human | Approved the design (packages money → domain → journal → rules → engine → replay; derived balances, holds and states; rejected events never reach the journal). Decided: back-dated entries **restate** closed days; when E9 reverses E7, the three fees are refunded by offsetting entries **booked and value-dated Day 6**; WORKLOG holds real entries only. Said "Let's code now." |
| 19:39:23 | AI | Hand-checked the expected numbers before writing code. ACC-001 closes as known at each close: D1 250.00, D2 250.00, D3 650.00, D4 465.00, D5 −230.00 (D2 restated to −370.00 pre-fee, −395.00 after; D3 5.00; D4 −205.00 after fee). Auth-A available after hold 50.00; Auth-B available after hold would be −205.00 − 90.00 = −295.00, so declined. After E9 and the Day 6 refunds, the final value-dated closes are D1 250.00, D2 225.00, D3 625.00, D4 415.00, D5 390.00, D6 465.00. Daily accruals at 0.04 %, HALF_EVEN: 0.10, 0.09, 0.25, 0.17, 0.16, 0.19 = **0.96**, final **465.96**. Matches the coordinator's estimate. Noted that the unrounded sum is 0.948 (0.95 if rounded once), so "sum of rounded dailies" is a real constraint, not a formality. |
| 19:39:38 | AI | Scaffolded the repo: `git init`, `.gitignore` (build/, IDE files), `build.sh` / `run.sh` / `test.sh` using only `javac` and `java` (`--release 21`, `-Xlint:all -Werror`). Local JDK is OpenJDK 26. |
