package ledger.rules;

import ledger.money.Money;

/**
 * "An authorization is approved only if available balance (ledger minus active holds) stays ≥ 0
 * after the hold." Decisions are made once, at arrival, on what is known then; they are never
 * re-run later.
 */
public final class AuthorizationPolicy {

    public record Decision(boolean approved, Money ledger, Money activeHolds, Money hold, Money availableAfter) {

        public String explanation() {
            return "available after hold = ledger %s - active holds %s - hold %s = %s %s 0"
                    .formatted(ledger, activeHolds, hold, availableAfter, approved ? ">=" : "<");
        }
    }

    public Decision decide(Money ledger, Money activeHolds, Money hold) {
        Money availableAfter = ledger.minus(activeHolds).minus(hold);
        return new Decision(!availableAfter.isNegative(), ledger, activeHolds, hold, availableAfter);
    }
}
