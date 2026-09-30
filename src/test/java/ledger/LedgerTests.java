package ledger;

import ledger.domain.Account;
import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.engine.EventOutcome;
import ledger.engine.Invariants;
import ledger.engine.LedgerEngine;
import ledger.journal.Entry;
import ledger.journal.EntryKind;
import ledger.journal.HoldBook;
import ledger.money.Currency;
import ledger.money.Money;
import ledger.replay.Scenario;
import ledger.rules.InterestPolicy;
import ledger.rules.Reason;
import ledger.rules.Rejection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ledger.Harness.check;
import static ledger.Harness.eq;
import static ledger.Harness.throwsIae;
import static ledger.money.Currency.AED;
import static ledger.money.Currency.BHD;
import static ledger.replay.Scenario.ACC_001;
import static ledger.replay.Scenario.ACC_002;

/** The suite: {@code bash test.sh}. Scenario tests first (the assignment's replay), then unit tests of each rule. */
public final class LedgerTests {

    private static final LedgerEngine REPLAY = Scenario.replay();
    private static final AccountId A = AccountId.of("A");
    private static final AccountId B = AccountId.of("B");

    public static void main(String[] args) {
        Harness h = new Harness();
        scenario(h);
        units(h);
        h.run();
    }

    // ================================================================ the assignment's replay

