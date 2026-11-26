package ae.book;

/** Time in force of a limit order. */
public enum Tif {
    /** Good till cancelled: an unfilled remainder rests. */
    GTC,
    /** Immediate or cancel: an unfilled remainder is dropped. */
    IOC,
    /** Fill or kill: fills completely or not at all. */
    FOK
}
