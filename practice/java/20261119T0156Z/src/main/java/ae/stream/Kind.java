package ae.stream;

/** Why a result was emitted: the watermark passed the window end, or late data changed a fired window. */
public enum Kind { ON_TIME, LATE_UPDATE }
