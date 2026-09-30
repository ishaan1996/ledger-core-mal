package ledger.journal;

import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.money.Money;

import java.util.Objects;

/**
 * One immutable journal line.
 *
 * @param sequence   1-based position in the journal; also the entry's identity
 * @param amount     signed for ledger kinds (credits positive, debits negative); positive for memo kinds
 * @param cause      what produced the entry: an event id (e.g. {@code E7}) or a day close (e.g. {@code close D5})
 * @param reference  kind-specific link, or {@code null}: the auth id for hold memos, the reversed
 *                   event id for a reversal, the refunded fee's sequence for a fee refund
 */
public record Entry(
        long sequence,
        EntryKind kind,
        AccountId account,
        Day bookedDay,
        Day valueDay,
        Money amount,
        String cause,
        String reference) {

    public Entry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(bookedDay, "bookedDay");
        Objects.requireNonNull(valueDay, "valueDay");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cause, "cause");
        if (valueDay.isAfter(bookedDay)) {
            throw new IllegalArgumentException("entry value day " + valueDay + " is after booked day " + bookedDay);
        }
    }
}
