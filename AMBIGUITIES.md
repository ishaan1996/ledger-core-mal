# AMBIGUITIES.md

This file lists every point the brief leaves open and how each one is resolved. Each entry gives
the question, the options, the decision and whether it changes the printed output. Where the
resolution is visible in code or tests, they are named.

**Legend:** 🔴 decided by the user · 🟠 changes this replay's output, default chosen by the AI ·
⚪ no effect on this replay, but the engine still had to choose.

---

## Back-dating, restatement and fees

### A1 🔴 What happens to closed days when a back-dated entry arrives? (restate vs freeze)
E7 is booked on D5 with value D2. D2 to D4 had already closed with no fee.
- **Restate:** re-decide the fees for the closed days on current knowledge as soon as the entry
  posts.
- **Freeze:** judge each day once, at its own close, and let back-dated entries move later
  balances only.

**Decision (user): Restate.** The rule defines a day's closing balance as all entries with
value date ≤ that day, so a back-dated entry belongs to the day it is dated to. There is no
freeze switch. The effect is three fees (value days D2, D4 and D5) instead of the one Freeze would
give (D5 only). See `LedgerEngine.submit` → `reconcileFees`.

### A2 🔴 When the entry that caused fees is reversed, are the fees refunded?
**Decision (user): yes, by offsetting entries.** After E9, none of the three fee days would have
been negative. Each fee gets a `FEE_REFUND` entry of +25.00 that references the fee's sequence
number. The fee entries stay. Nothing is deleted.

### A3 🔴 Which value date do fee refunds carry?
- Value-dated to the fee's own day, which would erase the fee from D2..D5 retroactively, or
- value-dated to the day the refund is booked.

**Decision (user): booked and value-dated D6.** D2 to D5 therefore keep their fee-reduced closes
on final knowledge (225.00, 625.00, 415.00, 390.00), and fees net to zero from D6. This is
deliberately asymmetric with A4: a fee is dated to the day it is *for* (the rule says so), while
a refund is a new event dated to when it happens.

### A4 🟠 Which days does a restated fee carry?
"Booked with value date equal to the day assessed" is read as the day whose balance was
tested. The D2 and D4 fees are **value D2 / D4, booked D5**. Both days are kept on every entry,
so it stays visible that the fee was decided later than the day it is for.

### A5 🟠 How does the engine know which fees a reversal "caused" to be unjustified?
It does not track causes. At every reconciliation, `OverdraftFeePolicy.daysRequiringFee`
recomputes from scratch which days deserve a fee. It walks D1 onward, taking all non-fee entries
plus the fees it has already decided for earlier days, and **ignores the fee and refund entries
already on the books**. The engine then compares that set with the fees currently standing. It
books any missing fee and refunds any fee no longer required. This makes reconciliation
idempotent (running it twice adds nothing) and independent of history. An invariant checks that,
at the end, the fees still standing are exactly the fees the rule requires on final knowledge.

### A6 🟠 Is a day's fee decided on the balance before or after that day's own fee? Do earlier fees count?
It is decided **before its own fee**, otherwise the fee would trigger itself. **Earlier days' fees
count**, because they are real entries with an earlier value date. This matters here: the D4
check sees 5.00 − 185.00 = −180.00, which already includes the D2 fee.

### A7 🟠 "Assessed once per day per account": once ever, or once net?
Read as **at most one standing (un-refunded) fee per account per value day**. Reconciliation
never books a second fee for a day that already has one standing. If a refunded day later
became negative again, it could get a new fee. That does not happen here; the invariant
`at most one fee per day` checks the net count.

### A8 ⚪ When are closed days re-decided?
Right away, when a back-dated posting to a closed day is accepted: for E7 at D5 and for E9 at
D6. The open day is decided at its own close. So E7's D2 and D4 fees are booked when E7 posts,
and the D5 fee is booked at the D5 close.

---

## Interest

### A9 🟠 Accruals are derived from the final closing balances at Day 6
- **At each close, on what is known then:** D1 0.10, D2 0.10, D3 0.26, D4 0.19, D5 0.00 (negative),
  and then a set of adjustment rows would be needed once E7 and E9 changed D2 to D5.
- **Once, at the D6 close, from the final value-dated closes.**

**Decision: the second.** The rule says interest is on the *closing ledger balance*, and under
Restate (A1) the correct closing balance of a day is the one known at the end, including every
back-dated correction. Capitalization happens once, at the end of D6, so D6 is the first moment
every day's balance is final. Deriving then gives one accrual row per day, with no adjustment
rows. Each row is on a balance that can be checked against the final value-dated view, and the
rows sum exactly to the capitalized credit. Because refunds are value-dated D6 (A3), the D2 to D5
bases still carry the fees (225.00, 625.00, 415.00, 390.00). That is the balance the ledger
really shows for those days. Result: 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.19 = **0.96**.

This combines with A3 in a way that under-pays: D2 to D5 still carry fees that were later
refunded, so the customer earns 0.07 less than if E7 had never happened (1.03 vs 0.96). A
failing test shows it. See **KNOWN_GAPS.md, GAP-1**.

### A10 🟠 Round each day, or round the total once?
**Each day**, because the rule requires the rounded daily accruals to sum exactly to the
capitalized total. Here the difference is visible: the rounded dailies sum to 0.96, while the
exact total 0.948 would round to 0.95 once. The test `33.75 × 6 days = 0.06, not 0.08` pins the
same effect.

