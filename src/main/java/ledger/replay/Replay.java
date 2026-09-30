package ledger.replay;

import ledger.engine.Invariants;
import ledger.engine.LedgerEngine;

import java.util.List;

/** Entry point: {@code bash run.sh}. Replays the scenario, prints the report, exits 1 if any invariant fails. */
public final class Replay {

    private Replay() {}

    public static void main(String[] args) {
        LedgerEngine engine = Scenario.replay();
        new ReportPrinter(engine, System.out).print();
        List<Invariants.Check> failed = Invariants.verify(engine).stream().filter(c -> !c.ok()).toList();
        if (!failed.isEmpty()) {
            System.err.println("INVARIANT FAILURES: " + failed);
            System.exit(1);
        }
    }
}
