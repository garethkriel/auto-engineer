package bench.cron;

import java.time.LocalDate;
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
    private final int[] minuteList;
    private final int[] hourList;

    private CronSchedule(boolean[] minutes, boolean[] hours, boolean[] doms, boolean[] months,
                         boolean[] dows, boolean domRestricted, boolean dowRestricted) {
        this.minutes = minutes;
        this.hours = hours;
        this.doms = doms;
        this.months = months;
        this.dows = dows;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
        this.minuteList = toList(minutes);
        this.hourList = toList(hours);
    }

    private static int[] toList(boolean[] b) {
        int n = 0;
        for (boolean x : b) {
            if (x) {
                n++;
            }
        }
        int[] r = new int[n];
        int i = 0;
        for (int v = 0; v < b.length; v++) {
            if (b[v]) {
                r[i++] = v;
            }
        }
        return r;
    }

    /** Parses a five-field cron expression. */
    public static CronSchedule parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        String s = expression.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        String[] f = s.split(" +", -1);
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        boolean[] min = parseField(f[0], 0, 59);
        boolean[] hr = parseField(f[1], 0, 23);
        boolean[] dom = parseField(f[2], 1, 31);
        boolean[] mon = parseField(f[3], 1, 12);
        boolean[] dow7 = parseField(f[4], 0, 7);
        boolean[] dow = new boolean[7];
        for (int i = 0; i < 7; i++) {
            dow[i] = dow7[i];
        }
        if (dow7[7]) {
            dow[0] = true;
        }
        return new CronSchedule(min, hr, dom, mon, dow, !f[2].equals("*"), !f[4].equals("*"));
    }

    private static boolean[] parseField(String field, int lo, int hi) {
        boolean[] set = new boolean[hi + 1];
        if (field.isEmpty()) {
            throw new IllegalArgumentException("empty field");
        }
        for (String item : field.split(",", -1)) {
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
                    throw new IllegalArgumentException("step must be >= 1");
                }
            }
            int from;
            int to;
            if (base.equals("*")) {
                from = lo;
                to = hi;
                if (hi == 7 && !hasStep) {
                    to = 6;
                }
            } else {
                int dash = base.indexOf('-');
                if (dash >= 0) {
                    from = num(base.substring(0, dash));
                    to = num(base.substring(dash + 1));
                } else {
                    from = num(base);
                    to = hasStep ? hi : from;
                }
            }
            if (from < lo || from > hi || to < lo || to > hi || from > to) {
                throw new IllegalArgumentException("value out of range: " + item);
            }
            for (int v = from; v <= to; v += step) {
                set[v] = true;
            }
        }
        return set;
    }

    private static int num(String t) {
        if (t.isEmpty() || t.length() > 6) {
            throw new IllegalArgumentException("bad number: '" + t + "'");
        }
        int v = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad number: '" + t + "'");
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private boolean dayMatches(LocalDate d) {
        if (!months[d.getMonthValue()]) {
            return false;
        }
        boolean domOk = doms[d.getDayOfMonth()];
        boolean dowOk = dows[d.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) {
            return domOk || dowOk;
        }
        if (domRestricted) {
            return domOk;
        }
        if (dowRestricted) {
            return dowOk;
        }
        return true;
    }

    /** Returns the earliest matching minute strictly after {@code after}. */
    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("after is null");
        }
        LocalDateTime start = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate limit = after.toLocalDate().plusYears(8);
        LocalDate day = start.toLocalDate();
        boolean first = true;
        while (!day.isAfter(limit)) {
            if (!months[day.getMonthValue()]) {
                day = day.withDayOfMonth(1).plusMonths(1);
                first = false;
                continue;
            }
            if (dayMatches(day)) {
                int h0 = 0;
                int m0 = 0;
                if (first) {
                    h0 = start.getHour();
                    m0 = start.getMinute();
                }
                for (int h : hourList) {
                    if (h < h0) {
                        continue;
                    }
                    for (int m : minuteList) {
                        if (h == h0 && m < m0) {
                            continue;
                        }
                        return day.atTime(h, m);
                    }
                }
            }
            day = day.plusDays(1);
            first = false;
        }
        throw new IllegalStateException("no matching time within 8 years");
    }
}
