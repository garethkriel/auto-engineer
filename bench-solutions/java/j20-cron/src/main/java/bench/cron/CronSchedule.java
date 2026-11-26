package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Five-field cron expression with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] dom;
    private final boolean[] months;
    private final boolean[] dow; // index 0..6, 0 = Sunday
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(String[] f) {
        minutes = field(f[0], 0, 59, 60);
        hours = field(f[1], 0, 23, 24);
        dom = field(f[2], 1, 31, 32);
        months = field(f[3], 1, 12, 13);
        boolean[] raw = field(f[4], 0, 7, 8);
        dow = new boolean[7];
        for (int i = 0; i < 8; i++) {
            if (raw[i]) {
                dow[i % 7] = true;
            }
        }
        domRestricted = !f[2].equals("*");
        dowRestricted = !f[4].equals("*");
    }

    public static CronSchedule parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        String t = expression.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        String[] f = t.split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        return new CronSchedule(f);
    }

    private static boolean[] field(String s, int min, int max, int size) {
        boolean[] out = new boolean[size];
        for (String item : s.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item");
            }
            String base = item;
            int step = 1;
            boolean hasStep = false;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                base = item.substring(0, slash);
                step = num(item.substring(slash + 1));
                hasStep = true;
                if (step < 1) {
                    throw new IllegalArgumentException("step < 1");
                }
            }
            int lo;
            int hi;
            if (base.equals("*")) {
                lo = min;
                hi = max;
            } else if (base.indexOf('-') >= 0) {
                int d = base.indexOf('-');
                lo = num(base.substring(0, d));
                hi = num(base.substring(d + 1));
                if (lo > hi) {
                    throw new IllegalArgumentException("a > b");
                }
            } else {
                lo = num(base);
                hi = hasStep ? max : lo;
            }
            if (lo < min || hi > max) {
                throw new IllegalArgumentException("out of range");
            }
            for (int v = lo; v <= hi; v += step) {
                out[v] = true;
            }
        }
        return out;
    }

    private static int num(String s) {
        if (s.isEmpty() || s.length() > 6) {
            throw new IllegalArgumentException("bad number");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad number");
            }
        }
        return Integer.parseInt(s);
    }

    private boolean dayMatches(LocalDate d) {
        boolean dm = dom[d.getDayOfMonth()];
        boolean wm = dow[d.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) {
            return dm || wm;
        }
        if (domRestricted) {
            return dm;
        }
        if (dowRestricted) {
            return wm;
        }
        return true;
    }

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("null after");
        }
        LocalDateTime start = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate limit = after.toLocalDate().plusYears(8).plusDays(1);
        LocalDate d = start.toLocalDate();
        boolean first = true;
        while (!d.isAfter(limit)) {
            if (!months[d.getMonthValue()]) {
                d = d.withDayOfMonth(1).plusMonths(1);
                first = false;
                continue;
            }
            if (dayMatches(d)) {
                int h0 = first ? start.getHour() : 0;
                for (int h = h0; h < 24; h++) {
                    if (!hours[h]) {
                        continue;
                    }
                    int m0 = (first && h == start.getHour()) ? start.getMinute() : 0;
                    for (int m = m0; m < 60; m++) {
                        if (minutes[m]) {
                            LocalDateTime r = d.atTime(h, m);
                            if (r.isAfter(after.plusYears(8))) {
                                throw new IllegalStateException("no match within 8 years");
                            }
                            return r;
                        }
                    }
                }
            }
            d = d.plusDays(1);
            first = false;
        }
        throw new IllegalStateException("no match within 8 years");
    }
}
