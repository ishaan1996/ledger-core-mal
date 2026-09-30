package ledger.engine;

import ledger.domain.Account;
import ledger.domain.Day;
import ledger.journal.Entry;
import ledger.journal.EntryKind;
import ledger.journal.HoldBook;
import ledger.money.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks that must hold for any run of the engine. They are evaluated on the real replay (by
 * {@code Replay} and the tests), so the guarantees are proved on the actual journal, not asserted.
 */
public final class Invariants {

    public record Check(String name, boolean ok, String detail) {}

    private Invariants() {}

    public static List<Check> verify(LedgerEngine engine) {
        List<Check> checks = new ArrayList<>();
        List<Entry> journal = engine.journal().snapshot();

        checks.add(appendOnly(engine, journal));
        checks.add(contiguousSequences(journal));
        for (Account account : engine.accounts()) {
            checks.add(openingPlusEntriesIsFinal(engine, account, journal));
            checks.add(accrualsSumToCapitalization(engine, account, journal));
            checks.add(atMostOneFeePerDay(account, journal));
            checks.add(feesMatchRuleOnFinalKnowledge(engine, account, journal));
            checks.add(scaleEverywhere(engine, account, journal));
        }
        checks.add(rejectedAndDeclinedPostNothing(engine, journal));
        checks.add(holdsBalance(engine));
        return checks;
    }

    public static boolean allOk(List<Check> checks) {
        return checks.stream().allMatch(Check::ok);
    }

    /** Every earlier journal snapshot is an unchanged prefix of the final journal. */
    private static Check appendOnly(LedgerEngine engine, List<Entry> journal) {
        int previous = 0;
        for (int i = 0; i < engine.snapshots().size(); i++) {
            List<Entry> snap = engine.snapshots().get(i);
            if (snap.size() < previous || snap.size() > journal.size() || !journal.subList(0, snap.size()).equals(snap)) {
                return new Check("journal only grows; no entry changed", false, "snapshot at close D" + (i + 1) + " is not a prefix");
            }
            previous = snap.size();
        }
        return new Check("journal only grows; no entry changed", true,
                engine.snapshots().size() + " close snapshots are unchanged prefixes of the final " + journal.size() + " entries");
    }

