package ledger.rules;

import ledger.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * "0.04% per day on closing ledger balance, positive balances only." Each day's accrual is the
 * exact product rounded once, HALF_EVEN, to the currency's scale. The capitalized total is the sum
 * of those rounded dailies, so they add up exactly by construction.
 */
public final class InterestPolicy {

    public static final BigDecimal DAILY_RATE = new BigDecimal("0.0004");
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money dailyAccrual(Money closingBalance) {
        if (!closingBalance.isPositive()) {
            return Money.zero(closingBalance.currency());
        }
        return Money.rounded(closingBalance.currency(), closingBalance.amount().multiply(DAILY_RATE), ROUNDING);
    }
}
