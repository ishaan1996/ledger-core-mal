package ledger;

import ledger.domain.Account;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.engine.LedgerEngine;
import ledger.money.Money;
import ledger.replay.Scenario;

import java.util.List;

import static ledger.Harness.eq;
import static ledger.replay.Scenario.ACC_001;

/**
 * Tests that FAIL against the design as built, kept on purpose. Run with {@code bash known-gaps.sh};
 * they are not part of {@code test.sh}. See KNOWN_GAPS.md.
 */
public final class KnownGaps {

    public static void main(String[] args) {
        Harness h = new Harness();

        h.test("GAP-1: a fully reversed debit should leave the customer exactly where they would be without it", () -> {
            // The claim under test: E9 reverses E7 in full, and every fee E7 caused is refunded.
            // A customer who is "made whole" should end the window with the same balance as a
            // customer for whom E7 and E9 never happened.
            LedgerEngine actual = Scenario.replay();

            // Counterfactual: the same stream without E7 and E9.
            // (Auth-B would now be approved: 465.00 - 90.00 = 375.00 >= 0. It is never settled,
            // and holds do not move the ledger, so this does not change any balance.)
            LedgerEngine without = new LedgerEngine();
            Scenario.accounts().forEach(without::open);
            List<Event> events = Scenario.events().stream()
                    .filter(e -> !e.id().equals("E7") && !e.id().equals("E9")).toList();
            events.forEach(without::submit);
            without.finish();

            Account acc = actual.account(ACC_001);
            Money made = actual.journal().balanceAsOf(acc, Day.last());   // 465.96
            Money whole = without.journal().balanceAsOf(acc, Day.last());  // 466.03

            // Principal is made whole: both end at 465.00 before interest. Both the 620.00 and
            // the 3 x 25.00 fees come back. The difference is all interest:
            //
            //   without E7: closes 250, 250, 650, 465, 465, 465 -> 0.10+0.10+0.26+0.19+0.19+0.19 = 1.03
            //   as built:   closes 250, 225, 625, 415, 390, 465 -> 0.10+0.09+0.25+0.17+0.16+0.19 = 0.96
            //
            // WHAT IT REVEALS: fee entries are append-only facts value-dated to the day they were
            // charged for (D2, D4, D5). The refunds are value-dated to the day they happen (D6,
            // AMBIGUITIES A3). Interest is derived from the final value-dated closes (A9). So on
            // final knowledge, D2..D5 still carry fees for days that were never actually negative
            // (their pre-fee closes are 250, 650, 465, 465). Those phantom fees depress the
            // interest base for four days, and the customer loses 0.07 of interest on money that
            // was refunded. Each decision is defensible alone; together they under-pay.
            //
            // WHAT WOULD MAKE IT PASS (each is a design change, not a bug fix):
            //   (a) value-date each refund to its fee's own day. That reverses the user's decision
            //       A3, and closed days' final balances would move again;
            //   (b) derive interest from closes that exclude refunded fees (a "net of
            //       corrections" base). Closes and the interest base would then differ, and that
            //       must be explained in the report;
            //   (c) keep A3 and book a separate INTEREST_ADJUSTMENT credit (+0.07) with the refunds
            //       on D6. The rounded dailies would then no longer equal the capitalized amount on
            //       their own, so the "sum exactly" invariant would need to include adjustments.
            eq(whole, made, "ACC-001 final balance after full reversal vs never-happened");
        });

        h.run();
    }

    private KnownGaps() {}
}
