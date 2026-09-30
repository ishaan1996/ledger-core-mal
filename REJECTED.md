# REJECTED.md

Everything this project refuses: two events in the stream, and six claims about the stream that
turn out to be wrong when checked against the rules. For each, the criterion it breaks and the
reasoning. Numbers come from `bash run.sh` (saved in `out/replay.txt`) and are pinned by tests.

A refused **event** stays in the event log with outcome `REJECTED` and every reason code. It is
printed under **Errors** on the day it arrived and posts nothing to the journal. An invariant
checks that no rejected event has any journal entry.

---

## Refused events

### E6: Settlement of Auth-Z, AED 180.00, booked D4

**Criterion:** a settlement captures a hold placed by an earlier *approved* authorization.
**Reason code:** `UNKNOWN_AUTHORIZATION`.

- The stream has no authorization for `Auth-Z`, so there is no hold to capture.
- The authorization rule is where the "available balance stays ≥ 0" check happens. If a settlement
  without an authorization were accepted, the check could be bypassed simply by skipping the
  authorization.
- Card schemes do have "force posts" (captures with no authorization), but the rules define no
  such thing. Accepting E6 would mean inventing a policy.
- **Effect:** no funds move. Had it been accepted, every ACC-001 close from D4 onward would be
  180.00 lower.

### E10: ACC-002 credit of BHD 10.000 "as three equal instalments", booked D5, arriving after E9 (D6)

Refused for **two independent reasons**, and both are reported:

1. **`BOOKED_DAY_CLOSED`.** The stream is replayed in the given order. E9 is booked on D6, so
   when it arrived the engine closed D5. E10 then asks to *book* onto D5, a day whose fees and
   balances have already been decided and reported. Allowing it would change a closed day with no
   record of when that became known. This is different from E7, which is *booked* on the open day
   and only *value-dated* earlier. The ledger supports that on purpose.
2. **`UNEQUAL_INSTALMENTS`.** 10.000 / 3 = 3.3333… BHD. BHD is held at 3 decimal places, so three
   equal parts cannot exist (10 000 fils mod 3 = 1). The nearest split, 3.334 + 3.333 + 3.333,
   is not equal, and posting it would silently rewrite the instruction. Posting 3.333 three times
   would lose 0.001 BHD. The engine never rounds a posting; only interest is rounded.

**Effect:** ACC-002 has no activity. It closes at 0.000 every day, earns 0.000 interest, and gets
no capitalization entry. **What would make it acceptable:** a corrected instruction, booked on
the open day, either as one credit of 10.000 or as an explicitly unequal split.

---

## Refused claims

These were put forward as expected behaviour. Each is refused because it contradicts the rules;
the engine's behaviour and a test are cited.

### "E7 causes exactly one fee, on Day 2": wrong, it causes three

E7 (booked D5, value D2, −620.00) changes every close from D2 onward. Restating on what is known
at D5:

| Day | Before its fee | Negative? | After fee |
|---|---|---|---|
| D2 | 250.00 − 620.00 = −370.00 | yes → fee | −395.00 |
| D3 | −395.00 + 400.00 = 5.00 | no | 5.00 |
| D4 | 5.00 − 185.00 = −180.00 | yes → fee | −205.00 |
| D5 | −205.00 | yes → fee | −230.00 |

The rule assesses a fee on every day whose closing ledger balance is negative. D4 is negative
because of E7 even though D3 recovered, and D5 stays negative. That makes three fees, with value
days D2, D4 and D5. Test: `E7 causes three fees (value D2, D4, D5), not one`.

### "The Auth-A Day 4 settlement must be rejected": does not hold

E5 settles `Auth-A`, which exists and was **approved** on D2 (available after the hold was
250.00 − 200.00 = 50.00 ≥ 0). Settling for less than the hold (185.00 of 200.00) is normal, for
example a fuel pre-authorization or a partial shipment. The 15.00 difference is released. The
engine refuses a settlement only when its authorization is unknown (E6), not active, on another
account, or smaller than the settlement. None of those apply. Test: `Auth-A approved at 50.00
available, settles 185.00, 15.00 released`.

### "An approved Auth-B hold reduces the ledger balance": wrong on two counts

1. Holds never touch the ledger balance. They reduce **available** balance only (available =
   ledger − active holds). Hold life-cycle entries are memo entries that `balanceAsOf` ignores.
   The D2 close shows it: ledger 250.00 while 200.00 is held for Auth-A. Test: `a hold reduces
   available, not ledger`.
2. Auth-B was not approved. When E8 arrived (D5, after E7), the ledger was −205.00. Available
   after the hold would be −205.00 − 0.00 − 90.00 = **−295.00 < 0**, so it was **declined** and
   no hold exists. The brief's note that Auth-B is never settled is consistent with this.

### "After E9, all balances and fees return to their pre-E7 values": wrong

- The ledger is append-only. E9 does not remove E7. It appends a mirror entry (+620.00, value D2).
  Both stay in the journal.
- The three fees stay booked. They are **offset** by three `FEE_REFUND` entries (+25.00 each),
  booked and value-dated **D6** as the user decided. So the D2 to D5 closes do not return to
  their pre-E7 values of 250.00, 650.00, 465.00 and 465.00. On final knowledge they are **225.00,
  625.00, 415.00, 390.00**: each still carries the fees charged up to that day, and the refunds
  only land on D6. D6 is 465.00 before interest, and fees net to zero only from D6.
- Auth-B's decline stands. Authorization decisions are made once, in real time, on what was known
  then. Re-running a card decision after the fact is not something a ledger can do.
- What each day *reported at its own close* is unchanged (D2 250.00 … D5 −230.00). Those are
  records of what was known then.

Tests: `after E9 and refunds, final closes are …`, `E9 refunds all three fees …`, `Auth-B
declined … stays declined after E9`.

### "The E10 instalments are each BHD 3.334": wrong

3 × 3.334 = **10.002**, which is 0.002 BHD more than the instruction. No three equal 3-decimal
amounts sum to 10.000 (see E10 above).

### "A remainder left over from interest rounding is discarded": wrong

The rule requires the rounded daily accruals to **sum exactly** to the capitalized total. The
engine records each day's rounded accrual and capitalizes exactly their sum, so there is no
remainder to discard. Here the dailies are 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.19 = **0.96**,
and 0.96 is capitalized. The unrounded total is 0.948, which would round to 0.95 if rounded
once. Rounding once and discarding the difference is exactly the approach the rule forbids. An
invariant checks the equality on the real run. Tests: `interest accrues on final closes …` and
`capitalization is the sum of rounded dailies (33.75 × 6 days = 0.06, not 0.08)`.

---

## Looked wrong, deliberately accepted

| Event | Why it looks wrong | Why it is accepted |
|---|---|---|
| **E5** settles 185.00 against a 200.00 hold | Amount differs from the hold | Under-settlement is normal. The remainder is released. Over-settlement is refused (tested). |
| **E7** booked D5, value D2 | Back-dated three days into closed days | Value dating before booking is standard (late presentment, corrections). The fee rule is defined by value date, so it expects this case. It is *booked* on the open day. |
| **E9** booked D6, value D2 | Reverses a back-dated entry | This is the append-only way to undo E7. The engine checks that the target exists, is a credit or debit, is on the same account, has the same value day, and has not already been reversed. |
| **E8** Auth-B | "Never settled" suggests an approved hold | It is valid, just **declined**. A decline is a business outcome, not a rejection, so it is reported under authorization states, not errors. |
