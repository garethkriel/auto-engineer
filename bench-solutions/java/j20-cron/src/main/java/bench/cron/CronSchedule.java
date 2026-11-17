package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/** Five-field cron expression with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] doms;
    private final boolean[] months;
    private final boolean[] dows; // index 0 = Sunday
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(boolean[] minutes, boolean[] hours, boolean[] doms, boolean[] months, boolean[] dows,
                         boolean domRestricted, boolean dowRestricted) {
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
        String[] f = expression.strip().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        boolean[] dow7 = field(f[4], 0, 7);
        boolean[] dow = new boolean[7];
        for (int i = 0; i <= 7; i++) {
            if (dow7[i]) {
                dow[i % 7] = true;
            }
        }
        return new CronSchedule(field(f[0], 0, 59), field(f[1], 0, 23), field(f[2], 1, 31), field(f[3], 1, 12),
                dow, !f[2].equals("*"), !f[4].equals("*"));
    }

    private static boolean[] field(String text, int min, int max) {
        boolean[] set = new boolean[max + 1];
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item");
            }
            String range = item;
            int step = 1;
            boolean hasStep = false;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                range = item.substring(0, slash);
                step = num(item.substring(slash + 1));
                hasStep = true;
                if (step < 1) {
                    throw new IllegalArgumentException("step must be >= 1");
                }
            }
            int lo;
            int hi;
            if (range.equals("*")) {
                lo = min;
                hi = max;
            } else if (range.indexOf('-') >= 0) {
                int dash = range.indexOf('-');
                lo = num(range.substring(0, dash));
                hi = num(range.substring(dash + 1));
                if (lo > hi) {
                    throw new IllegalArgumentException("range start > end");
                }
            } else {
                lo = num(range);
                hi = hasStep ? max : lo;
            }
            if (lo < min || hi > max) {
                throw new IllegalArgumentException("value out of range");
            }
            for (int v = lo; v <= hi; v += step) {
                set[v] = true;
            }
        }
        return set;
    }

    private static int num(String s) {
        if (s.isEmpty() || s.length() > 4) {
            throw new IllegalArgumentException("bad number: " + s);
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad number: " + s);
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private boolean dayMatches(LocalDate d) {
        boolean dom = doms[d.getDayOfMonth()];
        boolean dow = dows[d.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) {
            return dom || dow;
        }
        if (domRestricted) {
            return dom;
        }
        if (dowRestricted) {
            return dow;
        }
        return true;
    }

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("null after");
        }
        LocalDateTime start = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate limit = after.toLocalDate().plusYears(8);
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
                    int m0 = first && h == start.getHour() ? start.getMinute() : 0;
                    for (int m = m0; m < 60; m++) {
                        if (minutes[m]) {
                            return LocalDateTime.of(d, LocalTime.of(h, m));
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
