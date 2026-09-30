package ledger.rules;

import java.util.Objects;

/** One reason an event was refused, with a human-readable explanation. */
public record Rejection(Reason reason, String detail) {

    public Rejection {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
        return reason + ": " + detail;
    }
}
