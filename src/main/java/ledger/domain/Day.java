package ledger.domain;

import java.util.ArrayList;
import java.util.List;

/** A business day inside the window, Day 1 to Day 6. Days outside the window cannot be constructed. */
public record Day(int number) implements Comparable<Day> {

    public static final int FIRST = 1;
    public static final int LAST = 6;

    public Day {
        if (number < FIRST || number > LAST) {
            throw new IllegalArgumentException("day %d is outside the window D%d..D%d".formatted(number, FIRST, LAST));
        }
    }

    public static Day of(int number) {
        return new Day(number);
    }

    public static Day first() {
        return new Day(FIRST);
    }

    public static Day last() {
        return new Day(LAST);
    }

    /** Day 1 up to and including {@code last}. */
    public static List<Day> through(Day last) {
        List<Day> days = new ArrayList<>();
        for (int n = FIRST; n <= last.number; n++) {
            days.add(new Day(n));
        }
        return days;
    }

    /** Day 1 to Day 6. */
    public static List<Day> all() {
        return through(last());
    }

    public boolean isLast() {
        return number == LAST;
    }

    public Day next() {
        return new Day(number + 1);
    }

    public boolean isBefore(Day other) {
        return number < other.number;
    }

    public boolean isAfter(Day other) {
        return number > other.number;
    }

    @Override
    public int compareTo(Day other) {
        return Integer.compare(number, other.number);
    }

    @Override
    public String toString() {
        return "D" + number;
    }
}
