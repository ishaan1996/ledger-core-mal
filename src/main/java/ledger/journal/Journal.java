package ledger.journal;

import ledger.domain.Account;
import ledger.domain.AccountId;
import ledger.domain.Day;
import ledger.money.Money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The append-only journal. There is no update or delete operation, and callers only ever see an
 * unmodifiable view. Corrections are new entries (reversals, fee refunds), never edits.
 */
public final class Journal {

    private final List<Entry> entries = new ArrayList<>();

    public Entry append(EntryKind kind, AccountId account, Day bookedDay, Day valueDay, Money amount,
                        String cause, String reference) {
        Entry entry = new Entry(entries.size() + 1L, kind, account, bookedDay, valueDay, amount, cause, reference);
        entries.add(entry);
        return entry;
    }

    /** Read-only view of every entry in append order. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** An immutable copy of the journal as it stands now. */
    public List<Entry> snapshot() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public Optional<Entry> bySequence(long sequence) {
        if (sequence < 1 || sequence > entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (sequence - 1)));
    }

    /** Ledger balance of {@code account} for {@code day}: opening balance plus every ledger entry with value day ≤ day. */
    public Money balanceAsOf(Account account, Day day) {
        Money balance = account.openingBalance();
        for (Entry e : entries) {
            if (e.kind().affectsLedger() && e.account().equals(account.id()) && !e.valueDay().isAfter(day)) {
                balance = balance.plus(e.amount());
            }
        }
        return balance;
    }

    public List<Entry> forAccount(AccountId account) {
        return entries.stream().filter(e -> e.account().equals(account)).toList();
    }
}
