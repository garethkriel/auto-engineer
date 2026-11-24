package ae.columnar;

/** Column encodings; the ordinal is the on-disk tag and the tie-break order of {@link EncodedColumn#encodeBest}. */
public enum Encoding { PLAIN, RLE, DICT, FOR, DELTA }
