package ledger.replay;

import ledger.domain.Account;
import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.domain.Event;
import ledger.engine.LedgerEngine;
import ledger.money.Money;

import java.util.List;

import static ledger.money.Currency.AED;
import static ledger.money.Currency.BHD;

/** The assignment's two accounts and ten events, transcribed as given, in replay order. */
public final class Scenario {

    public static final AccountId ACC_001 = AccountId.of("ACC-001");
    public static final AccountId ACC_002 = AccountId.of("ACC-002");

    private Scenario() {}

    public static List<Account> accounts() {
        return List.of(
                new Account(ACC_001, AED, Money.of(AED, "0.00")),
                new Account(ACC_002, BHD, Money.of(BHD, "0.000")));
    }

    public static List<Event> events() {
        return List.of(
                new Event.Credit("E1", ACC_001, d(1), d(1), aed("1200.00")),
                new Event.Debit("E2", ACC_001, d(1), d(1), aed("950.00")),
                new Event.Authorization("E3", ACC_001, d(2), d(2), "Auth-A", aed("200.00")),
                new Event.Credit("E4", ACC_001, d(3), d(3), aed("400.00")),
                new Event.Settlement("E5", ACC_001, d(4), d(4), "Auth-A", aed("185.00")),
                new Event.Settlement("E6", ACC_001, d(4), d(4), "Auth-Z", aed("180.00")),
                new Event.Debit("E7", ACC_001, d(5), d(2), aed("620.00")),
                new Event.Authorization("E8", ACC_001, d(5), d(5), "Auth-B", aed("90.00")),
                new Event.Reversal("E9", ACC_001, d(6), d(2), "E7"),
                new Event.Credit("E10", ACC_002, d(5), d(5), Money.of(BHD, "10.000"), 3));
    }

    /** Opens the accounts, submits every event in order, and closes the window. */
    public static LedgerEngine replay() {
        LedgerEngine engine = new LedgerEngine();
        accounts().forEach(engine::open);
        events().forEach(engine::submit);
        engine.finish();
        return engine;
    }

    private static Day d(int n) {
        return Day.of(n);
    }

    private static Money aed(String value) {
        return Money.of(AED, value);
    }
}
