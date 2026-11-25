package ae.rv;

import java.util.Map;

/** An assembled program: little-endian words loaded at address 0 and the label table (byte addresses). */
public record Program(int[] words, Map<String, Integer> labels) { }
