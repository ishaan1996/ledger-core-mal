package ledger.engine;

import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.money.Money;

import java.util.List;

/** What was known about every account at the moment a day closed. */
public record DayClose(Day day, List<AccountClose> accounts, int journalSizeAtClose) {

    /**
     * @param closingBeforeInterest closing ledger balance for the day before any interest
     *                              capitalization (differs from {@code closing} only on Day 6)
     * @param closing               closing ledger balance for the day, as known at this close
     * @param valueDatedView        balance of Day 1..this day, as known at this close (index 0 is Day 1)
     */
    public record AccountClose(AccountId account, Money closingBeforeInterest, Money closing,
                               List<Money> valueDatedView) {

        public AccountClose {
            valueDatedView = List.copyOf(valueDatedView);
        }

        public Money asOf(Day day) {
            return valueDatedView.get(day.number() - Day.FIRST);
        }
    }

    public DayClose {
        accounts = List.copyOf(accounts);
    }

    public AccountClose account(AccountId id) {
        return accounts.stream().filter(a -> a.account().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no close for " + id));
    }
}
