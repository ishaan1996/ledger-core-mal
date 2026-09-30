package ledger.rules;

import ledger.domain.Account;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.journal.Entry;
import ledger.journal.EntryKind;
import ledger.journal.HoldBook;
import ledger.journal.Journal;
import ledger.money.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether an event may be applied at all. Every applicable check runs, so an event wrong in
 * two ways reports both reasons. An authorization that passes here can still be <em>declined</em>
 * by {@link AuthorizationPolicy}: a decline is a business outcome, a rejection means the event is
 * malformed or impossible.
 */
public final class EventValidator {

    private final Journal journal;
    private final HoldBook holds;

    public EventValidator(Journal journal, HoldBook holds) {
        this.journal = journal;
        this.holds = holds;
    }

    /**
     * @param account     the event's account, or {@code null} if it is not open
     * @param openDay     the business day currently open
     * @param seenIds     ids of every event submitted so far (accepted or not)
     */
    public List<Rejection> validate(Event event, Account account, Day openDay, Set<String> seenIds) {
        List<Rejection> out = new ArrayList<>();
        if (seenIds.contains(event.id())) {
            out.add(new Rejection(Reason.DUPLICATE_EVENT_ID, event.id() + " was already submitted"));
        }
        if (account == null) {
            out.add(new Rejection(Reason.UNKNOWN_ACCOUNT, "no open account " + event.account()));
            return out;
        }
        if (event.bookedDay().isBefore(openDay)) {
            out.add(new Rejection(Reason.BOOKED_DAY_CLOSED,
                    "booked on %s, but %s is already closed (the business day is %s); a closed day cannot take new bookings"
                            .formatted(event.bookedDay(), event.bookedDay(), openDay)));
        }
        if (event.valueDay().isAfter(event.bookedDay())) {
            out.add(new Rejection(Reason.VALUE_DAY_AFTER_BOOKED_DAY,
                    "value day %s is after booked day %s (forward dating is not supported)"
                            .formatted(event.valueDay(), event.bookedDay())));
        }
        amountOf(event).ifPresent(amount -> checkAmount(amount, account, out));

        switch (event) {
            case Event.Credit c -> checkInstalments(c, account, out);
            case Event.Debit d -> { }
            case Event.Authorization a -> {
                if (holds.find(a.authId(), Day.last()).isPresent()) {
                    out.add(new Rejection(Reason.DUPLICATE_AUTHORIZATION, a.authId() + " already has a hold"));
                }
            }
            case Event.Settlement s -> checkSettlement(s, account, openDay, out);
            case Event.Reversal r -> checkReversal(r, out);
        }
        return out;
    }

    private static Optional<Money> amountOf(Event event) {
        return switch (event) {
            case Event.Credit c -> Optional.of(c.amount());
            case Event.Debit d -> Optional.of(d.amount());
            case Event.Authorization a -> Optional.of(a.amount());
            case Event.Settlement s -> Optional.of(s.amount());
            case Event.Reversal r -> Optional.empty();
        };
    }

    private static void checkAmount(Money amount, Account account, List<Rejection> out) {
        if (amount.currency() != account.currency()) {
            out.add(new Rejection(Reason.CURRENCY_MISMATCH,
                    "amount is in %s but %s is a %s account".formatted(amount.currency(), account.id(), account.currency())));
        } else if (!amount.isPositive()) {
            out.add(new Rejection(Reason.NON_POSITIVE_AMOUNT, "amount must be positive, got " + amount));
        }
    }

