package ledger.domain;

import ledger.money.Money;

/**
 * An instruction arriving from outside. Events are immutable; the engine records each one with its
 * outcome and never changes it. Every event carries the day it was booked (when the bank learned of
 * it) and the day it is value-dated to (which day's balance it belongs to).
 */
public sealed interface Event {

    String id();

    AccountId account();

    Day bookedDay();

    Day valueDay();

    /** A credit, optionally to be posted as {@code instalments} equal parts. */
    record Credit(String id, AccountId account, Day bookedDay, Day valueDay, Money amount, int instalments)
            implements Event {

        public Credit(String id, AccountId account, Day bookedDay, Day valueDay, Money amount) {
            this(id, account, bookedDay, valueDay, amount, 1);
        }
    }

    record Debit(String id, AccountId account, Day bookedDay, Day valueDay, Money amount) implements Event {}

    /** A card authorization: asks to place a hold of {@code amount}. */
    record Authorization(String id, AccountId account, Day bookedDay, Day valueDay, String authId, Money amount)
            implements Event {}

    /** Captures funds held by an earlier approved authorization. */
    record Settlement(String id, AccountId account, Day bookedDay, Day valueDay, String authId, Money amount)
            implements Event {}

    /** Undoes an earlier posted credit or debit by appending its mirror image. */
    record Reversal(String id, AccountId account, Day bookedDay, Day valueDay, String reversedEventId)
            implements Event {}
}
