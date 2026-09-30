package ledger.money;

/** ISO 4217 currencies used in the window, with their minor-unit precision. */
public enum Currency {
    /** UAE dirham: 100 fils. */
    AED(2),
    /** Bahraini dinar: 1000 fils. */
    BHD(3);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    /** Decimal places every amount in this currency is stored and rounded to. */
    public int scale() {
        return scale;
    }
}
