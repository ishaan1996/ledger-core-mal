# NUMBERS.md

This file covers every constant in the code: why it has the value it has, and why it isn't half
that value. Nothing else in `src/main` changes an output.

## Rule constants

| Constant | Value | Where | Why this value | Why not half |
|---|---|---|---|---|
| Overdraft fee | **AED 25.00** | `OverdraftFeePolicy.standard()` | Given by the brief. Stored as exact `Money`, never a float. | It is part of the rule we implement, not a parameter to tune. At 12.50 every fee and refund line would halve, and so would the fee-reduced D2 to D5 bases that interest accrues on, which changes the interest. |
| Fee currency coverage | **AED only** | same map | The fee is quoted only in AED, and no FX rate is given (AMBIGUITIES A30). | "Half" doesn't apply. The plausible alternative, BHD 25.000, is about 10× the AED fee at roughly 9.7 AED per BHD, which is why no BHD fee is invented. |
| Standing fees per account per day | **1** | `reconcileFees`, checked by `Invariants` | "Once per day per account." | It's a count, so there's no half. 0 turns the rule off, and 2 would double-charge a day that receives two back-dated debits (tested). |
| Fee trigger | balance **< 0** (strict) | `daysRequiringFee` | "Is negative," and zero is not negative. | Using ≤ 0 would charge an account sitting at exactly 0.00 (tested: no fee). |
| Daily interest rate | **0.0004** | `InterestPolicy.DAILY_RATE` | 0.04 % = 0.04 / 100, applied per day as stated. No /365. That is 14.6 % a year simple: high, but as given. | 0.0002 would come from misreading 0.04 % as 0.02 %, which isn't plausible. The plausible misreadings are 100× too big (0.04 read as 4 %) and 365× too small (read as annual), and the words "per day" rule out both. |
| Interest trigger | balance **> 0** | `InterestPolicy.dailyAccrual` | "Positive balances only." | Zero earns zero either way. Negative balances never pay interest (there's no debit interest in the brief). |
| Rounding mode | **HALF_EVEN** | `InterestPolicy.ROUNDING` | Over many accruals it has no directional bias. HALF_UP rounds every tie up and so overpays systematically. | No tie occurs in this replay (0.100, 0.090, 0.250, 0.166, 0.156, 0.186), so HALF_UP would print the same. The choice only shows at a tie such as 12.50 × 0.0004 = 0.005 → 0.00 (HALF_UP gives 0.01). A test pins it. |
| Rounding point | **once per day**, at the currency's scale | `Money.rounded` | The rule: rounded dailies must sum exactly to the capitalized total. | Rounding the total once gives 0.95 here, not 0.96. Rounding the intermediate to 3 places first ("half" the extra digits) would double-round: 0.0045 → 0.005 → 0.01 instead of 0.00. |
| Intermediate precision | **exact** `BigDecimal` product (scale 2 + 4 = 6 for AED) | `dailyAccrual` | 465.00 × 0.0004 = 0.186000 exactly. It is rounded once, at the end. | Any truncation before the single rounding step would change results at ties. |
| AED scale | **2** | `Currency.AED` | Given by the brief. ISO 4217 has 100 fils per dirham. | With 1 place, accruals such as 0.09 could not be held. |
| BHD scale | **3** | `Currency.BHD` | Given by the brief. ISO 4217 has 1000 fils per dinar. | With the usual 2, 0.001 BHD amounts could not exist. And 10.000 still would not split three ways, so E10 would be refused either way. |
| Window | **Day 1 to Day 6** | `Day.FIRST`, `Day.LAST` | Given by the brief. It also fixes when capitalization happens and which booked and value days are valid. | With 3 days, E7 to E10 would fall outside the window, and interest would capitalize on D3, not D6 as the brief says. |
| Capitalization | **once**, at the D6 close, value D6 | `accrueAndCapitalize` | "Accruals capitalize as one credit at end of Day 6." | Capitalizing mid-window (for example every 3 days) would compound interest, which the brief does not ask for. |
| Instalment count (E10) | **3** | `Scenario` | Transcribed from the brief. It is what makes E10 invalid (10 000 fils mod 3 = 1). | Half of 3 isn't a whole count. With 2 instalments (5.000 each) the split would be valid, and only the closed-day reason would reject E10. |

## Scenario amounts

The account openings (AED 0.00, BHD 0.000) and the ten event amounts are transcribed from the
brief in `Scenario.java`. They are inputs, not choices.

## Test boundary values (`LedgerTests.java`)

| Value | Why | Why not half |
|---|---|---|
| **0.01** AED short on an authorization | This is the smallest AED unit, so it is the tightest test of "≥ 0". | 0.005 AED cannot exist; `Money` refuses it. |
| **12.50**, **37.50** AED | × 0.0004 gives 0.005 and 0.015, exact ties that tell HALF_EVEN from HALF_UP. | 6.25 gives 0.0025, which is not a tie at 2 places. |
| **33.75** AED over 6 days | This is 0.0135 a day. The rounded dailies sum to 0.06, while rounding once gives 0.08. | 16.875 is not a valid AED amount. |
| **100.01** vs a **100.00** hold | One unit over the hold is the boundary for over-settlement. | Anything smaller than one unit can't be represented. |
| **BHD 9.000** in 3 | Divides exactly (3.000), which shows the instalment path works when valid. | 4.500 / 3 = 1.500 would also work; 9.000 simply reads more clearly. |
| **BHD 1.000** debit | Makes a BHD account negative, to show that no fee is invented. | Any positive amount works. |

## Outputs to cross-check

Every value below appears in `out/replay.txt`.

| Figure | Value | Derivation |
|---|---|---|
| Auth-A available after hold | 50.00 | 250.00 − 0.00 − 200.00 |
| Auth-B available after hold | −295.00 | −205.00 − 0.00 − 90.00 |
| D2 pre-fee close after E7 | −370.00 | 250.00 − 620.00 |
| D5 close as known on D5 | −230.00 | 1200 − 950 − 620 + 400 − 185 − 3 × 25 |
| Final closes D1..D6 | 250.00, 225.00, 625.00, 415.00, 390.00, 465.00 | after E9's +620.00 at value D2 and the three +25.00 refunds at value D6 |
| Accruals | 0.10, 0.09, 0.25, 0.17, 0.16, 0.19 | each close × 0.0004, HALF_EVEN |
| Capitalized | 0.96 | sum of the above |
| Final ACC-001 | 465.96 | 465.00 + 0.96 |
