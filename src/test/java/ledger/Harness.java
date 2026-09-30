package ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A deliberately tiny test harness so the suite runs with only a JDK. Tests are named lambdas;
 * every test runs, failures are collected, and {@link #run()} exits non-zero if any failed.
 */
final class Harness {

    private record Test(String name, Runnable body) {}

    private final List<Test> tests = new ArrayList<>();

    void test(String name, Runnable body) {
        tests.add(new Test(name, body));
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void throwsIae(Supplier<?> body, String what) {
        try {
            body.get();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + ": expected IllegalArgumentException");
    }

    void run() {
        int failed = 0;
        for (Test t : tests) {
            try {
                t.body().run();
                System.out.println("PASS  " + t.name());
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL  " + t.name() + "\n      " + e);
            }
        }
        System.out.println();
        System.out.printf("%d tests, %d passed, %d failed%n", tests.size(), tests.size() - failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