    private static void scenario(Harness h) {
        h.test("replay: ACC-001 closing ledger balance as known at each day's close", () -> {
            String[] expected = {"250.00", "250.00", "650.00", "465.00", "-230.00", "465.96"};
            for (Day d : Day.all()) {
                eq(aed(expected[d.number() - 1]), REPLAY.close(d).account(ACC_001).closing(), "close " + d);
            }
            eq(aed("465.00"), REPLAY.close(Day.last()).account(ACC_001).closingBeforeInterest(), "D6 before interest");
        });

        h.test("replay: E7 restates D2..D5 as seen at the D5 close (D2 -370.00 pre-fee, -395.00 after)", () -> {
            var view = REPLAY.close(d(5)).account(ACC_001);
            eq(aed("250.00"), view.asOf(d(1)), "D1");
            eq(aed("-395.00"), view.asOf(d(2)), "D2 after fee");
            eq(aed("-370.00"), view.asOf(d(2)).plus(aed("25.00")), "D2 before fee");
            eq(aed("5.00"), view.asOf(d(3)), "D3");
            eq(aed("-205.00"), view.asOf(d(4)), "D4 after fee");
            eq(aed("-230.00"), view.asOf(d(5)), "D5 after fee");
        });

        h.test("replay: E7 causes three fees (value D2, D4, D5), not one, all booked on D5", () -> {
            List<Entry> fees = ofKind(REPLAY, EntryKind.OVERDRAFT_FEE);
            eq(List.of(d(2), d(4), d(5)), fees.stream().map(Entry::valueDay).toList(), "fee value days");
            check(fees.stream().allMatch(f -> f.bookedDay().equals(d(5)) && f.amount().equals(aed("-25.00"))),
                    "each fee is -25.00 booked D5");
        });

        h.test("replay: E9 refunds all three fees with entries booked and value-dated D6; fees net to zero", () -> {
            List<Entry> fees = ofKind(REPLAY, EntryKind.OVERDRAFT_FEE);
            List<Entry> refunds = ofKind(REPLAY, EntryKind.FEE_REFUND);
            eq(3, refunds.size(), "refund count");
            for (int i = 0; i < 3; i++) {
                Entry r = refunds.get(i);
                eq(d(6), r.bookedDay(), "refund booked");
                eq(d(6), r.valueDay(), "refund value day");
                eq(aed("25.00"), r.amount(), "refund amount");
                eq(Long.toString(fees.get(i).sequence()), r.reference(), "refund points at fee");
            }
            Money net = Money.zero(AED);
            for (Entry e : REPLAY.journal().entries()) {
                if (e.kind().isFeeOrRefund()) {
                    net = net.plus(e.amount());
                }
            }
            eq(Money.zero(AED), net, "net fees at end of D6");
        });

        h.test("replay: after E9 and refunds, final closes are D2 225, D3 625, D4 415, D5 390, D6 465 (+0.96)", () -> {
            Account acc = REPLAY.account(ACC_001);
            String[] expected = {"250.00", "225.00", "625.00", "415.00", "390.00", "465.96"};
            for (Day d : Day.all()) {
                eq(aed(expected[d.number() - 1]), REPLAY.journal().balanceAsOf(acc, d), "final " + d);
            }
        });

        h.test("replay: D2..D5 as reported at their own closes are unchanged records after E9", () -> {
            eq(aed("250.00"), REPLAY.close(d(2)).account(ACC_001).closing(), "D2 own close");
            eq(aed("-230.00"), REPLAY.close(d(5)).account(ACC_001).closing(), "D5 own close");
        });

        h.test("replay: interest accrues on final closes, rounded per day, and sums exactly to the capitalization", () -> {
            List<LedgerEngine.Accrual> acc = REPLAY.accruals().stream().filter(a -> a.account().equals(ACC_001)).toList();
            eq(List.of(aed("0.10"), aed("0.09"), aed("0.25"), aed("0.17"), aed("0.16"), aed("0.19")),
                    acc.stream().map(LedgerEngine.Accrual::amount).toList(), "daily accruals");
            List<Entry> caps = ofKind(REPLAY, EntryKind.INTEREST_CAPITALIZATION);
            eq(1, caps.size(), "one capitalization entry");
            eq(aed("0.96"), caps.get(0).amount(), "capitalized");
            eq(d(6), caps.get(0).valueDay(), "capitalization value day");
            eq(aed("465.00"), acc.get(5).closingBalance(), "D6 accrual base excludes the capitalization itself");
        });

        h.test("replay: event outcomes", () -> {
            String[] expected = {"POSTED", "POSTED", "APPROVED", "POSTED", "POSTED", "REJECTED",
                    "POSTED", "DECLINED", "POSTED", "REJECTED"};
            for (int i = 0; i < expected.length; i++) {
                eq(expected[i], REPLAY.outcome("E" + (i + 1)).status().name(), "E" + (i + 1));
            }
        });

        h.test("replay: E6 rejected for unknown authorization and moves no funds", () -> {
            EventOutcome o = REPLAY.outcome("E6");
            eq(List.of(Reason.UNKNOWN_AUTHORIZATION), reasons(o), "reasons");
            check(REPLAY.journal().entries().stream().noneMatch(e -> e.cause().equals("E6")), "no entries");
        });

        h.test("replay: E10 rejected for two separate reasons (closed day, unequal 3-decimal split)", () -> {
            EventOutcome o = REPLAY.outcome("E10");
            eq(Set.of(Reason.BOOKED_DAY_CLOSED, Reason.UNEQUAL_INSTALMENTS), Set.copyOf(reasons(o)), "reasons");
            eq(d(6), o.processedOn(), "arrives while D6 is open");
            check(REPLAY.journal().forAccount(ACC_002).isEmpty(), "ACC-002 has no entries");
        });

        h.test("replay: Auth-A approved at 50.00 available, settles 185.00, 15.00 released", () -> {
            check(REPLAY.outcome("E3").note().contains("= 50.00 >= 0"), REPLAY.outcome("E3").note());
            eq(aed("200.00"), REPLAY.holds().activeHolds(REPLAY.account(ACC_001), d(3)), "active hold D3");
            HoldBook.Hold hold = REPLAY.holds().find("Auth-A", d(4)).orElseThrow();
            eq(HoldBook.State.SETTLED, hold.state(), "state");
            eq(aed("185.00"), hold.settled(), "settled");
            eq(aed("15.00"), hold.released(), "released");
        });

        h.test("replay: a hold reduces available, not ledger (D2 ledger stays 250.00 with 200.00 held)", () ->
                eq(aed("250.00"), REPLAY.close(d(2)).account(ACC_001).closing(), "D2 ledger"));

        h.test("replay: Auth-B declined at -295.00 available, places no hold, and stays declined after E9", () -> {
            EventOutcome o = REPLAY.outcome("E8");
            check(o.note().contains("= -295.00 < 0"), o.note());
            check(REPLAY.holds().find("Auth-B", d(6)).isEmpty(), "no hold");
            eq(EventOutcome.Status.DECLINED, o.status(), "still declined at end");
        });

        h.test("replay: E7 is still in the journal, unchanged, after its reversal", () -> {
            Entry e7 = REPLAY.journal().entries().stream().filter(e -> e.cause().equals("E7") && e.kind() == EntryKind.DEBIT)
                    .findFirst().orElseThrow();
            eq(aed("-620.00"), e7.amount(), "amount");
            eq(d(2), e7.valueDay(), "value day");
            Entry rev = ofKind(REPLAY, EntryKind.REVERSAL).get(0);
            eq(aed("620.00"), rev.amount(), "mirror");
            eq(d(2), rev.valueDay(), "mirror value day");
        });

        h.test("replay: ACC-002 stays 0.000 every day and earns nothing", () -> {
            for (Day d : Day.all()) {
                eq(bhd("0.000"), REPLAY.close(d).account(ACC_002).closing(), "close " + d);
            }
            check(REPLAY.accruals().stream().filter(a -> a.account().equals(ACC_002)).allMatch(a -> a.amount().isZero()),
                    "zero accruals");
        });

        h.test("replay: every invariant holds", () -> {
            for (Invariants.Check c : Invariants.verify(REPLAY)) {
                check(c.ok(), c.name() + " " + c.detail());
            }
        });
    }

