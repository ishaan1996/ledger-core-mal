package ledger.domain;

import java.util.Objects;

public record AccountId(String value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id is blank");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
