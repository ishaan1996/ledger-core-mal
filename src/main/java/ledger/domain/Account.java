package ledger.domain;

import ledger.money.Currency;
import ledger.money.Money;

import java.util.Objects;

/** A ledger account. It holds no balance: balances are always derived from the journal. */
public record Account(AccountId id, Currency currency, Money openingBalance) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(openingBalance, "openingBalance");
        if (openingBalance.currency() != currency) {
            throw new IllegalArgumentException("opening balance of " + id + " must be in " + currency);
        }
    }
}
