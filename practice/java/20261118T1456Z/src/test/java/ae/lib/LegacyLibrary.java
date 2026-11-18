package ae.lib;

import java.util.*;

/** Supplied messy implementation. Oracle for the refactoring; do not clean up. */
public class LegacyLibrary {
    private final Map<String, Object[]> books = new HashMap<>();   // {title, copies}
    private final Map<String, Object[]> members = new HashMap<>();  // {tier, owed}
    private final List<Object[]> loans = new ArrayList<>();         // {member, book, due, renewals}
    private final Map<String, List<String>> holds = new HashMap<>();

    public String addBook(String id, String title, int copies) {
        if (id == null || id.trim().isEmpty()) return "ERR_ID";
        if (copies < 1) return "ERR_COPIES";
        id = id.trim();
        Object[] b = books.get(id);
        if (b != null) { b[1] = (Integer) b[1] + copies; return "OK_ADDED"; }
        books.put(id, new Object[]{title, copies});
        holds.put(id, new ArrayList<>());
        return "OK";
    }

    public String join(String member, String tier) {
        if (member == null || member.trim().isEmpty()) return "ERR_NAME";
        String t = tier == null ? "" : tier.trim().toLowerCase();
        if (!t.equals("std") && !t.equals("prem") && !t.equals("kid")) return "ERR_TIER";
        member = member.trim();
        if (members.containsKey(member)) return "ERR_DUP";
        members.put(member, new Object[]{t, 0});
        return "OK";
    }

    private int loaned(String book) {
        int n = 0;
        for (Object[] l : loans) if (l[1].equals(book)) n++;
        return n;
    }

    private Object[] findLoan(String m, String b) {
        for (Object[] l : loans) if (l[0].equals(m) && l[1].equals(b)) return l;
        return null;
    }

    public String checkout(String member, String book, int day) {
        if (day < 0) return "ERR_DAY";
        Object[] m = member == null ? null : members.get(member.trim());
        if (m == null) return "NO_MEMBER";
        member = member.trim();
        Object[] b = book == null ? null : books.get(book.trim());
        if (b == null) return "NO_BOOK";
        book = book.trim();
        if ((Integer) m[1] >= 500) return "BLOCKED";
        int count = 0;
        for (Object[] l : loans) if (l[0].equals(member)) count++;
        String t = (String) m[0];
        int limit = t.equals("std") ? 3 : t.equals("prem") ? 6 : 2;
        if (count >= limit) return "LIMIT";
        if (findLoan(member, book) != null) return "DUP_LOAN";
        int avail = (Integer) b[1] - loaned(book);
        if (avail <= 0) return "UNAVAILABLE";
        List<String> q = holds.get(book);
        if (!q.isEmpty()) {
            if (!q.get(0).equals(member)) return "HELD_FOR_OTHERS";
            q.remove(0);
        }
        int period = t.equals("std") ? 14 : t.equals("prem") ? 28 : 7;
        loans.add(new Object[]{member, book, day + period, 0});
        return "DUE:" + (day + period);
    }

    public String renew(String member, String book, int day) {
        if (day < 0) return "ERR_DAY";
        Object[] m = member == null ? null : members.get(member.trim());
        if (m == null) return "NO_MEMBER";
        Object[] l = book == null ? null : findLoan(member.trim(), book.trim());
        if (l == null) return "NO_LOAN";
        String t = (String) m[0];
        int max = t.equals("std") ? 1 : t.equals("prem") ? 2 : 0;
        if ((Integer) l[3] >= max) return "MAX_RENEWALS";
        if (!holds.get(book.trim()).isEmpty()) return "HELD";
        if (day > (Integer) l[2]) return "OVERDUE";
        int period = t.equals("std") ? 14 : t.equals("prem") ? 28 : 7;
        l[2] = day + period;
        l[3] = (Integer) l[3] + 1;
        return "DUE:" + l[2];
    }

    public String giveBack(String member, String book, int day) {
        if (day < 0) return "ERR_DAY";
        Object[] m = member == null ? null : members.get(member.trim());
        if (m == null) return "NO_MEMBER";
        Object[] l = book == null ? null : findLoan(member.trim(), book.trim());
        if (l == null) return "NO_LOAN";
        loans.remove(l);
        int late = day - (Integer) l[2];
        if (late <= 0) return "RETURNED";
        String t = (String) m[0];
        int rate = t.equals("std") ? 25 : t.equals("prem") ? 10 : 5;
        int cap = t.equals("kid") ? 300 : 1000;
        int fine = Math.min(late * rate, cap);
        m[1] = (Integer) m[1] + fine;
        return "RETURNED_FINE:" + fine;
    }

    public String hold(String member, String book) {
        Object[] m = member == null ? null : members.get(member.trim());
        if (m == null) return "NO_MEMBER";
        Object[] b = book == null ? null : books.get(book.trim());
        if (b == null) return "NO_BOOK";
        member = member.trim();
        book = book.trim();
        if (findLoan(member, book) != null) return "HAS_LOAN";
        List<String> q = holds.get(book);
        if (q.contains(member)) return "DUP_HOLD";
        if (q.isEmpty() && (Integer) b[1] - loaned(book) > 0) return "AVAILABLE";
        if (q.size() >= 3) return "QUEUE_FULL";
        q.add(member);
        return "HOLD:" + q.size();
    }

    public String payFine(String member, int cents) {
        Object[] m = member == null ? null : members.get(member.trim());
        if (m == null) return "NO_MEMBER";
        if (cents <= 0) return "ERR_AMOUNT";
        int owed = (Integer) m[1];
        int p = Math.min(cents, owed);
        m[1] = owed - p;
        return "PAID:" + p + " LEFT:" + (owed - p);
    }

    public int owed(String member) {
        Object[] m = member == null ? null : members.get(member.trim());
        return m == null ? -1 : (Integer) m[1];
    }

    public int available(String book) {
        Object[] b = book == null ? null : books.get(book.trim());
        return b == null ? -1 : (Integer) b[1] - loaned(book.trim());
    }

    public int queueLength(String book) {
        Object[] b = book == null ? null : books.get(book.trim());
        return b == null ? -1 : holds.get(book.trim()).size();
    }

    public List<String> loansOf(String member) {
        List<String> out = new ArrayList<>();
        if (member == null) return out;
        for (Object[] l : loans) if (l[0].equals(member.trim())) out.add(l[1] + ":" + l[2]);
        Collections.sort(out);
        return out;
    }
}
