package ae.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A semantic version {@code major.minor.patch[-prerelease]} with semver 2.0 precedence. */
public record Version(int major, int minor, int patch, List<String> pre) implements Comparable<Version> {
    private static final Pattern FORM = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?");
    private static final Pattern NUMERIC = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern ALNUM = Pattern.compile("[0-9A-Za-z-]+");

    /** Canonical constructor: validates fields and makes {@code pre} immutable. */
    public Version {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("negative version number");
        pre = List.copyOf(pre);
        for (String id : pre) {
            if (!ALNUM.matcher(id).matches()) throw new IllegalArgumentException("bad prerelease identifier '" + id + "'");
            if (id.chars().allMatch(Character::isDigit) && !NUMERIC.matcher(id).matches()) {
                throw new IllegalArgumentException("numeric prerelease identifier with leading zero: " + id);
            }
        }
    }

    /** Release version without prerelease. */
    public Version(int major, int minor, int patch) {
        this(major, minor, patch, List.of());
    }

    /** Strictly parses text such as {@code 1.2.3} or {@code 1.2.3-rc.1}. */
    public static Version parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = FORM.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("not a version: '" + text + "'");
        try {
            List<String> pre = new ArrayList<>();
            if (m.group(4) != null) pre.addAll(List.of(m.group(4).split("\\.", -1)));
            return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), pre);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("version number out of range: '" + text + "'", e);
        }
    }

    /** True if this has a prerelease part. */
    public boolean isPrerelease() {
        return !pre.isEmpty();
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (pre.isEmpty() || o.pre.isEmpty()) return Boolean.compare(pre.isEmpty(), o.pre.isEmpty());
        for (int i = 0; i < Math.min(pre.size(), o.pre.size()); i++) {
            c = compareId(pre.get(i), o.pre.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(pre.size(), o.pre.size());
    }

    private static int compareId(String a, String b) {
        boolean na = NUMERIC.matcher(a).matches();
        boolean nb = NUMERIC.matcher(b).matches();
        if (na && nb) {
            // no leading zeros, so length decides first and then digits: safe for arbitrarily long ids
            int c = Integer.compare(a.length(), b.length());
            return c != 0 ? c : a.compareTo(b);
        }
        if (na) return -1;
        if (nb) return 1;
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return pre.isEmpty() ? base : base + "-" + String.join(".", pre);
    }
}
