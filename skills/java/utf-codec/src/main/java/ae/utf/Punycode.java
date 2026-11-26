package ae.utf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** RFC 3492 Punycode (the bootstring parameters for IDNA); no "xn--" handling. */
public final class Punycode {
    private static final int BASE = 36;
    private static final int TMIN = 1;
    private static final int TMAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 128;
    private static final long MAX = Integer.MAX_VALUE;

    private Punycode() { }

    public static String encode(int[] cps) {
        Objects.requireNonNull(cps, "cps");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c < 0 || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
                throw new CodecException("not a Unicode scalar value: " + c, i);
            }
            if (c < 0x80) {
                out.append((char) c);
            }
        }
        int b = out.length();
        int h = b;
        if (b > 0) {
            out.append('-');
        }
        long n = INITIAL_N;
        long delta = 0;
        int bias = INITIAL_BIAS;
        while (h < cps.length) {
            int m = Integer.MAX_VALUE;
            for (int c : cps) {
                if (c >= n && c < m) {
                    m = c;
                }
            }
            delta += (m - n) * (h + 1L);
            if (delta > MAX) {
                throw new CodecException("overflow", -1);
            }
            n = m;
            for (int c : cps) {
                if (c < n && ++delta > MAX) {
                    throw new CodecException("overflow", -1);
                }
                if (c == n) {
                    long q = delta;
                    for (int k = BASE;; k += BASE) {
                        int t = threshold(k, bias);
                        if (q < t) {
                            break;
                        }
                        out.append(digit((int) (t + (q - t) % (BASE - t))));
                        q = (q - t) / (BASE - t);
                    }
                    out.append(digit((int) q));
                    bias = adapt(delta, h + 1, h == b);
                    delta = 0;
                    h++;
                }
            }
            delta++;
            n++;
        }
        return out.toString();
    }

    public static int[] decode(String s) {
        Objects.requireNonNull(s, "s");
        int delim = s.lastIndexOf('-');
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < Math.max(delim, 0); j++) {
            char c = s.charAt(j);
            if (c >= 0x80) {
                throw new CodecException("non-basic character in basic segment", j);
            }
            out.add((int) c);
        }
        long n = INITIAL_N;
        long i = 0;
        int bias = INITIAL_BIAS;
        int pos = delim > 0 ? delim + 1 : 0;
        // delim == 0 means the string starts with '-' and has no basic code points: the '-' is then a digit-less error below
        while (pos < s.length()) {
            long oldi = i;
            long w = 1;
            for (int k = BASE;; k += BASE) {
                if (pos >= s.length()) {
                    throw new CodecException("truncated number", pos);
                }
                int d = value(s.charAt(pos), pos);
                pos++;
                i += d * w;
                if (i > MAX) {
                    throw new CodecException("overflow", pos - 1);
                }
                int t = threshold(k, bias);
                if (d < t) {
                    break;
                }
                w *= BASE - t;
                if (w > MAX) {
                    throw new CodecException("overflow", pos - 1);
                }
            }
            int len = out.size() + 1;
            bias = adapt(i - oldi, len, oldi == 0);
            n += i / len;
            i %= len;
            if (n > 0x10FFFF || (n >= 0xD800 && n <= 0xDFFF)) {
                throw new CodecException("decoded value is not a Unicode scalar value", pos - 1);
            }
            out.add((int) i, (int) n);
            i++;
        }
        int[] r = new int[out.size()];
        for (int j = 0; j < r.length; j++) {
            r[j] = out.get(j);
        }
        return r;
    }

    private static int threshold(int k, int bias) {
        if (k <= bias) {
            return TMIN;
        }
        return k >= bias + TMAX ? TMAX : k - bias;
    }

    private static int adapt(long delta, int numPoints, boolean first) {
        long d = first ? delta / DAMP : delta / 2;
        d += d / numPoints;
        int k = 0;
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= BASE - TMIN;
            k += BASE;
        }
        return (int) (k + (BASE - TMIN + 1) * d / (d + SKEW));
    }

    private static char digit(int d) {
        return (char) (d < 26 ? 'a' + d : '0' + (d - 26));
    }

    private static int value(char c, int at) {
        if (c >= 'a' && c <= 'z') {
            return c - 'a';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 26;
        }
        throw new CodecException("invalid digit '" + c + "'", at);
    }
}
