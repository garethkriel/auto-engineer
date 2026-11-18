package ae.lib;

/** An open loan; immutable, renewal produces a new value. */
record Loan(String member, String book, int due, int renewals) {
    Loan renewedTo(int newDue) { return new Loan(member, book, newDue, renewals + 1); }

    String describe() { return book + ":" + due; }
}
