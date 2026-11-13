package ae;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** A small in-memory full-text index mapping terms to the documents that contain them. */
public final class InvertedIndex {
    private final Map<String, SortedSet<Integer>> docsByTerm = new TreeMap<>();
    private final Map<String, Integer> countByTerm = new HashMap<>();

    /** Adds the tokens of {@code text} to document {@code docId}. */
    public void add(int docId, String text) {
        Objects.requireNonNull(text, "text");
        if (docId < 0) {
            throw new IllegalArgumentException("docId must be >= 0: " + docId);
        }
        for (String token : tokenize(text)) {
            docsByTerm.computeIfAbsent(token, k -> new TreeSet<>()).add(docId);
            countByTerm.merge(token, 1, Integer::sum);
        }
    }

    /** Documents containing {@code term}, sorted and unmodifiable. */
    public SortedSet<Integer> search(String term) {
        Objects.requireNonNull(term, "term");
        SortedSet<Integer> docs = docsByTerm.get(term.toLowerCase(Locale.ROOT));
        return docs == null ? Collections.emptySortedSet() : Collections.unmodifiableSortedSet(docs);
    }

    /** Documents containing every one of {@code terms}. */
    public SortedSet<Integer> all(String... terms) {
        Objects.requireNonNull(terms, "terms");
        if (terms.length == 0) {
            return Collections.emptySortedSet();
        }
        SortedSet<Integer> result = new TreeSet<>(search(terms[0]));
        for (int i = 1; i < terms.length; i++) {
            result.retainAll(search(terms[i]));
        }
        return Collections.unmodifiableSortedSet(result);
    }

    /** Documents containing at least one of {@code terms}. */
    public SortedSet<Integer> any(String... terms) {
        Objects.requireNonNull(terms, "terms");
        SortedSet<Integer> result = new TreeSet<>();
        for (String t : terms) {
            result.addAll(search(t));
        }
        return Collections.unmodifiableSortedSet(result);
    }

    /** Up to {@code n} most frequent terms: count descending, then alphabetical. */
    public List<String> topTerms(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0: " + n);
        }
        List<String> terms = new ArrayList<>(countByTerm.keySet());
        terms.sort(Comparator.comparing((String t) -> countByTerm.get(t)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(terms.subList(0, Math.min(n, terms.size())));
    }

    /** One line per term, alphabetical: {@code term -> [ids]}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SortedSet<Integer>> e : docsByTerm.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append(" -> ").append(e.getValue());
        }
        return sb.toString();
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                tokens.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
