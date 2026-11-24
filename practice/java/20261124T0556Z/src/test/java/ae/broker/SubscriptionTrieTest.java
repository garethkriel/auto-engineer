package ae.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionTrieTest {
    private static Map<String, Integer> asMap(List<SubscriptionTrie.Entry<Integer>> l) {
        Map<String, Integer> m = new TreeMap<>();
        for (SubscriptionTrie.Entry<Integer> e : l) {
            assertNull(m.put(e.key(), e.value()), "duplicate entry for " + e.key());
        }
        return m;
    }

    @Test
    void literalAndWildcards() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("a/b", "c1", 1);
        t.add("a/+", "c2", 2);
        t.add("a/#", "c3", 3);
        t.add("#", "c4", 4);
        t.add("+/b", "c5", 5);
        assertEquals(Map.of("c1", 1, "c2", 2, "c3", 3, "c4", 4, "c5", 5), asMap(t.match("a/b")));
        assertEquals(Map.of("c3", 3, "c4", 4), asMap(t.match("a/b/c")));
        assertEquals(Map.of("c3", 3, "c4", 4), asMap(t.match("a")));
        assertEquals(Map.of("c4", 4), asMap(t.match("z")));
    }

    @Test
    void dollarRuleAppliesOnlyAtRoot() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("#", "all", 0);
        t.add("+/x", "plus", 0);
        t.add("$SYS/#", "sys", 0);
        t.add("a/+", "inner", 0);
        assertEquals(Set.of("sys"), asMap(t.match("$SYS/x")).keySet());
        assertEquals(Set.of("all", "inner"), asMap(t.match("a/$q")).keySet());
    }

    @Test
    void sameKeySameFilterReplacesValue() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("a", "c", 1);
        t.add("a", "c", 2);
        assertEquals(Map.of("c", 2), asMap(t.match("a")));
        assertEquals(1, t.size());
    }

    @Test
    void sameKeyTwoFiltersGivesTwoEntries() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("a/+", "c", 0);
        t.add("a/#", "c", 1);
        List<SubscriptionTrie.Entry<Integer>> m = t.match("a/b");
        assertEquals(2, m.size());
        assertEquals(1, m.stream().mapToInt(SubscriptionTrie.Entry::value).max().getAsInt());
    }

    @Test
    void removePrunesNodes() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        assertEquals(1, t.nodeCount());
        t.add("a/b/c", "k", 0);
        t.add("a/b/#", "k2", 0);
        assertEquals(1 + 3 + 1, t.nodeCount());
        assertTrue(t.remove("a/b/c", "k"));
        assertEquals(1 + 3, t.nodeCount());
        assertFalse(t.remove("a/b/c", "k"));
        assertFalse(t.remove("x", "k"));
        assertTrue(t.remove("a/b/#", "k2"));
        assertEquals(1, t.nodeCount());
        assertEquals(0, t.size());
        assertTrue(t.match("a/b/c").isEmpty());
    }

    @Test
    void removeKeepsSiblings() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("a/b", "k", 0);
        t.add("a/c", "k", 0);
        t.remove("a/b", "k");
        assertEquals(Set.of("k"), asMap(t.match("a/c")).keySet());
        assertTrue(t.match("a/b").isEmpty());
    }

    @Test
    void removeKey() {
        SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
        t.add("a/b", "k", 0);
        t.add("#", "k", 0);
        t.add("a/b", "other", 0);
        assertEquals(2, t.removeKey("k"));
        assertEquals(Set.of("other"), asMap(t.match("a/b")).keySet());
        assertEquals(1, t.size());
        assertEquals(0, t.removeKey("k"));
    }

    @Test
    void differentialAgainstReferenceMatcher() {
        Random rnd = new Random(20261124);
        String[] words = {"a", "b", "c", "", "$s"};
        int wildcardHits = 0;
        for (int round = 0; round < 300; round++) {
            SubscriptionTrie<Integer> t = new SubscriptionTrie<>();
            List<Map.Entry<String, Integer>> filters = new ArrayList<>();
            int n = 1 + rnd.nextInt(12);
            for (int i = 0; i < n; i++) {
                String f = randomFilter(rnd, words);
                filters.add(Map.entry(f, i));
                t.add(f, "k" + i, i);
                }
            for (int q = 0; q < 40; q++) {
                String topic = randomTopic(rnd, words);
                Set<String> expected = new TreeSet<>();
                for (Map.Entry<String, Integer> e : filters) {
                    if (Topics.matches(e.getKey(), topic)) {
                        expected.add("k" + e.getValue());
                    }
                }
                Set<String> got = new TreeSet<>();
                for (SubscriptionTrie.Entry<Integer> e : t.match(topic)) {
                    got.add(e.key());
                }
                assertEquals(expected, got, "topic " + topic + " filters " + filters);
                if (!expected.isEmpty()) {
                    wildcardHits++;
                }
            }
        }
        assertTrue(wildcardHits > 1000, "generator should produce plenty of matches: " + wildcardHits);
    }

    static String randomFilter(Random rnd, String[] words) {
        int levels = 1 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            if (i > 0) {
                sb.append('/');
            }
            int r = rnd.nextInt(10);
            if (i == levels - 1 && r == 0) {
                sb.append('#');
            } else if (r < 4) {
                sb.append('+');
            } else {
                sb.append(words[rnd.nextInt(words.length)]);
            }
        }
        return sb.length() == 0 ? "a" : sb.toString();
    }

    static String randomTopic(Random rnd, String[] words) {
        int levels = 1 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(words[rnd.nextInt(words.length)]);
        }
        return sb.length() == 0 ? "a" : sb.toString();
    }
}
