package se.julklappstimern;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

record TimeRange(int min, int max) {
    static final int STEP = 15;
    static final int FIRST = 60;
    static final int LAST = 3600;
    static final TimeRange DEFAULT = new TimeRange(900, 1500);

    TimeRange {
        if (min < FIRST || max > LAST || min >= max || min % STEP != 0 || max % STEP != 0) {
            throw new IllegalArgumentException("Invalid duration range");
        }
    }

    int lowerBound(boolean editingMin) {
        return editingMin ? FIRST : min + STEP;
    }

    int upperBound(boolean editingMin) {
        return editingMin ? max - STEP : LAST;
    }

    TimeRange with(boolean editingMin, int value) {
        return editingMin ? new TimeRange(value, max) : new TimeRange(min, value);
    }

    int draw() {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    static String format(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
