package ae.cfg;

import java.util.List;
import java.util.Objects;

/** A labelled straight-line sequence ending in exactly one terminator (checked by {@link Function}). */
public record Block(String label, List<Instr> instrs) {
    public Block {
        Objects.requireNonNull(label, "label");
        instrs = List.copyOf(instrs);
    }
}
