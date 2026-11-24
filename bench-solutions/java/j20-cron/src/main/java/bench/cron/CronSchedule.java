package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Five-field cron schedule with the classic day-of-month / day-of-week rule. */
public final class CronSchedule {
    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] doms;
    private final boolean[] months;
    private final boolean[] dows; // index 0..6, Sunday = 0
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
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty expression");
        }
        String[] f = trimmed.split(" +");
        if (f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        boolean[] dow7 = field(f[4], 0, 7);
        boolean[] dow = new boolean[7];
        for (int i = 0; i < 7; i++) {
            dow[i] = dow7[i];
        }
        dow[0] = dow7[0] || dow7[7];
        return new CronSchedule(field(f[0], 0, 59), field(f[1], 0, 23), field(f[2], 1, 31),
                field(f[3], 1, 12), dow, !f[2].equals("*"), !f[4].equals("*"));
    }

    private static int num(String s, int min, int max) {
        if (s.isEmpty() || s.length() > 4) {
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

    private static boolean[] field(String text, int min, int max) {
        boolean[] set = new boolean[max + 1];
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
                if (st.isEmpty() || st.length() > 4) {
                    throw new IllegalArgumentException("bad step");
                }
                step = num(st, 0, 9999);
                if (step < 1) {
                    throw new IllegalArgumentException("step < 1");
                }
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
                        throw new IllegalArgumentException("range a > b");
                    }
                } else {
                    lo = num(base, min, max);
                    hi = hasStep ? max : lo;
                }
            }
            for (int v = lo; v <= hi; v += step) {
                set[v] = true;
            }
        }
        return set;
    }

    private boolean dayMatches(LocalDate d) {
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

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("null after");
        }
        LocalDateTime start = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDateTime deadline = after.plusYears(8);
        LocalDate d = start.toLocalDate();
        LocalDate last = deadline.toLocalDate();
        boolean first = true;
        while (!d.isAfter(last)) {
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
                            if (r.isAfter(deadline)) {
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
