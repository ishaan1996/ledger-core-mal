package ledger.journal;

/**
 * What an entry records. Ledger kinds move the ledger balance. Memo kinds record the life of a
 * card hold so that hold state can be derived from the journal too; they never touch the ledger
 * balance (a hold reduces <em>available</em>, not ledger).
 */
public enum EntryKind {
    CREDIT(true),
    DEBIT(true),
    SETTLEMENT(true),
    REVERSAL(true),
    OVERDRAFT_FEE(true),
    FEE_REFUND(true),
    INTEREST_CAPITALIZATION(true),

    /** A hold was placed. Amount is the hold, positive. */
    HOLD_PLACED(false),
    /** Part of a hold was captured by a settlement. Amount is the captured part, positive. */
    HOLD_SETTLED(false),
    /** The uncaptured rest of a hold was released. Amount is the released part, positive. */
    HOLD_RELEASED(false);

    private final boolean affectsLedger;

    EntryKind(boolean affectsLedger) {
        this.affectsLedger = affectsLedger;
    }

    public boolean affectsLedger() {
        return affectsLedger;
    }

    public boolean isFeeOrRefund() {
        return this == OVERDRAFT_FEE || this == FEE_REFUND;
    }
}
