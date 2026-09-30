# KNOWN_GAPS.md

This file lists tests that **fail against the design as built**. They are kept on purpose,
because each one shows a property the design does not have. They run separately from the main
suite, so `bash test.sh` stays green:

```
bash known-gaps.sh   # exits 1 while any gap is open; last output in out/known-gaps.txt
```

## GAP-1: A fully reversed debit still costs the customer 0.07 of interest

**Test:** `src/gaps/java/ledger/KnownGaps.java`, annotated inline.

**Claim tested:** E9 reverses E7 in full and every fee E7 caused is refunded, so ACC-001 should end
the window where it would be if E7 and E9 had never happened.

**Result:** `expected <466.03> but was <465.96>`.

| | D1 | D2 | D3 | D4 | D5 | D6 | Interest | Final |
|---|---|---|---|---|---|---|---|---|
| Without E7/E9 | 250.00 | 250.00 | 650.00 | 465.00 | 465.00 | 465.00 | 1.03 | 466.03 |
| As built | 250.00 | 225.00 | 625.00 | 415.00 | 390.00 | 465.00 | 0.96 | 465.96 |

The principal is made whole: both lines are 465.00 before interest. The whole 0.07 difference is
interest.

**What it reveals.** Three decisions that are each defensible combine to under-pay:
- Fee entries are append-only facts, value-dated to the day they were charged for (D2, D4, D5).
- Refunds are value-dated to the day they happen, D6 (AMBIGUITIES A3, the user's decision).
- Interest is derived from the final value-dated closes (A9).

As a result, on final knowledge D2 to D5 still carry fees for days that were never negative.
Their pre-fee closes are 250.00, 650.00, 465.00 and 465.00. Those fees depress four days of
interest base on money that was later refunded.

**Options to close it (each is a design change, not a bug fix):**
1. Value-date each refund to its fee's own day. This reverses A3, and closed days' final balances
   move again.
2. Accrue interest on a base that excludes refunded fees. The report would then have to explain
   why the interest base differs from the closing balance.
3. Keep A3 and book a separate `INTEREST_ADJUSTMENT` credit (+0.07) alongside the refunds on D6.
   The "rounded dailies sum exactly to capitalization" invariant would have to include adjustments.

**Status: accepted as a known gap (user decision, 20:04:51 UTC).** Ishaan chose to leave it
unfixed and documented. None of the three options is implemented. `known-gaps.sh` is expected to
keep failing with 466.03 vs 465.96, and would start passing only if one of the options above were
adopted. Anyone reading the replay should know that ACC-001's final 465.96 includes this 0.07
shortfall.
