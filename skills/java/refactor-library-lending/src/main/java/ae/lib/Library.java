package ae.lib;

import static ae.lib.Result.Refusal.*;
import static ae.lib.Result.done;
import static ae.lib.Result.refused;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Library lending facade. Same public signatures and string results as the legacy class;
 * rules live in {@link Tier}, {@link Member}, {@link HoldQueue} and {@link Catalog}.
 */
public class Library {
    private final Catalog catalog = new Catalog();
    private final Map<String, Member> members = new HashMap<>();
    private final List<Loan> loans = new ArrayList<>();

    public String addBook(String id, String title, int copies) {
        return addBookResult(id, copies).code();
    }

    public String join(String member, String tier) {
        return joinResult(member, tier).code();
    }

    public String checkout(String member, String book, int day) {
        return checkoutResult(member, book, day).code();
    }

    public String renew(String member, String book, int day) {
        return renewResult(member, book, day).code();
    }

    public String giveBack(String member, String book, int day) {
        return giveBackResult(member, book, day).code();
    }

    public String hold(String member, String book) {
        return holdResult(member, book).code();
    }

    public String payFine(String member, int cents) {
        return payFineResult(member, cents).code();
    }

    public int owed(String member) {
        Member m = memberOf(member);
        return m == null ? -1 : m.owed();
    }

    public int available(String book) {
        if (!catalog.contains(book)) return -1;
        return freeCopies(Catalog.key(book));
    }

    public int queueLength(String book) {
        return catalog.contains(book) ? catalog.queue(Catalog.key(book)).size() : -1;
    }

    public List<String> loansOf(String member) {
        List<String> out = new ArrayList<>();
        if (member == null) return out;
        String name = member.trim();
        for (Loan l : loans) if (l.member().equals(name)) out.add(l.describe());
        Collections.sort(out);
        return out;
    }

    // ---- commands -------------------------------------------------------------------------

    private Result addBookResult(String id, int copies) {
        if (id == null || id.isBlank()) return refused(ERR_ID);
        if (copies < 1) return refused(ERR_COPIES);
        return done(catalog.add(id.trim(), copies) ? "OK" : "OK_ADDED");
    }

    private Result joinResult(String member, String tierText) {
        if (member == null || member.isBlank()) return refused(ERR_NAME);
        Optional<Tier> tier = Tier.parse(tierText);
        if (tier.isEmpty()) return refused(ERR_TIER);
        String name = member.trim();
        if (members.containsKey(name)) return refused(ERR_DUP);
        members.put(name, new Member(tier.get()));
        return done("OK");
    }

    private Result checkoutResult(String member, String book, int day) {
        if (day < 0) return refused(ERR_DAY);
        Member m = memberOf(member);
        if (m == null) return refused(NO_MEMBER);
        if (!catalog.contains(book)) return refused(NO_BOOK);
        String who = member.trim();
        String id = Catalog.key(book);
        if (m.isBlocked()) return refused(BLOCKED);
        if (loanCount(who) >= m.tier.loanLimit) return refused(LIMIT);
        if (findLoan(who, id) != null) return refused(DUP_LOAN);
        if (freeCopies(id) <= 0) return refused(UNAVAILABLE);
        HoldQueue queue = catalog.queue(id);
        if (!queue.isEmpty()) {
            if (!queue.isHead(who)) return refused(HELD_FOR_OTHERS);
            queue.removeHead();
        }
        int due = day + m.tier.loanDays;
        loans.add(new Loan(who, id, due, 0));
        return done("DUE:" + due);
    }

    private Result renewResult(String member, String book, int day) {
        if (day < 0) return refused(ERR_DAY);
        Member m = memberOf(member);
        if (m == null) return refused(NO_MEMBER);
        Loan loan = book == null ? null : findLoan(member.trim(), book.trim());
        if (loan == null) return refused(NO_LOAN);
        if (loan.renewals() >= m.tier.maxRenewals) return refused(MAX_RENEWALS);
        if (!catalog.queue(loan.book()).isEmpty()) return refused(HELD);
        if (day > loan.due()) return refused(OVERDUE);
        Loan renewed = loan.renewedTo(day + m.tier.loanDays);
        loans.set(loans.indexOf(loan), renewed);
        return done("DUE:" + renewed.due());
    }

    private Result giveBackResult(String member, String book, int day) {
        if (day < 0) return refused(ERR_DAY);
        Member m = memberOf(member);
        if (m == null) return refused(NO_MEMBER);
        Loan loan = book == null ? null : findLoan(member.trim(), book.trim());
        if (loan == null) return refused(NO_LOAN);
        loans.remove(loan);
        int fine = m.tier.fineFor(day - loan.due());
        if (fine == 0) return done("RETURNED");
        m.charge(fine);
        return done("RETURNED_FINE:" + fine);
    }

    private Result holdResult(String member, String book) {
        Member m = memberOf(member);
        if (m == null) return refused(NO_MEMBER);
        if (!catalog.contains(book)) return refused(NO_BOOK);
        String who = member.trim();
        String id = Catalog.key(book);
        if (findLoan(who, id) != null) return refused(HAS_LOAN);
        HoldQueue queue = catalog.queue(id);
        if (queue.contains(who)) return refused(DUP_HOLD);
        if (queue.isEmpty() && freeCopies(id) > 0) return done("AVAILABLE");
        if (queue.isFull()) return refused(QUEUE_FULL);
        return done("HOLD:" + queue.enqueue(who));
    }

    private Result payFineResult(String member, int cents) {
        Member m = memberOf(member);
        if (m == null) return refused(NO_MEMBER);
        if (cents <= 0) return refused(ERR_AMOUNT);
        int paid = m.pay(cents);
        return done("PAID:" + paid + " LEFT:" + m.owed());
    }

    // ---- helpers --------------------------------------------------------------------------

    private Member memberOf(String name) {
        return name == null ? null : members.get(name.trim());
    }

    private int freeCopies(String id) {
        int n = 0;
        for (Loan l : loans) if (l.book().equals(id)) n++;
        return catalog.copies(id) - n;
    }

    private int loanCount(String member) {
        int n = 0;
        for (Loan l : loans) if (l.member().equals(member)) n++;
        return n;
    }

    private Loan findLoan(String member, String book) {
        for (Loan l : loans) if (l.member().equals(member) && l.book().equals(book)) return l;
        return null;
    }
}
