package ae.sst;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** K-way merge (compaction) of SSTables. */
public final class SstMerge {
    private SstMerge() {}

    /**
     * Merges {@code newestFirst} into {@code out}. When several tables hold a key, the earliest table in the list wins.
     * With {@code dropTombstones} deletion markers are omitted from the output. Returns the entries written.
     */
    public static int merge(Path out, List<SstReader> newestFirst, boolean dropTombstones) throws IOException {
        Objects.requireNonNull(out, "out");
        List<List<Entry>> lists = new ArrayList<>();
        for (SstReader r : newestFirst) lists.add(r.entries());
        int[] cursor = new int[lists.size()];
        List<Entry> merged = new ArrayList<>();
        while (true) {
            Entry best = null;
            for (int i = 0; i < lists.size(); i++) {
                if (cursor[i] >= lists.get(i).size()) continue;
                Entry cand = lists.get(i).get(cursor[i]);
                // strictly smaller replaces; equal keys keep the earlier (newer) table's entry
                if (best == null || Entry.compareKeys(cand.key(), best.key()) < 0) best = cand;
            }
            if (best == null) break;
            for (int i = 0; i < lists.size(); i++) {
                if (cursor[i] < lists.get(i).size() && lists.get(i).get(cursor[i]).key().equals(best.key())) cursor[i]++;
            }
            if (!(dropTombstones && best.isTombstone())) merged.add(best);
        }
        return SstWriter.write(out, merged);
    }
}
