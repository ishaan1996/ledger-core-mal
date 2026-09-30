package ledger.replay;

import ledger.domain.Account;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.engine.DayClose;
import ledger.engine.EventOutcome;
import ledger.engine.Invariants;
import ledger.engine.LedgerEngine;
import ledger.journal.Entry;
import ledger.journal.EntryKind;
import ledger.journal.HoldBook;
import ledger.money.Money;
import ledger.rules.InterestPolicy;
import ledger.rules.Rejection;

import java.io.PrintStream;
import java.util.List;

/** Prints the per-day report and the end-of-window summary. Reads the engine; never changes it. */
public final class ReportPrinter {

    private final LedgerEngine engine;
    private final PrintStream out;

    public ReportPrinter(LedgerEngine engine, PrintStream out) {
        this.engine = engine;
        this.out = out;
    }

    public void print() {
        out.println("LEDGER REPLAY  window D%d..D%d".formatted(Day.FIRST, Day.LAST));
        for (Account a : engine.accounts()) {
            out.println("  %s  %s  opening %s".formatted(a.id(), a.currency(), a.openingBalance()));
        }
        for (DayClose close : engine.closes()) {
            printDay(close);
        }
        printEndOfWindow();
    }

    // ---------------------------------------------------------------- per day

    private void printDay(DayClose close) {
        Day day = close.day();
        out.println();
        out.println("==================== Day " + day.number() + " ====================");

        section("Events processed");
        List<EventOutcome> today = engine.outcomes().stream().filter(o -> o.processedOn().equals(day)).toList();
        if (today.isEmpty()) {
            line("none");
        }
        for (EventOutcome o : today) {
            line("%-4s %-9s %s".formatted(o.event().id(), o.status(), describe(o.event())));
            if (o.note() != null) {
                line("               " + o.note());
            }
        }

        section("Closing ledger balance (value date <= " + day + ", as known at this close)");
        for (DayClose.AccountClose ac : close.accounts()) {
            Account a = engine.account(ac.account());
            if (day.isLast() && !ac.closing().equals(ac.closingBeforeInterest())) {
                line("%s  %s %12s   (%s before interest capitalization)".formatted(
                        a.id(), a.currency(), ac.closing(), ac.closingBeforeInterest()));
            } else {
                line("%s  %s %12s".formatted(a.id(), a.currency(), ac.closing()));
            }
        }

        section("Value-dated view at this close (* = differs from what that day's own close reported)");
        for (DayClose.AccountClose ac : close.accounts()) {
            StringBuilder sb = new StringBuilder(ac.account().toString());
            for (Day d : Day.through(day)) {
                Money now = ac.asOf(d);
                Money then = engine.close(d).account(ac.account()).closingBeforeInterest();
                boolean changed = d.isBefore(day) && !now.equals(then);
                sb.append("  %s %s%s".formatted(d, now, changed ? "*" : ""));
            }
            line(sb.toString());
        }

        section("Fee assessments booked today");
        boolean anyFee = false;
        for (Entry e : engine.journal().entries()) {
            if (!e.bookedDay().equals(day)) {
                continue;
            }
            if (e.kind() == EntryKind.OVERDRAFT_FEE) {
                anyFee = true;
                line("%s  OVERDRAFT_FEE %s for %s  (%s closed at %s before this fee, on what was known then; cause %s) #%d"
                        .formatted(e.account(), e.amount(), e.valueDay(), e.valueDay(), balanceBeforeFee(e), e.cause(), e.sequence()));
            } else if (e.kind() == EntryKind.FEE_REFUND) {
                anyFee = true;
                Entry fee = engine.journal().bySequence(Long.parseLong(e.reference())).orElseThrow();
                line("%s  FEE_REFUND    %s value %s  refunds the %s fee #%d, which no longer applies (cause %s) #%d"
                        .formatted(e.account(), "+" + e.amount(), e.valueDay(), fee.valueDay(), fee.sequence(), e.cause(), e.sequence()));
            }
        }
        if (!anyFee) {
            line("none");
        }

        section("Authorization states at end of day");
        boolean anyAuth = false;
        for (HoldBook.Hold h : engine.holds().holdsAsOf(day)) {
            anyAuth = true;
            String state = switch (h.state()) {
                case ACTIVE -> "ACTIVE (hold since %s)".formatted(h.placedOn());
                case SETTLED -> "SETTLED on %s: %s captured, %s released".formatted(h.closedOn(), h.settled(), h.released());
                case RELEASED -> "RELEASED on %s: %s released".formatted(h.closedOn(), h.released());
            };
            line("%-7s %s  hold %s  %s".formatted(h.authId(), h.account(), h.amount(), state));
        }
        for (EventOutcome o : engine.outcomes()) {
            if (o.status() == EventOutcome.Status.DECLINED && !o.processedOn().isAfter(day)
                    && o.event() instanceof Event.Authorization a) {
                anyAuth = true;
                line("%-7s %s  asked %s  DECLINED on %s, no hold".formatted(a.authId(), a.account(), a.amount(), o.processedOn()));
            }
        }
        if (!anyAuth) {
            line("none");
        }

        section("Errors");
        boolean anyError = false;
        for (EventOutcome o : today) {
            if (o.status() == EventOutcome.Status.REJECTED) {
                anyError = true;
                line("%s REJECTED, nothing posted: %s".formatted(o.event().id(), describe(o.event())));
                for (Rejection r : o.rejections()) {
                    line("     - " + r);
                }
            }
        }
        for (LedgerEngine.EngineError err : engine.errors()) {
            if (err.day().equals(day)) {
                anyError = true;
                line("%s: %s".formatted(err.account(), err.message()));
            }
        }
        if (!anyError) {
            line("none");
        }
    }

