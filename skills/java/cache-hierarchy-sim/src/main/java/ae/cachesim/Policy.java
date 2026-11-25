package ae.cachesim;

/** Replacement policy used to pick a victim in a full set. */
public enum Policy {
    /** Least recently used (touched on hit and fill). */
    LRU,
    /** First in, first out (hits do not refresh). */
    FIFO,
    /** Tree pseudo-LRU; needs a power-of-two way count of at most 32. */
    PLRU,
    /** Uniformly random victim from a seeded generator. */
    RANDOM
}