    // ================================================================ rules in isolation

    private static void units(Harness h) {
        h.test("money: AED is held at 2 places and BHD at 3", () -> {
            eq(2, aed("1").amount().scale(), "AED scale");
            eq(3, bhd("1").amount().scale(), "BHD scale");
        });

        h.test("money: an amount finer than its currency is refused, never rounded", () -> {
            throwsIae(() -> aed("1.001"), "AED 1.001");
            throwsIae(() -> bhd("3.3335"), "BHD 3.3335");
            throwsIae(() -> new Money(AED, new BigDecimal("1.0")), "wrong scale");
        });

        h.test("money: mixing currencies throws", () -> throwsIae(() -> aed("1.00").plus(bhd("1.000")), "AED + BHD"));

        h.test("interest: HALF_EVEN at ties (12.50 -> 0.00, 37.50 -> 0.02); zero and negative earn nothing", () -> {
            InterestPolicy p = new InterestPolicy();
            eq(aed("0.00"), p.dailyAccrual(aed("12.50")), "0.005");
            eq(aed("0.02"), p.dailyAccrual(aed("37.50")), "0.015");
            eq(aed("0.00"), p.dailyAccrual(aed("0.00")), "zero");
            eq(aed("0.00"), p.dailyAccrual(aed("-500.00")), "negative");
        });

        h.test("interest: capitalization is the sum of rounded dailies (33.75 x 6 days = 0.06, not 0.08)", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "33.75"));
            e.finish();
            eq(aed("0.06"), ofKind(e, EntryKind.INTEREST_CAPITALIZATION).get(0).amount(), "capitalized");
        });

        h.test("fees: one per account per day even when two back-dated debits land on the same day", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "100.00"));
            e.submit(new Event.Debit("X2", A, d(3), d(1), aed("150.00")));
            e.submit(new Event.Debit("X3", A, d(3), d(1), aed("10.00")));
            e.finish();
            var byDay = ofKind(e, EntryKind.OVERDRAFT_FEE).stream()
                    .collect(Collectors.groupingBy(Entry::valueDay, Collectors.counting()));
            check(byDay.values().stream().allMatch(n -> n == 1), "fees per day " + byDay);
            check(byDay.containsKey(d(1)), "D1 fee assessed");
        });

        h.test("fees: a closing balance of exactly 0.00 is not negative and draws no fee", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "100.00"));
            e.submit(new Event.Debit("X2", A, d(1), d(1), aed("100.00")));
            e.finish();
            check(ofKind(e, EntryKind.OVERDRAFT_FEE).isEmpty(), "no fee");
        });

        h.test("fees: a reversal that leaves the day negative refunds nothing", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "100.00"));
            e.submit(new Event.Debit("X2", A, d(1), d(1), aed("10.00")));
            e.submit(new Event.Debit("X3", A, d(2), d(2), aed("150.00")));
            e.submit(new Event.Reversal("X4", A, d(3), d(1), "X2"));
            e.finish();
            check(ofKind(e, EntryKind.OVERDRAFT_FEE).stream().anyMatch(f -> f.valueDay().equals(d(2))), "D2 fee");
            check(ofKind(e, EntryKind.FEE_REFUND).isEmpty(), "no refund: D2 is still -50.00 before its fee");
        });

        h.test("fees: a BHD account closing negative is reported as an error; no FX rate is invented", () -> {
            LedgerEngine e = engine();
            e.submit(new Event.Debit("X1", B, d(1), d(1), bhd("1.000")));
            e.finish();
            check(ofKind(e, EntryKind.OVERDRAFT_FEE).isEmpty(), "no fee");
            check(!e.errors().isEmpty() && e.errors().get(0).message().contains("no overdraft fee is defined in BHD"),
                    "error " + e.errors());
        });

        h.test("authorization: available exactly 0.00 after the hold is approved; 0.01 short is declined", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "100.00"));
            eq(EventOutcome.Status.APPROVED, e.submit(auth("X2", "H1", "100.00")).status(), "exact");
            eq(EventOutcome.Status.DECLINED, e.submit(auth("X3", "H2", "0.01")).status(), "0.01 short");
        });

        h.test("settlement: above the hold is refused; a second settlement of the same hold is refused", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "500.00"));
            e.submit(auth("X2", "H1", "100.00"));
            eq(List.of(Reason.SETTLEMENT_EXCEEDS_HOLD), reasons(e.submit(settle("X3", "H1", "100.01"))), "over");
            eq(EventOutcome.Status.POSTED, e.submit(settle("X4", "H1", "100.00")).status(), "exact");
            eq(List.of(Reason.AUTHORIZATION_NOT_ACTIVE), reasons(e.submit(settle("X5", "H1", "1.00"))), "again");
        });

        h.test("reversal: unknown target, settlement target, wrong value day and double reversal are refused", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "500.00"));
            e.submit(auth("X2", "H1", "100.00"));
            e.submit(settle("X3", "H1", "100.00"));
            eq(List.of(Reason.UNKNOWN_REVERSAL_TARGET), reasons(e.submit(new Event.Reversal("R1", A, d(1), d(1), "NOPE"))), "unknown");
            eq(List.of(Reason.REVERSAL_TARGET_NOT_REVERSIBLE), reasons(e.submit(new Event.Reversal("R2", A, d(1), d(1), "X3"))), "settlement");
            e.submit(new Event.Debit("X4", A, d(2), d(1), aed("10.00")));
            eq(List.of(Reason.REVERSAL_VALUE_DAY_MISMATCH), reasons(e.submit(new Event.Reversal("R3", A, d(2), d(2), "X4"))), "value day");
            eq(EventOutcome.Status.POSTED, e.submit(new Event.Reversal("R4", A, d(2), d(1), "X4")).status(), "ok");
            eq(List.of(Reason.ALREADY_REVERSED), reasons(e.submit(new Event.Reversal("R5", A, d(2), d(1), "X4"))), "twice");
        });

        h.test("validation: forward-dated, wrong currency, non-positive and duplicate id are refused", () -> {
            LedgerEngine e = engine();
            eq(List.of(Reason.VALUE_DAY_AFTER_BOOKED_DAY), reasons(e.submit(credit("X1", A, 1, 2, "1.00"))), "forward");
            eq(List.of(Reason.CURRENCY_MISMATCH), reasons(e.submit(new Event.Credit("X2", A, d(1), d(1), bhd("1.000")))), "currency");
            eq(List.of(Reason.NON_POSITIVE_AMOUNT), reasons(e.submit(credit("X3", A, 1, 1, "0.00"))), "zero");
            e.submit(credit("X4", A, 1, 1, "1.00"));
            eq(List.of(Reason.DUPLICATE_EVENT_ID), reasons(e.submit(credit("X4", A, 1, 1, "1.00"))), "duplicate");
        });

        h.test("instalments that divide exactly are posted as equal parts (BHD 9.000 / 3 = 3.000)", () -> {
            LedgerEngine e = engine();
            e.submit(new Event.Credit("X1", B, d(1), d(1), bhd("9.000"), 3));
            List<Entry> parts = e.journal().forAccount(B);
            eq(3, parts.size(), "parts");
            check(parts.stream().allMatch(p -> p.amount().equals(bhd("3.000"))), "each 3.000");
        });

        h.test("time: an event after the window closes is refused; days close only in order", () -> {
            LedgerEngine e = engine();
            try {
                e.closeDay(d(2));
                throw new AssertionError("closing D2 before D1 should fail");
            } catch (IllegalStateException expected) {
                // ok
            }
            e.finish();
            eq(List.of(Reason.WINDOW_CLOSED), reasons(e.submit(credit("X1", A, 6, 6, "1.00"))), "after window");
        });

        h.test("journal: the public view cannot be modified", () -> {
            LedgerEngine e = engine();
            e.submit(credit("X1", A, 1, 1, "1.00"));
            try {
                e.journal().entries().clear();
                throw new AssertionError("journal view was modifiable");
            } catch (UnsupportedOperationException expected) {
                eq(1, e.journal().size(), "still one entry");
            }
        });
    }

    // ================================================================ helpers

    private static LedgerEngine engine() {
        LedgerEngine e = new LedgerEngine();
        e.open(new Account(A, AED, Money.zero(AED)));
        e.open(new Account(B, BHD, Money.zero(BHD)));
        return e;
    }

    private static Event.Credit credit(String id, AccountId acc, int booked, int value, String amount) {
        return new Event.Credit(id, acc, d(booked), d(value), aed(amount));
    }

    private static Event.Authorization auth(String id, String authId, String amount) {
        return new Event.Authorization(id, A, d(1), d(1), authId, aed(amount));
    }

    private static Event.Settlement settle(String id, String authId, String amount) {
        return new Event.Settlement(id, A, d(1), d(1), authId, aed(amount));
    }

    private static List<Reason> reasons(EventOutcome o) {
        return o.rejections().stream().map(Rejection::reason).toList();
    }

    private static List<Entry> ofKind(LedgerEngine e, EntryKind kind) {
        return e.journal().entries().stream().filter(x -> x.kind() == kind).toList();
    }

    private static Day d(int n) {
        return Day.of(n);
    }

    private static Money aed(String v) {
        return Money.of(Currency.AED, v);
    }

    private static Money bhd(String v) {
        return Money.of(Currency.BHD, v);
    }
}
