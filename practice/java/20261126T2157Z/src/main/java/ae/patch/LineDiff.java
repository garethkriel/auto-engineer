package ae.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minimal line diff (longest common subsequence) with prefix/suffix trimming. */
public final class LineDiff {
    private LineDiff() { }

    /**
     * Edit script turning {@code a} into {@code b} with the fewest deletes and inserts.
     * Inside a change block deletes come before inserts (delete wins every tie).
     */
    public static List<Edit> diff(List<String> a, List<String> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int n = a.size();
        int m = b.size();
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < n - pre && suf < m - pre && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) {
            suf++;
        }
        int an = n - pre - suf;
        int bn = m - pre - suf;
        // lcs[i][j] = LCS length of a[pre+i..pre+an) and b[pre+j..pre+bn)
        int w = bn + 1;
        int[] lcs = new int[(an + 1) * w];
        for (int i = an - 1; i >= 0; i--) {
            for (int j = bn - 1; j >= 0; j--) {
                if (a.get(pre + i).equals(b.get(pre + j))) {
                    lcs[i * w + j] = lcs[(i + 1) * w + j + 1] + 1;
                } else {
                    lcs[i * w + j] = Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
                }
            }
        }
        List<Edit> out = new ArrayList<>(n + m);
        for (int i = 0; i < pre; i++) {
            out.add(new Edit.Keep(a.get(i)));
        }
        int i = 0;
        int j = 0;
        while (i < an || j < bn) {
            if (i < an && j < bn && a.get(pre + i).equals(b.get(pre + j))) {
                out.add(new Edit.Keep(a.get(pre + i)));
                i++;
                j++;
            } else if (j >= bn || (i < an && lcs[(i + 1) * w + j] >= lcs[i * w + j + 1])) {
                out.add(new Edit.Delete(a.get(pre + i)));
                i++;
            } else {
                out.add(new Edit.Insert(b.get(pre + j)));
                j++;
            }
        }
        for (int k = n - suf; k < n; k++) {
            out.add(new Edit.Keep(a.get(k)));
        }
        return out;
    }

    /** Lines of the old text implied by the script (keeps and deletes). */
    public static List<String> oldSide(List<Edit> script) {
        List<String> out = new ArrayList<>();
        for (Edit e : script) {
            if (!(e instanceof Edit.Insert)) {
                out.add(e.line());
            }
        }
        return out;
    }

    /** Lines of the new text implied by the script (keeps and inserts). */
    public static List<String> newSide(List<Edit> script) {
        List<String> out = new ArrayList<>();
        for (Edit e : script) {
            if (!(e instanceof Edit.Delete)) {
                out.add(e.line());
            }
        }
        return out;
    }
}
