package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** Five-field cron expression with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private static final Pattern NUM = Pattern.compile("\\d{1,9}");

    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] doms;
    private final boolean[] months;
    private final boolean[] dows; // index 0..6, Sunday = 0
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(String[] f) {
        minutes = field(f[0], 0, 59, 60);
        hours = field(f[1], 0, 23, 24);
        doms = field(f[2], 1, 31, 32);
        months = field(f[3], 1, 12, 13);
        boolean[] raw = field(f[4], 0, 7, 8);
        dows = new boolean[7];
        for (int i = 0; i < 8; i++) {
            if (raw[i]) {
                dows[i % 7] = true;
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
        String[] f = t.split("\\s+");
        if (t.isEmpty() || f.length != 5) {
            throw new IllegalArgumentException("expected five fields");
        }
        return new CronSchedule(f);
    }

    private static int num(String s, int min, int max) {
        if (!NUM.matcher(s).matches()) {
            throw new IllegalArgumentException("bad number: " + s);
        }
        int v = Integer.parseInt(s);
        if (v < min || v > max) {
            throw new IllegalArgumentException("out of range: " + s);
        }
        return v;
    }

    private static boolean[] field(String spec, int min, int max, int size) {
        boolean[] set = new boolean[size];
        for (String item : spec.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item");
            }
            String base = item;
            int step = 0;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                base = item.substring(0, slash);
                String st = item.substring(slash + 1);
                if (!NUM.matcher(st).matches()) {
                    throw new IllegalArgumentException("bad step");
                }
                step = Integer.parseInt(st);
                if (step < 1) {
                    throw new IllegalArgumentException("step < 1");
                }
            }
            int a;
            int b;
            if (base.equals("*")) {
                a = min;
                b = max;
            } else {
                int dash = base.indexOf('-');
                if (dash >= 0) {
                    a = num(base.substring(0, dash), min, max);
                    b = num(base.substring(dash + 1), min, max);
                    if (a > b) {
                        throw new IllegalArgumentException("a > b");
                    }
                } else {
                    a = num(base, min, max);
                    b = step > 0 ? max : a;
                }
            }
            for (int v = a; v <= b; v += step > 0 ? step : 1) {
                set[v] = true;
            }
        }
        return set;
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
        LocalDateTime limit = after.plusYears(8);
        LocalDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        while (!t.isAfter(limit)) {
            if (!months[t.getMonthValue()]) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
            } else if (!dayMatches(t.toLocalDate())) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
            } else if (!hours[t.getHour()]) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            } else if (!minutes[t.getMinute()]) {
                t = t.plusMinutes(1);
            } else {
                return t;
            }
        }
        throw new IllegalStateException("no match within 8 years");
    }
}
