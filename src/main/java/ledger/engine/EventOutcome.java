package ledger.engine;

import ledger.domain.Day;
import ledger.domain.Event;
import ledger.rules.Rejection;

import java.util.List;

/**
 * What happened to one submitted event. The event itself is kept unchanged alongside its outcome.
 *
 * @param processedOn     the business day that was open when the event arrived
 * @param entrySequences  journal entries the event posted (empty unless POSTED or APPROVED)
 * @param note            explanation for APPROVED / DECLINED, or {@code null}
 */
public record EventOutcome(Event event, Day processedOn, Status status, List<Rejection> rejections,
                           List<Long> entrySequences, String note) {

    public enum Status {
        /** Money moved (credit, debit, settlement, reversal). */
        POSTED,
        /** Authorization approved; a hold was placed. */
        APPROVED,
        /** Authorization valid but refused under the available-balance rule; nothing posted. */
        DECLINED,
        /** Event malformed or impossible; nothing posted. See {@link #rejections()}. */
        REJECTED
    }

    public EventOutcome {
        rejections = List.copyOf(rejections);
        entrySequences = List.copyOf(entrySequences);
    }
}
