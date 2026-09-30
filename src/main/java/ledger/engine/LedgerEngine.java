package ledger.engine;

import ledger.domain.Account;
import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.journal.Entry;
import ledger.journal.EntryKind;
import ledger.journal.HoldBook;
import ledger.journal.Journal;
import ledger.money.Money;
import ledger.rules.AuthorizationPolicy;
import ledger.rules.EventValidator;
import ledger.rules.InterestPolicy;
import ledger.rules.OverdraftFeePolicy;
import ledger.rules.Reason;
import ledger.rules.Rejection;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ledger core. Single-threaded and deterministic: the same events in the same order always
 * produce the same journal.
 *
 * <p>Time moves only forward. An event booked on a later day first closes every day before it.
 * An event booked on a day that has already closed is rejected. An event may be value-dated to
 * an earlier, closed day; when it posts, fees for the closed days are re-decided at once on the new
 * knowledge (<b>restate</b>). A fee that becomes due is booked today, value-dated to the day it is
 * for. A fee that is no longer justified is refunded by an offsetting entry booked <em>and
 * value-dated</em> today, so closed days keep the balances they were reported with. Nothing already
 * in the journal is ever changed.
 *
 * <p>At the Day 6 close, one interest accrual per account per day is derived from the final
 * value-dated closing balances, recorded, and their exact sum is capitalized as one credit.
 */
public final class LedgerEngine {

    /** A daily interest accrual. Recorded at the Day 6 close; not a journal entry. */
    public record Accrual(AccountId account, Day day, Money closingBalance, Money amount) {}

    /** A problem the engine hit that is not a rejected event, e.g. a fee rule it cannot apply. */
    public record EngineError(Day day, AccountId account, String message) {}

    private final Map<AccountId, Account> accounts = new LinkedHashMap<>();
    private final Journal journal = new Journal();
    private final HoldBook holds = new HoldBook(journal);
    private final EventValidator validator = new EventValidator(journal, holds);
    private final AuthorizationPolicy authorizationPolicy = new AuthorizationPolicy();
    private final OverdraftFeePolicy feePolicy;
    private final InterestPolicy interestPolicy = new InterestPolicy();

    private final List<EventOutcome> outcomes = new ArrayList<>();
    private final List<EngineError> errors = new ArrayList<>();
    private final List<DayClose> closes = new ArrayList<>();
    private final List<Accrual> accruals = new ArrayList<>();
    private final List<List<Entry>> snapshots = new ArrayList<>();
    private final Set<String> seenEventIds = new HashSet<>();
    private int closedThrough = Day.FIRST - 1;

    public LedgerEngine() {
        this(OverdraftFeePolicy.standard());
    }

    public LedgerEngine(OverdraftFeePolicy feePolicy) {
        this.feePolicy = feePolicy;
    }

