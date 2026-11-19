package bench.cron;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/** Five-field cron schedule with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays; // 0 = Sunday
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(BitSet mi, BitSet h, BitSet d, BitSet mo, BitSet w, boolean dr, boolean wr) {
        minutes = mi;
        hours = h;
        days = d;
        months = mo;
        weekdays = w;
        domRestricted = dr;
        dowRestricted = wr;
    }

    public static CronSchedule parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        String[] f = expression.strip().split(" +");
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        BitSet wd = field(f[4], 0, 7);
        if (wd.get(7)) {
            wd.set(0);
            wd.clear(7);
        }
        return new CronSchedule(field(f[0], 0, 59), field(f[1], 0, 23), field(f[2], 1, 31),
                field(f[3], 1, 12), wd, !f[2].equals("*"), !f[4].equals("*"));
    }

    private static int num(String s, int min, int max) {
        if (s.isEmpty() || s.length() > 9) {
            throw new IllegalArgumentException("bad number: " + s);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad number: " + s);
            }
        }
        int v = Integer.parseInt(s);
        if (v < min || v > max) {
            throw new IllegalArgumentException("out of range: " + s);
        }
        return v;
    }

    private static BitSet field(String text, int min, int max) {
        BitSet bits = new BitSet();
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item");
            }
            String base = item;
            int step = 1;
            boolean hasStep = false;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                base = item.substring(0, slash);
                String st = item.substring(slash + 1);
                step = num(st, 1, Integer.MAX_VALUE);
                hasStep = true;
            }
            int lo;
            int hi;
            if (base.equals("*")) {
                lo = min;
                hi = max;
            } else {
                int dash = base.indexOf('-');
                if (dash >= 0) {
                    lo = num(base.substring(0, dash), min, max);
                    hi = num(base.substring(dash + 1), min, max);
                    if (lo > hi) {
                        throw new IllegalArgumentException("range reversed");
                    }
                } else {
                    lo = num(base, min, max);
                    hi = hasStep ? max : lo;
                }
            }
            for (int v = lo; v <= hi; v += step) {
                bits.set(v);
                if (v > Integer.MAX_VALUE - step) {
                    break;
                }
            }
        }
        return bits;
    }

    private boolean dayMatches(LocalDateTime t) {
        boolean dom = days.get(t.getDayOfMonth());
        boolean dow = weekdays.get(t.getDayOfWeek().getValue() % 7);
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
            if (!months.get(t.getMonthValue())) {
                t = t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1);
            } else if (!dayMatches(t)) {
                t = t.truncatedTo(ChronoUnit.DAYS).plusDays(1);
            } else if (!hours.get(t.getHour())) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            } else if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1);
            } else {
                return t;
            }
        }
        throw new IllegalStateException("no matching time within 8 years");
    }
}
