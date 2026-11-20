package bench.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/** Five-field cron expression with classic day-of-month / day-of-week semantics. */
public final class CronSchedule {
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet doms;
    private final BitSet months;
    private final BitSet dows; // 0 = Sunday .. 6 = Saturday
    private final boolean domStar;
    private final boolean dowStar;

    private CronSchedule(BitSet minutes, BitSet hours, BitSet doms, BitSet months, BitSet dows,
            boolean domStar, boolean dowStar) {
        this.minutes = minutes;
        this.hours = hours;
        this.doms = doms;
        this.months = months;
        this.dows = dows;
        this.domStar = domStar;
        this.dowStar = dowStar;
    }

    public static CronSchedule parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("null expression");
        }
        String s = expression.strip();
        String[] f = s.split(" +", -1);
        if (s.isEmpty() || f.length != 5) {
            throw new IllegalArgumentException("expected 5 fields");
        }
        BitSet dow = field(f[4], 0, 7);
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        return new CronSchedule(field(f[0], 0, 59), field(f[1], 0, 23), field(f[2], 1, 31),
                field(f[3], 1, 12), dow, f[2].equals("*"), f[4].equals("*"));
    }

    private static BitSet field(String text, int min, int max) {
        BitSet set = new BitSet();
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
                if (step < 1) {
                    throw new IllegalArgumentException("step must be >= 1");
                }
                hasStep = true;
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
                    throw new IllegalArgumentException("reversed range");
                }
            } else {
                lo = num(range);
                hi = hasStep ? max : lo;
            }
            if (lo < min || hi > max) {
                throw new IllegalArgumentException("value out of range");
            }
            for (int v = lo; v <= hi; v += step) {
                set.set(v);
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

    private boolean dayMatches(LocalDate d) {
        if (!months.get(d.getMonthValue())) {
            return false;
        }
        boolean domOk = doms.get(d.getDayOfMonth());
        boolean dowOk = dows.get(d.getDayOfWeek().getValue() % 7);
        if (!domStar && !dowStar) {
            return domOk || dowOk;
        }
        if (!domStar) {
            return domOk;
        }
        if (!dowStar) {
            return dowOk;
        }
        return true;
    }

    public LocalDateTime next(LocalDateTime after) {
        if (after == null) {
            throw new IllegalArgumentException("null after");
        }
        LocalDateTime start = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate limit = after.toLocalDate().plusYears(8);
        LocalDate day = start.toLocalDate();
        boolean first = true;
        while (!day.isAfter(limit.plusDays(1))) {
            if (dayMatches(day)) {
                int h0 = first ? start.getHour() : 0;
                for (int h = hours.nextSetBit(h0); h >= 0 && h <= 23; h = hours.nextSetBit(h + 1)) {
                    int m0 = (first && h == start.getHour()) ? start.getMinute() : 0;
                    int m = minutes.nextSetBit(m0);
                    if (m >= 0) {
                        LocalDateTime r = day.atTime(h, m);
                        if (r.isAfter(after.plusYears(8))) {
                            break;
                        }
                        return r;
                    }
                }
            }
            first = false;
            day = day.plusDays(1);
        }
        throw new IllegalStateException("no matching time within 8 years");
    }
}
