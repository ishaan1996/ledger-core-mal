package ledger.journal;

import ledger.domain.Account;
import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Card holds, derived on demand from the journal's hold memo entries. Nothing is stored here, so a
 * hold's state can never drift from the journal.
 */
public final class HoldBook {

    public enum State { ACTIVE, SETTLED, RELEASED }

    /** A hold as it stood at the end of some day. */
    public record Hold(String authId, AccountId account, Day placedOn, Money amount, State state,
                       Money settled, Money released, Day closedOn) {}

    private final Journal journal;

    public HoldBook(Journal journal) {
        this.journal = journal;
    }

    /** The hold for {@code authId} considering entries booked on or before {@code asOf}. */
    public Optional<Hold> find(String authId, Day asOf) {
        Entry placed = null;
        Entry settled = null;
        Entry released = null;
        for (Entry e : journal.entries()) {
            if (!authId.equals(e.reference()) || e.bookedDay().isAfter(asOf)) {
                continue;
            }
            switch (e.kind()) {
                case HOLD_PLACED -> placed = e;
                case HOLD_SETTLED -> settled = e;
                case HOLD_RELEASED -> released = e;
                default -> { }
            }
        }
        if (placed == null) {
            return Optional.empty();
        }
        Money zero = Money.zero(placed.amount().currency());
        State state = settled != null ? State.SETTLED : released != null ? State.RELEASED : State.ACTIVE;
        Day closedOn = settled != null ? settled.bookedDay() : released != null ? released.bookedDay() : null;
        return Optional.of(new Hold(authId, placed.account(), placed.bookedDay(), placed.amount(), state,
                settled != null ? settled.amount() : zero, released != null ? released.amount() : zero, closedOn));
    }

    /** Every hold placed on or before {@code asOf}, in the order placed. */
    public List<Hold> holdsAsOf(Day asOf) {
        List<Hold> holds = new ArrayList<>();
        for (Entry e : journal.entries()) {
            if (e.kind() == EntryKind.HOLD_PLACED && !e.bookedDay().isAfter(asOf)) {
                find(e.reference(), asOf).ifPresent(holds::add);
            }
        }
        return holds;
    }

    /** Sum of holds still active on {@code account} at {@code asOf}. */
    public Money activeHolds(Account account, Day asOf) {
        Money total = Money.zero(account.currency());
        for (Hold h : holdsAsOf(asOf)) {
            if (h.account().equals(account.id()) && h.state() == State.ACTIVE) {
                total = total.plus(h.amount());
            }
        }
        return total;
    }
}
