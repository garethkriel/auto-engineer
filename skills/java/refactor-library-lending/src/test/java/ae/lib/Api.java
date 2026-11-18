package ae.lib;

import java.util.List;

/** Common surface of the legacy oracle and the refactored library. */
interface Api {
    String addBook(String id, String title, int copies);
    String join(String member, String tier);
    String checkout(String member, String book, int day);
    String renew(String member, String book, int day);
    String giveBack(String member, String book, int day);
    String hold(String member, String book);
    String payFine(String member, int cents);
    int owed(String member);
    int available(String book);
    int queueLength(String book);
    List<String> loansOf(String member);

    static Api legacy() {
        LegacyLibrary l = new LegacyLibrary();
        return new Api() {
            public String addBook(String i, String t, int c) { return l.addBook(i, t, c); }
            public String join(String m, String t) { return l.join(m, t); }
            public String checkout(String m, String b, int d) { return l.checkout(m, b, d); }
            public String renew(String m, String b, int d) { return l.renew(m, b, d); }
            public String giveBack(String m, String b, int d) { return l.giveBack(m, b, d); }
            public String hold(String m, String b) { return l.hold(m, b); }
            public String payFine(String m, int c) { return l.payFine(m, c); }
            public int owed(String m) { return l.owed(m); }
            public int available(String b) { return l.available(b); }
            public int queueLength(String b) { return l.queueLength(b); }
            public List<String> loansOf(String m) { return l.loansOf(m); }
        };
    }

    static Api refactored() {
        Library l = new Library();
        return new Api() {
            public String addBook(String i, String t, int c) { return l.addBook(i, t, c); }
            public String join(String m, String t) { return l.join(m, t); }
            public String checkout(String m, String b, int d) { return l.checkout(m, b, d); }
            public String renew(String m, String b, int d) { return l.renew(m, b, d); }
            public String giveBack(String m, String b, int d) { return l.giveBack(m, b, d); }
            public String hold(String m, String b) { return l.hold(m, b); }
            public String payFine(String m, int c) { return l.payFine(m, c); }
            public int owed(String m) { return l.owed(m); }
            public int available(String b) { return l.available(b); }
            public int queueLength(String b) { return l.queueLength(b); }
            public List<String> loansOf(String m) { return l.loansOf(m); }
        };
    }
}
