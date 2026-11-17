package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class CronSchedule {
    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] doms = new boolean[32];
    private final boolean[] months = new boolean[13];
    private final boolean[] dows = new boolean[7];
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(String[] f) {
        fill(f[0], 0, 59, minutes);
        fill(f[1], 0, 23, hours);
        fill(f[2], 1, 31, doms);
        fill(f[3], 1, 12, months);
        boolean[] raw = new boolean[8];
        fill(f[4], 0, 7, raw);
        for (int i = 0; i < 8; i++) if (raw[i]) dows[i % 7] = true;
        domRestricted = !f[2].equals("*");
        dowRestricted = !f[4].equals("*");
    }

    public static CronSchedule parse(String expression) {
        if (expression == null) throw new IllegalArgumentException("null");
        String t = expression.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("empty");
        String[] f = t.split(" +", -1);
        if (f.length != 5) throw new IllegalArgumentException("need 5 fields");
        return new CronSchedule(f);
    }

    private static int num(String s, int min, int max) {
        if (s.isEmpty() || s.length() > 9) throw new IllegalArgumentException("bad number '" + s + "'");
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') throw new IllegalArgumentException("bad number '" + s + "'");
        }
        int v = Integer.parseInt(s);
        if (v < min || v > max) throw new IllegalArgumentException("out of range: " + v);
        return v;
    }

    private static void fill(String field, int min, int max, boolean[] set) {
        for (String item : field.split(",", -1)) {
            if (item.isEmpty()) throw new IllegalArgumentException("empty item");
            int step = 1;
            String range = item;
            int slash = item.indexOf('/');
            boolean hasStep = slash >= 0;
            if (hasStep) {
                range = item.substring(0, slash);
                step = num(item.substring(slash + 1), 1, Integer.MAX_VALUE);
            }
            int lo, hi;
            if (range.equals("*")) {
                lo = min; hi = max;
            } else {
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    lo = num(range.substring(0, dash), min, max);
                    hi = num(range.substring(dash + 1), min, max);
                    if (lo > hi) throw new IllegalArgumentException("a > b");
                } else {
                    lo = num(range, min, max);
                    hi = hasStep ? max : lo;
                }
            }
            for (int v = lo; v <= hi; v += step) set[v] = true;
        }
    }

    private boolean dayMatches(LocalDate d) {
        if (!months[d.getMonthValue()]) return false;
        boolean dom = doms[d.getDayOfMonth()];
        boolean dow = dows[d.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) return dom || dow;
        if (domRestricted) return dom;
        if (dowRestricted) return dow;
        return true;
    }

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) throw new IllegalArgumentException("null");
        LocalDateTime start = after.withSecond(0).withNano(0).plusMinutes(1);
        LocalDateTime limit = after.plusYears(8);
        LocalDate day = start.toLocalDate();
        boolean first = true;
        while (true) {
            if (day.atStartOfDay().isAfter(limit)) throw new IllegalStateException("no match within 8 years");
            if (dayMatches(day)) {
                int h0 = first ? start.getHour() : 0;
                for (int h = h0; h < 24; h++) {
                    if (!hours[h]) continue;
                    int m0 = first && h == start.getHour() ? start.getMinute() : 0;
                    for (int m = m0; m < 60; m++) {
                        if (minutes[m]) {
                            LocalDateTime r = day.atTime(h, m);
                            if (r.isAfter(limit)) throw new IllegalStateException("no match within 8 years");
                            return r;
                        }
                    }
                }
            }
            day = day.plusDays(1);
            first = false;
        }
    }
}