    /** The day's balance as known just before the fee was appended: the journal prefix makes this exact. */
    private Money balanceBeforeFee(Entry fee) {
        Account account = engine.account(fee.account());
        Money balance = account.openingBalance();
        for (Entry e : engine.journal().entries()) {
            if (e.sequence() >= fee.sequence()) {
                break;
            }
            if (e.kind().affectsLedger() && e.account().equals(fee.account()) && !e.valueDay().isAfter(fee.valueDay())) {
                balance = balance.plus(e.amount());
            }
        }
        return balance;
    }

    // ---------------------------------------------------------------- end of window

    private void printEndOfWindow() {
        out.println();
        out.println("==================== End of window ====================");

        section("Interest: one accrual per day, derived at the Day 6 close from the final value-dated closes");
        line("rate %s per day (0.04%%), positive balances only, each day rounded %s to the currency's scale"
                .formatted(InterestPolicy.DAILY_RATE.toPlainString(), InterestPolicy.ROUNDING));
        for (Account a : engine.accounts()) {
            Money total = Money.zero(a.currency());
            StringBuilder sb = new StringBuilder();
            for (LedgerEngine.Accrual acc : engine.accruals()) {
                if (acc.account().equals(a.id())) {
                    sb.append("  %s %s -> %s".formatted(acc.day(), acc.closingBalance(), acc.amount()));
                    total = total.plus(acc.amount());
                }
            }
            line(a.id() + sb.toString());
            Money capitalized = engine.journal().entries().stream()
                    .filter(e -> e.kind() == EntryKind.INTEREST_CAPITALIZATION && e.account().equals(a.id()))
                    .map(Entry::amount).reduce(Money.zero(a.currency()), Money::plus);
            line("%s  sum of rounded accruals %s = capitalized %s%s".formatted(a.id(), total, capitalized,
                    capitalized.isZero() ? " (nothing to capitalize, no entry posted)" : " (one credit, value D6)"));
        }

        section("Final value-dated closing balances (everything known at the end)");
        for (Account a : engine.accounts()) {
            StringBuilder sb = new StringBuilder(a.id().toString());
            for (Day d : Day.all()) {
                sb.append("  %s %s".formatted(d, engine.journal().balanceAsOf(a, d)));
            }
            line(sb + "   (D6 includes interest)");
        }

        section("Event outcomes");
        for (EventOutcome o : engine.outcomes()) {
            line("%-4s %-9s processed %s  %s".formatted(o.event().id(), o.status(), o.processedOn(), describe(o.event())));
        }

        section("Journal (append order; memo entries do not move the ledger balance)");
        for (Entry e : engine.journal().entries()) {
            line("#%-3d %-24s %s booked %s value %s %10s  cause %s%s".formatted(e.sequence(),
                    e.kind() + (e.kind().affectsLedger() ? "" : " (memo)"), e.account(), e.bookedDay(), e.valueDay(),
                    e.amount(), e.cause(), e.reference() == null ? "" : "  ref " + e.reference()));
        }

        section("Invariants");
        for (Invariants.Check c : Invariants.verify(engine)) {
            line("%s  %s%s".formatted(c.ok() ? "ok  " : "FAIL", c.name(), c.detail().isEmpty() ? "" : "  (" + c.detail() + ")"));
        }
    }

    // ---------------------------------------------------------------- helpers

    static String describe(Event event) {
        String days = "booked %s, value %s".formatted(event.bookedDay(), event.valueDay());
        return switch (event) {
            case Event.Credit c -> c.instalments() == 1
                    ? "CREDIT %s %s %s, %s".formatted(c.account(), c.amount(), c.amount().currency(), days)
                    : "CREDIT %s %s %s as %d equal instalments, %s".formatted(c.account(), c.amount(),
                    c.amount().currency(), c.instalments(), days);
            case Event.Debit d -> "DEBIT %s %s %s, %s".formatted(d.account(), d.amount(), d.amount().currency(), days);
            case Event.Authorization a -> "AUTHORIZATION %s %s hold %s %s, %s".formatted(a.authId(), a.account(),
                    a.amount(), a.amount().currency(), days);
            case Event.Settlement s -> "SETTLEMENT %s %s %s %s, %s".formatted(s.authId(), s.account(), s.amount(),
                    s.amount().currency(), days);
            case Event.Reversal r -> "REVERSAL of %s on %s, %s".formatted(r.reversedEventId(), r.account(), days);
        };
    }

    private void section(String title) {
        out.println("-- " + title);
    }

    private void line(String text) {
        out.println("   " + text);
    }
}