### A11 🟠 Rounding mode
**HALF_EVEN** (banker's rounding). HALF_UP rounds every tie up and so overpays systematically
over many accruals, while HALF_EVEN has no bias. No tie occurs in this replay, so the output is
the same either way. A test pins the ties (12.50 → 0.00 and 37.50 → 0.02). See NUMBERS.md.

### A12 ⚪ Interest on zero or negative balances
"Positive balances only": zero earns zero, and negative balances neither earn nor pay. There is
no debit interest in the brief. D5 is negative as known at its own close, but on final knowledge
it is +390.00, so it earns.

### A13 ⚪ Does the capitalized interest itself earn interest?
**No.** The D6 accrual is on 465.00, the close before capitalization. Capitalization is the last
act of the window, so there is no later day for it to earn on, and including it would make the
total depend on itself. Test: `D6 accrual base excludes the capitalization itself`.

### A14 ⚪ Rate interpretation
0.04 % per day = 0.0004 per day, simple, applied to each day's close. There is no /365 and no
compounding inside the window, because accruals capitalize once, at the end.

### A15 ⚪ An account with nothing to capitalize
ACC-002 accrues 0.000 every day. No zero-amount capitalization entry is posted, since an entry
that moves nothing is noise. The accrual rows are still recorded, and the invariant still checks
0.000 = 0.000.

---

## Time and ordering

### A16 🟠 Replay order vs booked day
The stream is replayed **in the order given** and never re-sorted by booked day. Time only moves
forward: an event booked on a later day first closes every day before it. An event booked on a
day that has already closed is refused (`BOOKED_DAY_CLOSED`). This is what rejects E10.

### A17 ⚪ Does a rejected event still advance the clock?
Yes. An event arriving with a later booked day is evidence that the day has moved on, valid or
not. The clock moves before validation.

### A18 ⚪ Forward-dated entries (value day after booked day)
None are in the stream. They are refused (`VALUE_DAY_AFTER_BOOKED_DAY`), because the brief gives
no rule for them.

### A19 ⚪ Events after the window
After the D6 close, any event is refused (`WINDOW_CLOSED`).

---

## Authorizations and holds

### A20 🟠 Which balance does an authorization test?
The **ledger balance for the booked day on everything known at arrival** (all entries with value
date ≤ that day, including back-dated ones), minus active holds, minus this hold. E7 precedes E8,
so Auth-B sees −205.00 and is declined at −295.00.

### A21 🟠 Are authorization decisions ever re-run?
**No.** A card decision is made in real time. E9 later restores the balance, but Auth-B stays
declined, because approving it after the fact would be deciding a card transaction the merchant
has already been told about.

### A22 ⚪ Declined vs rejected
A **declined** authorization is valid but refused by the balance rule. It is a business outcome,
listed under authorization states. A **rejected** event is malformed or impossible and is listed
under errors. Neither posts anything.

### A23 🟠 What happens to the unsettled remainder of a hold?
It is **released at settlement**: Auth-A settles 185.00 of 200.00, and 15.00 is released at once.
The model is single-capture: one settlement closes the hold. A second settlement against the same
authorization is refused (`AUTHORIZATION_NOT_ACTIVE`).

### A24 ⚪ Holds that are never settled
There is no expiry rule in the brief. A hold that is never settled would stay ACTIVE past the
window. Auth-B never became a hold, so this does not arise here.

### A25 ⚪ Settlement larger than the hold
It is refused (`SETTLEMENT_EXCEEDS_HOLD`). Capturing more than was authorized would bypass the
authorization rule.

### A26 ⚪ Where hold state lives
Per the approved design, `HoldBook` stores nothing. It derives ACTIVE / SETTLED / RELEASED from
**memo** journal entries (`HOLD_PLACED`, `HOLD_SETTLED`, `HOLD_RELEASED`). Those entries are
excluded from the ledger balance, so a hold changes available balance only.

---

## Events and amounts

### A27 🟠 E10 handling
The whole event is refused, for both reasons, and nothing is partially posted. See REJECTED.md.

### A28 ⚪ Instalments that do divide
They are posted as *n* equal credit entries, one per instalment, all with the same value day.
Test: BHD 9.000 / 3 = 3.000.

### A29 ⚪ Currency mismatch
An amount in a currency other than the account's is refused (`CURRENCY_MISMATCH`). The engine
never converts.

### A30 ⚪ The fee is quoted in AED, but ACC-002 is BHD
No FX rate is given, so no BHD fee is invented. If a BHD account closed negative, the engine would
record an error ("no overdraft fee is defined in BHD") and assess nothing. That does not happen
here. A test covers it.

### A31 ⚪ Reversal rules
Only credits and debits can be reversed. The reversal must be on the same account, carry the
**original's value day**, and target something not already reversed. The mirror entry has the
original's value day (D2 for E9), which is why E9 changes the D2 to D5 closes.

### A32 ⚪ Duplicate event ids
The second event with the same id is refused (`DUPLICATE_EVENT_ID`).

### A33 ⚪ Non-positive amounts
Refused. Direction comes from the event type, not the sign.

---

## Output and tooling

### A34 🟠 What does "closing ledger balance per day" print?
Three views, because back-dating makes one number ambiguous:
1. **At each close:** what the bank would have reported that evening (D5: −230.00).
2. **The value-dated view at each close:** D1 up to that day as known then. `*` marks a day whose
   number changed after its own close.
3. **The final view:** every day on everything known at the end (D2: 225.00).

### A35 ⚪ Opening balance
It is held on `Account`, not posted as an entry. The invariant `opening + all entries = final
balance` makes the relationship explicit.

### A36 ⚪ No build tool or test framework
`bash test.sh` needs only a JDK. The harness is a small class of `check` / `eq` helpers, which
avoids a Maven or JUnit download and the network access it would need.
