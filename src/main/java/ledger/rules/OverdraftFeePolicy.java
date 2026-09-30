package ledger.rules;

import ledger.domain.Account;
import ledger.domain.Day;
import ledger.journal.Entry;
import ledger.journal.Journal;
import ledger.money.Currency;
import ledger.money.Money;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * "AED 25.00, once per day per account, when that day's closing ledger balance is negative, booked
 * with value date equal to the day assessed."
 *
 * <p>{@link #daysRequiringFee} answers "which days should carry a fee, on everything known now?".
 * It walks the days in order: a day's balance is every non-fee entry dated on or before it, plus
 * the fees this same walk has already decided for earlier days. Existing fee and refund entries are
 * deliberately ignored, so the answer does not depend on what was booked before; the engine compares
 * it with the fees actually on the books and appends only the difference. That makes assessment
 * idempotent and lets a later correction (a reversal) show which fees are no longer justified.
 */
public final class OverdraftFeePolicy {

    private final Map<Currency, Money> schedule;

    public OverdraftFeePolicy(Map<Currency, Money> schedule) {
        this.schedule = Map.copyOf(schedule);
    }

    public static OverdraftFeePolicy standard() {
        return new OverdraftFeePolicy(Map.of(Currency.AED, Money.of(Currency.AED, "25.00")));
    }

    /** The fee for a currency, if one is defined. No FX conversion is ever invented. */
    public Optional<Money> feeFor(Currency currency) {
        return Optional.ofNullable(schedule.get(currency));
    }

    public Set<Day> daysRequiringFee(Account account, Journal journal, Day through) {
        Set<Day> days = new TreeSet<>();
        Optional<Money> fee = feeFor(account.currency());
        Money feesSoFar = Money.zero(account.currency());
        for (Day day : Day.through(through)) {
            Money balance = balanceExcludingFees(account, journal, day).minus(feesSoFar);
            if (balance.isNegative()) {
                days.add(day);
                if (fee.isPresent()) {
                    feesSoFar = feesSoFar.plus(fee.get());
                }
            }
        }
        return days;
    }

    /** Opening balance plus every ledger entry dated on or before {@code day}, except fees and refunds. */
    public static Money balanceExcludingFees(Account account, Journal journal, Day day) {
        Money balance = account.openingBalance();
        for (Entry e : journal.entries()) {
            if (e.kind().affectsLedger() && !e.kind().isFeeOrRefund()
                    && e.account().equals(account.id()) && !e.valueDay().isAfter(day)) {
                balance = balance.plus(e.amount());
            }
        }
        return balance;
    }
}