    /** Equal instalments must each be a whole number of minor units: total (in minor units) mod n == 0. */
    private static void checkInstalments(Event.Credit c, Account account, List<Rejection> out) {
        if (c.instalments() < 1) {
            out.add(new Rejection(Reason.INVALID_INSTALMENT_COUNT, "instalment count must be at least 1, got " + c.instalments()));
            return;
        }
        if (c.instalments() == 1 || c.amount().currency() != account.currency()) {
            return;
        }
        BigInteger minor = c.amount().amount().unscaledValue();
        BigInteger[] qr = minor.divideAndRemainder(BigInteger.valueOf(c.instalments()));
        if (qr[1].signum() != 0) {
            int scale = c.amount().currency().scale();
            out.add(new Rejection(Reason.UNEQUAL_INSTALMENTS,
                    ("%s %s cannot be split into %d equal instalments at %d decimal places: %s / %d = %s..., "
                            + "and the nearest representable parts (%s) are not equal")
                            .formatted(c.amount().currency(), c.amount(), c.instalments(), scale,
                                    c.amount(), c.instalments(),
                                    new BigDecimal(minor).divide(BigDecimal.valueOf(c.instalments()),
                                            scale + 4, RoundingMode.DOWN).toPlainString(),
                                    nearestSplit(qr, c.instalments(), scale))));
        }
    }

    private static String nearestSplit(BigInteger[] qr, int n, int scale) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigInteger part = i < qr[1].intValue() ? qr[0].add(BigInteger.ONE) : qr[0];
            parts.add(new BigDecimal(part, scale).toPlainString());
        }
        return String.join(" + ", parts);
    }

    private void checkSettlement(Event.Settlement s, Account account, Day openDay, List<Rejection> out) {
        Optional<HoldBook.Hold> found = holds.find(s.authId(), openDay);
        if (found.isEmpty()) {
            out.add(new Rejection(Reason.UNKNOWN_AUTHORIZATION,
                    "no approved authorization %s precedes this settlement, so there is no hold to capture".formatted(s.authId())));
            return;
        }
        HoldBook.Hold hold = found.get();
        if (!hold.account().equals(account.id())) {
            out.add(new Rejection(Reason.AUTHORIZATION_ACCOUNT_MISMATCH,
                    "%s is held on %s, not %s".formatted(s.authId(), hold.account(), account.id())));
        }
        if (hold.state() != HoldBook.State.ACTIVE) {
            out.add(new Rejection(Reason.AUTHORIZATION_NOT_ACTIVE, "%s is already %s".formatted(s.authId(), hold.state())));
        }
        if (s.amount().currency() == hold.amount().currency() && s.amount().compareTo(hold.amount()) > 0) {
            out.add(new Rejection(Reason.SETTLEMENT_EXCEEDS_HOLD,
                    "settles %s but the hold is %s".formatted(s.amount(), hold.amount())));
        }
    }

    private void checkReversal(Event.Reversal r, List<Rejection> out) {
        // Fees restated because of the target carry it as their cause too; they are not part of what it posted.
        List<Entry> targets = journal.entries().stream()
                .filter(e -> e.cause().equals(r.reversedEventId()) && !e.kind().isFeeOrRefund())
                .toList();
        if (targets.isEmpty()) {
            out.add(new Rejection(Reason.UNKNOWN_REVERSAL_TARGET,
                    r.reversedEventId() + " posted nothing (unknown, rejected or declined), so there is nothing to reverse"));
            return;
        }
        if (targets.stream().anyMatch(e -> e.kind() != EntryKind.CREDIT && e.kind() != EntryKind.DEBIT)) {
            out.add(new Rejection(Reason.REVERSAL_TARGET_NOT_REVERSIBLE,
                    "only credits and debits can be reversed by an event; " + r.reversedEventId() + " is not one"));
            return;
        }
        Entry first = targets.get(0);
        if (!first.account().equals(r.account())) {
            out.add(new Rejection(Reason.REVERSAL_ACCOUNT_MISMATCH,
                    "%s was posted to %s, not %s".formatted(r.reversedEventId(), first.account(), r.account())));
        }
        if (!first.valueDay().equals(r.valueDay())) {
            out.add(new Rejection(Reason.REVERSAL_VALUE_DAY_MISMATCH,
                    "a reversal must carry the original value day %s, got %s".formatted(first.valueDay(), r.valueDay())));
        }
        boolean alreadyReversed = journal.entries().stream()
                .anyMatch(e -> e.kind() == EntryKind.REVERSAL && r.reversedEventId().equals(e.reference()));
        if (alreadyReversed) {
            out.add(new Rejection(Reason.ALREADY_REVERSED, r.reversedEventId() + " has already been reversed"));
        }
    }
}
