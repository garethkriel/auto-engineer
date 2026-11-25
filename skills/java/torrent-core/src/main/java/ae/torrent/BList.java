package ae.torrent;

import java.util.List;

/** Bencode list; the element list is copied and immutable. */
public record BList(List<BValue> items) implements BValue {
    public BList {
        items = List.copyOf(items);
    }
}