    public void open(Account account) {
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new IllegalStateException("account already open: " + account.id());
        }
    }

    // ---------------------------------------------------------------- events

    public EventOutcome submit(Event event) {
        if (isWindowClosed()) {
            seenEventIds.add(event.id());
            return record(new EventOutcome(event, Day.last(), EventOutcome.Status.REJECTED,
                    List.of(new Rejection(Reason.WINDOW_CLOSED, "Day 6 has closed; the window takes no more events")),
                    List.of(), null));
        }
        while (event.bookedDay().isAfter(openDay())) {
            closeDay(openDay());
        }
        Day today = openDay();
        Account account = accounts.get(event.account());
        List<Rejection> rejections = validator.validate(event, account, today, seenEventIds);
        seenEventIds.add(event.id());
        if (!rejections.isEmpty()) {
            return record(new EventOutcome(event, today, EventOutcome.Status.REJECTED, rejections, List.of(), null));
        }

        EventOutcome outcome = switch (event) {
            case Event.Credit c -> postCredit(c, account, today);
            case Event.Debit d -> posted(d, today, List.of(
                    journal.append(EntryKind.DEBIT, account.id(), today, d.valueDay(), d.amount().negate(), d.id(), null)));
            case Event.Authorization a -> authorize(a, account, today);
            case Event.Settlement s -> settle(s, today);
            case Event.Reversal r -> reverse(r, today);
        };

        if (outcome.status() == EventOutcome.Status.POSTED && closedThrough >= event.valueDay().number()) {
            reconcileFees(account, Day.of(closedThrough), today, event.id());
        }
        return record(outcome);
    }

    private EventOutcome postCredit(Event.Credit c, Account account, Day today) {
        BigInteger minor = c.amount().amount().unscaledValue();
        BigInteger part = minor.divide(BigInteger.valueOf(c.instalments()));
        Money each = new Money(account.currency(), new BigDecimal(part, account.currency().scale()));
        List<Entry> posted = new ArrayList<>();
        for (int i = 1; i <= c.instalments(); i++) {
            posted.add(journal.append(EntryKind.CREDIT, account.id(), today, c.valueDay(), each, c.id(),
                    c.instalments() == 1 ? null : "instalment %d/%d".formatted(i, c.instalments())));
        }
        return posted(c, today, posted);
    }

    private EventOutcome authorize(Event.Authorization a, Account account, Day today) {
        AuthorizationPolicy.Decision decision = authorizationPolicy.decide(
                journal.balanceAsOf(account, today), holds.activeHolds(account, today), a.amount());
        if (!decision.approved()) {
            return new EventOutcome(a, today, EventOutcome.Status.DECLINED, List.of(), List.of(), decision.explanation());
        }
        Entry hold = journal.append(EntryKind.HOLD_PLACED, account.id(), today, a.valueDay(), a.amount(), a.id(), a.authId());
        return new EventOutcome(a, today, EventOutcome.Status.APPROVED, List.of(), List.of(hold.sequence()),
                decision.explanation());
    }

    private EventOutcome settle(Event.Settlement s, Day today) {
        HoldBook.Hold hold = holds.find(s.authId(), today).orElseThrow();
        List<Entry> posted = new ArrayList<>();
        posted.add(journal.append(EntryKind.SETTLEMENT, s.account(), today, s.valueDay(), s.amount().negate(), s.id(), s.authId()));
        posted.add(journal.append(EntryKind.HOLD_SETTLED, s.account(), today, s.valueDay(), s.amount(), s.id(), s.authId()));
        Money remainder = hold.amount().minus(s.amount());
        if (remainder.isPositive()) {
            posted.add(journal.append(EntryKind.HOLD_RELEASED, s.account(), today, s.valueDay(), remainder, s.id(), s.authId()));
        }
        return posted(s, today, posted);
    }

    private EventOutcome reverse(Event.Reversal r, Day today) {
        List<Entry> targets = journal.entries().stream()
                .filter(e -> e.cause().equals(r.reversedEventId()) && !e.kind().isFeeOrRefund())
                .toList();
        List<Entry> posted = new ArrayList<>();
        for (Entry target : targets) {
            posted.add(journal.append(EntryKind.REVERSAL, target.account(), today, target.valueDay(),
                    target.amount().negate(), r.id(), r.reversedEventId()));
        }
        return posted(r, today, posted);
    }

    private static EventOutcome posted(Event event, Day today, List<Entry> entries) {
        return new EventOutcome(event, today, EventOutcome.Status.POSTED, List.of(),
                entries.stream().map(Entry::sequence).toList(), null);
    }

    private EventOutcome record(EventOutcome outcome) {
        outcomes.add(outcome);
        return outcome;
    }

    // ---------------------------------------------------------------- fees

    /**
     * Brings the fees for Day 1..{@code through} in line with what is known now. Missing fees are
     * booked today, value-dated to their day. Fees no longer justified are refunded, booked and
     * value-dated today. Running it twice changes nothing the second time.
     */
    private void reconcileFees(Account account, Day through, Day today, String cause) {
        Set<Day> required = feePolicy.daysRequiringFee(account, journal, through);
        Map<Day, Entry> active = activeFees(account, through);

        for (Day day : required) {
            if (active.containsKey(day)) {
                continue;
            }
            Optional<Money> fee = feePolicy.feeFor(account.currency());
            if (fee.isEmpty()) {
                addErrorOnce(new EngineError(today, account.id(),
                        "%s closes negative but no overdraft fee is defined in %s; no fee assessed (no FX rate is invented)"
                                .formatted(day, account.currency())));
                continue;
            }
            journal.append(EntryKind.OVERDRAFT_FEE, account.id(), today, day, fee.get().negate(), cause, null);
        }
        for (Map.Entry<Day, Entry> e : active.entrySet()) {
            if (!required.contains(e.getKey())) {
                Entry fee = e.getValue();
                journal.append(EntryKind.FEE_REFUND, account.id(), today, today, fee.amount().negate(), cause,
                        Long.toString(fee.sequence()));
            }
        }
    }

    /** Fees for Day 1..through that have not been refunded, by the day they are for. */
    private Map<Day, Entry> activeFees(Account account, Day through) {
        Set<String> refunded = new HashSet<>();
        for (Entry e : journal.forAccount(account.id())) {
            if (e.kind() == EntryKind.FEE_REFUND) {
                refunded.add(e.reference());
            }
        }
        Map<Day, Entry> active = new TreeMap<>();
        for (Entry e : journal.forAccount(account.id())) {
            if (e.kind() == EntryKind.OVERDRAFT_FEE && !e.valueDay().isAfter(through)
                    && !refunded.contains(Long.toString(e.sequence()))) {
                active.put(e.valueDay(), e);
            }
        }
        return active;
    }

    private void addErrorOnce(EngineError error) {
        if (!errors.contains(error)) {
            errors.add(error);
        }
    }

    // ---------------------------------------------------------------- day close

    /** Closes the open day. Days close strictly in order; closing any other day is a programming error. */
    public DayClose closeDay(Day day) {
        if (isWindowClosed() || !day.equals(openDay())) {
            throw new IllegalStateException("cannot close " + day + (isWindowClosed() ? ": window closed" : ": open day is " + openDay()));
        }
        String cause = "close " + day;
        for (Account account : accounts.values()) {
            reconcileFees(account, day, day, cause);
        }
        Map<AccountId, Money> beforeInterest = new LinkedHashMap<>();
        for (Account account : accounts.values()) {
            beforeInterest.put(account.id(), journal.balanceAsOf(account, day));
        }
        if (day.isLast()) {
            accrueAndCapitalize(day, cause);
        }
        closedThrough = day.number();
        snapshots.add(journal.snapshot());

        List<DayClose.AccountClose> perAccount = new ArrayList<>();
        for (Account account : accounts.values()) {
            List<Money> view = Day.through(day).stream().map(d -> journal.balanceAsOf(account, d)).toList();
            perAccount.add(new DayClose.AccountClose(account.id(), beforeInterest.get(account.id()),
                    journal.balanceAsOf(account, day), view));
        }
        DayClose close = new DayClose(day, perAccount, journal.size());
        closes.add(close);
        return close;
    }

    /** Closes every remaining day through Day 6. */
    public void finish() {
        while (!isWindowClosed()) {
            closeDay(openDay());
        }
    }

    private void accrueAndCapitalize(Day lastDay, String cause) {
        for (Account account : accounts.values()) {
            Money total = Money.zero(account.currency());
            for (Day day : Day.through(lastDay)) {
                Money closing = journal.balanceAsOf(account, day);
                Money accrual = interestPolicy.dailyAccrual(closing);
                accruals.add(new Accrual(account.id(), day, closing, accrual));
                total = total.plus(accrual);
            }
            if (total.isPositive()) {
                journal.append(EntryKind.INTEREST_CAPITALIZATION, account.id(), lastDay, lastDay, total, cause, null);
            }
        }
    }

    // ---------------------------------------------------------------- queries

    public boolean isWindowClosed() {
        return closedThrough >= Day.LAST;
    }

    /** The business day currently open. */
    public Day openDay() {
        if (isWindowClosed()) {
            throw new IllegalStateException("window closed");
        }
        return Day.of(closedThrough + 1);
    }

    public Collection<Account> accounts() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    public Account account(AccountId id) {
        Account account = accounts.get(id);
        if (account == null) {
            throw new IllegalArgumentException("no account " + id);
        }
        return account;
    }

    public Journal journal() {
        return journal;
    }

    public HoldBook holds() {
        return holds;
    }

    public OverdraftFeePolicy feePolicy() {
        return feePolicy;
    }

    public List<EventOutcome> outcomes() {
        return Collections.unmodifiableList(outcomes);
    }

    public EventOutcome outcome(String eventId) {
        return outcomes.stream().filter(o -> o.event().id().equals(eventId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no outcome for " + eventId));
    }

    public List<EngineError> errors() {
        return Collections.unmodifiableList(errors);
    }

    public List<DayClose> closes() {
        return Collections.unmodifiableList(closes);
    }

    public DayClose close(Day day) {
        return closes.stream().filter(c -> c.day().equals(day)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(day + " has not closed"));
    }

    public List<Accrual> accruals() {
        return Collections.unmodifiableList(accruals);
    }

    /** The journal as it stood at each day close, oldest first. */
    public List<List<Entry>> snapshots() {
        return Collections.unmodifiableList(snapshots);
    }
}
