# ledger-core-mal

An in-memory account ledger core in Java 21. It has no web layer, no persistence, no UI and no
database. It replays a ten-event stream over a six-day window for two accounts (ACC-001 in AED
and ACC-002 in BHD) and prints a per-day report: the closing ledger balance, fee assessments,
authorization states and errors.

## Run

You need only a JDK (21 or later). There is no build tool, no test framework and no network use.

```
bash run.sh    # compile, replay the stream, print the report (exits 1 if any invariant fails)
bash test.sh   # compile, run 32 tests, then the replay as a smoke test
```

The saved output of the last run is in `out/replay.txt` and `out/test-results.txt`.

## Results at a glance

| | |
|---|---|
| Rejected events | **E6** (unknown authorization Auth-Z) and **E10** (booked into closed D5, and 10.000 BHD can't be split into three equal 3-decimal parts) |
| Auth-A | approved (available 50.00), settled 185.00, 15.00 released |
| Auth-B | declined (available would be −295.00) |
| ACC-001 closes as reported each evening | D1 250.00 · D2 250.00 · D3 650.00 · D4 465.00 · D5 −230.00 · D6 465.96 |
| Fees | three, for D2, D4 and D5, all booked on D5 when E7 arrived back-dated to D2 |
| Refunds | three, booked and value-dated D6 after E9 reversed E7 |
| Final value-dated closes | D1 250.00 · D2 225.00 · D3 625.00 · D4 415.00 · D5 390.00 · D6 465.00 |
| Interest | 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.19 = 0.96, capitalized on D6 |
| ACC-002 | 0.000 every day |

## Reading the report

Each day block has these sections:

- **Events processed:** every event that arrived while the day was open, with its outcome
  (`POSTED`, `APPROVED`, `DECLINED` or `REJECTED`). Authorizations show the arithmetic behind the
  decision.
- **Closing ledger balance:** the sum of all entries with value date ≤ that day, *as known at that
  close*. This is what the bank would have reported that evening. On D6 the figure before interest
  capitalization is shown too.
- **Value-dated view at this close:** D1 to that day, as known at that close. A `*` marks a day
  whose balance differs from what its own close reported. On D5 you can see E7's restatement
  (D2 −395.00\*), and on D6 E9's reversal.
- **Fee assessments booked today:** fees and refunds *booked* that day. A fee line shows the day
  it is for and the balance before the fee that triggered it. A refund line shows which fee it
  offsets.
- **Authorization states at end of day:** each hold (ACTIVE, or SETTLED with the captured and
  released amounts) and each declined authorization.
- **Errors:** rejected events with every reason code, and any rule the engine could not apply.

The **End of window** block shows the interest accruals (one per day, derived from the final
closes), the final value-dated balances, every event outcome, the full journal in append order
(memo entries are hold records that don't move the ledger balance), and the invariant results.

## Design

- **Balances are derived, never stored.** The balance for a day is the opening balance plus every
  ledger entry with value date ≤ that day. Available balance and hold states are derived as well.
- **The journal is append-only.** It has no update or delete operation, and callers get an
  unmodifiable view. Corrections are new entries: a reversal mirrors the original, and a fee refund
  offsets a fee. The engine snapshots the journal at every close, and an invariant checks that
  each snapshot is an unchanged prefix of the final journal.
- **Rejected events never reach the journal.** Each one is logged with its reason codes.
- **Restate.** When a back-dated entry lands on a closed day, fees for the closed days are
  re-decided at once. Missing fees are booked today with the value date of the day they are for.
  Fees that are no longer justified are refunded, booked and value-dated today. See AMBIGUITIES
  A1 to A8.
- **Money is exact.** `BigDecimal` at the currency's scale is enforced in the `Money` constructor.
  Posting amounts are never rounded; only interest is, once per day, HALF_EVEN.
- **Deterministic and single-threaded.** The same stream always produces the same journal.

## Package layout

Each package depends only on the ones above it in this list.

| Package | Contents |
|---|---|
| `ledger.money` | `Currency` (AED = 2, BHD = 3 decimal places) and `Money` (`BigDecimal` fixed to the currency's scale) |
| `ledger.domain` | `Day` (D1..D6), `AccountId`, `Account`, and sealed `Event` (Credit, Debit, Authorization, Settlement, Reversal) |
| `ledger.journal` | `Entry` (immutable), `EntryKind`, the append-only `Journal` with `balanceAsOf`, and `HoldBook` (derives hold state from the journal) |
| `ledger.rules` | `EventValidator` (accept or reject with `Reason` codes), `AuthorizationPolicy`, `OverdraftFeePolicy`, `InterestPolicy` |
| `ledger.engine` | `LedgerEngine` (`submit`, `closeDay`, `finish`), `DayClose`, `EventOutcome`, `Invariants` |
| `ledger.replay` | `Scenario` (the ten events), `ReportPrinter`, and `Replay` (the main entry point) |

Tests live in `src/test/java/ledger/` (`Harness` and `LedgerTests`).

## Documents

| File | Contents |
|---|---|
| `REJECTED.md` | The refused events (E6, E10) and the refused claims, each with its reasoning |
| `AMBIGUITIES.md` | Every open point in the brief and how it was resolved, including the decisions the user made |
| `NUMBERS.md` | Every constant, why it has that value and not half of it, and the output figures to cross-check |
| `WORKLOG.md` | A timestamped record of the build, including mistakes caught along the way |
