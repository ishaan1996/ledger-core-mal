package ledger.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact amount in one currency. The scale of {@code amount} always equals the currency's scale;
 * the constructor refuses anything else, so "stored at its own precision" is a type invariant, not
 * a convention. Arithmetic across currencies throws.
 */
public record Money(Currency currency, BigDecimal amount) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() != currency.scale()) {
            throw new IllegalArgumentException("%s amounts have exactly %d decimal places, got %s"
                    .formatted(currency, currency.scale(), amount.toPlainString()));
        }
    }

    /**
     * Parses an amount. Trailing zeros are padded to the currency's scale; any digit beyond it is
     * refused rather than rounded (a posting is never silently rounded).
     */
    public static Money of(Currency currency, String value) {
        BigDecimal parsed = new BigDecimal(value);
        try {
            return new Money(currency, parsed.setScale(currency.scale(), RoundingMode.UNNECESSARY));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("%s cannot be held in %s at %d decimal places"
                    .formatted(value, currency, currency.scale()), e);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(currency, BigDecimal.ZERO.setScale(currency.scale()));
    }

    /** The only rounding entry point: an exact intermediate rounded once to the currency's scale. */
    public static Money rounded(Currency currency, BigDecimal exact, RoundingMode mode) {
        return new Money(currency, exact.setScale(currency.scale(), mode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.add(other.amount));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(currency, amount.negate());
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    /** Signed plain decimal, e.g. {@code -230.00} or {@code 0.000}. */
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