    private static Check contiguousSequences(List<Entry> journal) {
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i).sequence() != i + 1) {
                return new Check("entry sequences are 1..n with no gaps", false, "position " + (i + 1) + " has " + journal.get(i).sequence());
            }
        }
        return new Check("entry sequences are 1..n with no gaps", true, "1.." + journal.size());
    }

    private static Check openingPlusEntriesIsFinal(LedgerEngine engine, Account account, List<Entry> journal) {
        Money sum = account.openingBalance();
        for (Entry e : journal) {
            if (e.kind().affectsLedger() && e.account().equals(account.id())) {
                sum = sum.plus(e.amount());
            }
        }
        Money fin = engine.journal().balanceAsOf(account, Day.last());
        return new Check(account.id() + ": opening + all entries = final balance", sum.equals(fin),
                "%s + entries = %s; final = %s".formatted(account.openingBalance(), sum, fin));
    }

    private static Check accrualsSumToCapitalization(LedgerEngine engine, Account account, List<Entry> journal) {
        Money accrued = Money.zero(account.currency());
        for (LedgerEngine.Accrual a : engine.accruals()) {
            if (a.account().equals(account.id())) {
                accrued = accrued.plus(a.amount());
            }
        }
        Money capitalized = Money.zero(account.currency());
        for (Entry e : journal) {
            if (e.kind() == EntryKind.INTEREST_CAPITALIZATION && e.account().equals(account.id())) {
                capitalized = capitalized.plus(e.amount());
            }
        }
        return new Check(account.id() + ": rounded daily accruals sum exactly to capitalized total",
                accrued.equals(capitalized), "accruals %s, capitalized %s".formatted(accrued, capitalized));
    }

    /** At most one un-refunded fee per value day, and every refund points at a distinct fee of the same size. */
    private static Check atMostOneFeePerDay(Account account, List<Entry> journal) {
        Map<String, Entry> feesBySeq = new HashMap<>();
        for (Entry e : journal) {
            if (e.kind() == EntryKind.OVERDRAFT_FEE && e.account().equals(account.id())) {
                feesBySeq.put(Long.toString(e.sequence()), e);
            }
        }
        Set<String> refunded = new TreeSet<>();
        for (Entry e : journal) {
            if (e.kind() == EntryKind.FEE_REFUND && e.account().equals(account.id())) {
                Entry fee = feesBySeq.get(e.reference());
                if (fee == null || !refunded.add(e.reference()) || !fee.amount().negate().equals(e.amount())) {
                    return new Check(account.id() + ": at most one fee per day", false, "bad refund #" + e.sequence());
                }
            }
        }
        Map<Day, Integer> activePerDay = new HashMap<>();
        for (Entry fee : feesBySeq.values()) {
            if (!refunded.contains(Long.toString(fee.sequence()))) {
                activePerDay.merge(fee.valueDay(), 1, Integer::sum);
            }
        }
        boolean ok = activePerDay.values().stream().allMatch(n -> n <= 1);
        return new Check(account.id() + ": at most one fee per day", ok,
                "%d fee(s) assessed, %d refunded".formatted(feesBySeq.size(), refunded.size()));
    }

    /** After the window, the fees still standing are exactly the ones the rule asks for on final knowledge. */
    private static Check feesMatchRuleOnFinalKnowledge(LedgerEngine engine, Account account, List<Entry> journal) {
        Set<Day> required = engine.feePolicy().daysRequiringFee(account, engine.journal(), Day.last());
        if (engine.feePolicy().feeFor(account.currency()).isEmpty()) {
            return new Check(account.id() + ": standing fees match the rule", true, "no fee defined in " + account.currency());
        }
        Set<String> refunded = new TreeSet<>();
        for (Entry e : journal) {
            if (e.kind() == EntryKind.FEE_REFUND && e.account().equals(account.id())) {
                refunded.add(e.reference());
            }
        }
        Set<Day> standing = new TreeSet<>();
        for (Entry e : journal) {
            if (e.kind() == EntryKind.OVERDRAFT_FEE && e.account().equals(account.id())
                    && !refunded.contains(Long.toString(e.sequence()))) {
                standing.add(e.valueDay());
            }
        }
        return new Check(account.id() + ": standing fees match the rule", standing.equals(required),
                "standing " + standing + ", required " + required);
    }

    private static Check scaleEverywhere(LedgerEngine engine, Account account, List<Entry> journal) {
        List<Money> amounts = new ArrayList<>();
        amounts.add(account.openingBalance());
        journal.stream().filter(e -> e.account().equals(account.id())).map(Entry::amount).forEach(amounts::add);
        engine.accruals().stream().filter(a -> a.account().equals(account.id()))
                .forEach(a -> { amounts.add(a.amount()); amounts.add(a.closingBalance()); });
        for (Money m : amounts) {
            if (m.currency() != account.currency() || m.amount().scale() != account.currency().scale()) {
                return new Check(account.id() + ": every amount in " + account.currency() + " at scale "
                        + account.currency().scale(), false, "found " + m.currency() + " " + m);
            }
        }
        return new Check(account.id() + ": every amount in " + account.currency() + " at scale " + account.currency().scale(),
                true, amounts.size() + " amounts checked");
    }

    private static Check rejectedAndDeclinedPostNothing(LedgerEngine engine, List<Entry> journal) {
        for (EventOutcome o : engine.outcomes()) {
            boolean refused = o.status() == EventOutcome.Status.REJECTED || o.status() == EventOutcome.Status.DECLINED;
            if (refused && journal.stream().anyMatch(e -> e.cause().equals(o.event().id()))) {
                return new Check("rejected and declined events post nothing", false, o.event().id() + " has entries");
            }
        }
        return new Check("rejected and declined events post nothing", true, "");
    }

    private static Check holdsBalance(LedgerEngine engine) {
        for (HoldBook.Hold h : engine.holds().holdsAsOf(Day.last())) {
            if (h.state() != HoldBook.State.ACTIVE && !h.settled().plus(h.released()).equals(h.amount())) {
                return new Check("every closed hold is fully settled or released", false, h.authId());
            }
        }
        return new Check("every closed hold is fully settled or released", true, "");
    }
}
