package ae.rangecoder;

/** An adaptive symbol model over {@code 0..n-1} that feeds {@link RangeEncoder#encode}. */
public interface FreqModel {
    /** @return sum of all frequencies */
    int total();

    /** @return sum of the frequencies of symbols below {@code sym} */
    int cumFreq(int sym);

    /** @return frequency of {@code sym}, at least 1 */
    int freq(int sym);

    /** @return the symbol {@code s} with {@code cumFreq(s) <= target < cumFreq(s)+freq(s)} */
    int findSymbol(int target);

    /** Records one occurrence of {@code sym}. */
    void update(int sym);
}
