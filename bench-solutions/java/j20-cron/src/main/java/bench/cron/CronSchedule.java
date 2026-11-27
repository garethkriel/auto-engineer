package bench.cron;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Five-field cron schedule with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] doms;
    private final boolean[] months;
    private final boolean[] dows; // 0..6, Sunday = 0
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(boolean[] minutes, boolean[] hours, boolean[] doms, boolean[] months,
                         boolean[] dows, boolean domRestricted, boolean dowRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.doms = doms;
        this.months = months;
        this.dows = dows;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    public static CronSchedule parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        String trimmed = expression.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        String[] f = trimmed.split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        boolean[] min = field(f[0], 0, 59, 60);
        boolean[] hr = field(f[1], 0, 23, 24);
        boolean[] dom = field(f[2], 1, 31, 32);
        boolean[] mon = field(f[3], 1, 12, 13);
        boolean[] dowRaw = field(f[4], 0, 7, 8);
        boolean[] dow = new boolean[7];
        for (int i = 0; i < 8; i++) {
            if (dowRaw[i]) {
                dow[i % 7] = true;
            }
        }
        return new CronSchedule(min, hr, dom, mon, dow, !f[2].equals("*"), !f[4].equals("*"));
    }

    private static boolean[] field(String text, int lo, int hi, int size) {
        boolean[] set = new boolean[size];
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty field");
        }
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item");
            }
            String rangePart = item;
            int step = 1;
            boolean hasStep = false;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                rangePart = item.substring(0, slash);
                step = num(item.substring(slash + 1));
                hasStep = true;
                if (step < 1) {
                    throw new IllegalArgumentException("step must be >= 1");
                }
            }
            int a;
            int b;
            if (rangePart.equals("*")) {
                a = lo;
                b = hi;
            } else {
                int dash = rangePart.indexOf('-');
                if (dash >= 0) {
                    a = num(rangePart.substring(0, dash));
                    b = num(rangePart.substring(dash + 1));
                    if (a > b) {
                        throw new IllegalArgumentException("range start > end");
                    }
                } else {
                    a = num(rangePart);
                    b = hasStep ? hi : a;
                }
            }
            if (a < lo || b > hi || a > hi || b < lo) {
                throw new IllegalArgumentException("value out of range");
            }
            for (int v = a; v <= b; v += step) {
                set[v] = true;
            }
        }
        return set;
    }

    private static int num(String t) {
        if (t.isEmpty() || t.length() > 6) {
            throw new IllegalArgumentException("bad number");
        }
        int v = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad number");
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private boolean dayMatches(LocalDateTime t) {
        boolean d = doms[t.getDayOfMonth()];
        boolean w = dows[t.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) {
            return d || w;
        }
        if (domRestricted) {
            return d;
        }
        if (dowRestricted) {
            return w;
        }
        return true;
    }

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("null after");
        }
        LocalDateTime limit = after.plusYears(8);
        LocalDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        while (!t.isAfter(limit)) {
            if (!months[t.getMonthValue()]) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
                continue;
            }
            if (!dayMatches(t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            if (!hours[t.getHour()]) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                continue;
            }
            if (!minutes[t.getMinute()]) {
                t = t.plusMinutes(1);
                continue;
            }
            return t;
        }
        throw new IllegalStateException("no matching time within 8 years");
    }
}
