package ae.xsort;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LoserTreeTest {
    /** (key, source) pair; ordering uses key only so ties must fall back to source index. */
    record Item(int key, int source) {}

    private static final Comparator<Item> BY_KEY = Comparator.comparingInt(Item::key);

    private static List<Item> merge(List<List<Item>> sources) {
        List<Iterator<Item>> its = new ArrayList<>();
        for (List<Item> s : sources) its.add(s.iterator());
        LoserTree<Item> tree = new LoserTree<>(its, BY_KEY);
        List<Item> out = new ArrayList<>();
        while (tree.hasNext()) out.add(tree.next());
        assertFalse(tree.hasNext());
        return out;
    }

    @Test
    void literalMergeOfThreeSourcesWithTies() {
        // sources 0:[1,3,3] 1:[3,4] 2:[1,3]; equal keys come out in source order.
        List<List<Item>> src = List.of(
                List.of(new Item(1, 0), new Item(3, 0), new Item(3, 0)),
                List.of(new Item(3, 1), new Item(4, 1)),
                List.of(new Item(1, 2), new Item(3, 2)));
        List<Item> expected = List.of(
                new Item(1, 0), new Item(1, 2),
                new Item(3, 0), new Item(3, 0), new Item(3, 1), new Item(3, 2),
                new Item(4, 1));
        assertEquals(expected, merge(src));
    }

    @Test
    void singleSourcePassesThrough() {
        assertEquals(List.of(new Item(1, 0), new Item(2, 0)),
                merge(List.of(List.of(new Item(1, 0), new Item(2, 0)))));
    }

    @Test
    void allSourcesEmptyYieldsNothing() {
        for (int k = 1; k <= 5; k++) {
            List<List<Item>> src = new ArrayList<>();
            for (int i = 0; i < k; i++) src.add(List.of());
            assertEquals(List.of(), merge(src), "k=" + k);
        }
    }

    @Test
    void randomMergeMatchesNaiveStableSortForEveryK() {
        Random rnd = new Random(42);
        for (int k = 1; k <= 9; k++) {
            for (int trial = 0; trial < 60; trial++) {
                List<List<Item>> src = new ArrayList<>();
                List<Item> all = new ArrayList<>();
                for (int s = 0; s < k; s++) {
                    int len = rnd.nextInt(6); // may be empty
                    int[] keys = new int[len];
                    for (int i = 0; i < len; i++) keys[i] = rnd.nextInt(5); // many ties
                    Arrays.sort(keys);
                    List<Item> list = new ArrayList<>();
                    for (int key : keys) list.add(new Item(key, s));
                    src.add(list);
                    all.addAll(list);
                }
                // naive oracle: concatenation in source order, stable sort by key
                List<Item> expected = new ArrayList<>(all);
                expected.sort(BY_KEY);
                assertEquals(expected, merge(src), "k=" + k + " trial=" + trial);
            }
        }
    }

    @Test
    void nextOnExhaustedTreeThrows() {
        LoserTree<Item> tree = new LoserTree<>(List.of(List.<Item>of().iterator()), BY_KEY);
        assertThrows(NoSuchElementException.class, tree::next);
    }

    @Test
    void rejectsEmptySourceList() {
        assertThrows(IllegalArgumentException.class, () -> new LoserTree<Item>(List.of(), BY_KEY));
    }
}
