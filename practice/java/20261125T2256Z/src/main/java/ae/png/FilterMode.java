package ae.png;

/** How the encoder chooses the scanline filter: a fixed type for every row, or per-row adaptive. */
public enum FilterMode {
    FIXED_0, FIXED_1, FIXED_2, FIXED_3, FIXED_4, ADAPTIVE
}
