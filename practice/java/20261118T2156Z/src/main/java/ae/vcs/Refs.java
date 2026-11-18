package ae.vcs;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Port: named pointers to commits. */
public interface Refs {
    Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    Optional<ObjectId> get(String name);

    void set(String name, ObjectId id);

    /** All names, sorted. */
    List<String> names();

    static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("bad branch name: " + name);
        }
    }
}
