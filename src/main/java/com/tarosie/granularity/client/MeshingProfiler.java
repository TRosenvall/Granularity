package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what chunk meshing actually spends on composition, when asked to.
 *
 * <p>Off unless {@code -Dgranularity.profileMeshing=true}; the flag is wired to
 * {@code ./gradlew runClient -PprofileMeshing}. The checks are a static boolean the JIT folds away,
 * so a normal run pays nothing.
 *
 * <p>This exists because the interesting number cannot be reached from a unit test. A benchmark can
 * time one derivation; only real terrain can say what fraction of blocks in a section are exposed
 * enough to need one, and that fraction is the whole point of the occlusion skip.
 */
final class MeshingProfiler {

    static final boolean ENABLED = Boolean.getBoolean("granularity.profileMeshing");

    /** Report roughly every few section rebuilds' worth of blocks. */
    private static final long REPORT_EVERY = 60_000L;

    private static final AtomicLong seen = new AtomicLong();
    private static final AtomicLong derived = new AtomicLong();
    private static final AtomicLong nanos = new AtomicLong();
    private static final AtomicLong nextReport = new AtomicLong(REPORT_EVERY);

    // Only ever touched inside the CAS-guarded report, so plain fields are safe here.
    private static long lastSeen;
    private static long lastDerived;
    private static long lastNanos;

    private MeshingProfiler() {
    }

    static void recordSkipped() {
        seen.incrementAndGet();
        maybeReport();
    }

    static void recordDerived(long elapsedNanos) {
        seen.incrementAndGet();
        derived.incrementAndGet();
        nanos.addAndGet(elapsedNanos);
        maybeReport();
    }

    private static void maybeReport() {
        long total = seen.get();
        long threshold = nextReport.get();
        if (total < threshold || !nextReport.compareAndSet(threshold, threshold + REPORT_EVERY)) {
            return;
        }

        // Windowed, not cumulative. A running average is dominated for a long time by the first
        // few thousand derivations, which run interpreted before the JIT gets to them -- the
        // cumulative figure read 123 us/derivation while the marginal rate was already under 8.
        // Steady state is what decides whether rendering stutters, so that is what is reported.
        long derivedNow = derived.get();
        long nanosNow = nanos.get();
        long windowBlocks = total - lastSeen;
        long windowDerived = derivedNow - lastDerived;
        long windowNanos = nanosNow - lastNanos;
        lastSeen = total;
        lastDerived = derivedNow;
        lastNanos = nanosNow;

        if (windowBlocks <= 0) {
            return;
        }
        double exposedFraction = windowDerived / (double) windowBlocks;
        double nsPerDerivation = windowDerived == 0 ? 0 : windowNanos / (double) windowDerived;
        double msPerSection = (windowNanos / (double) windowBlocks) * 4096 / 1_000_000.0;

        Granularity.LOGGER.info(String.format(Locale.ROOT,
                "meshing window: %d blocks, %.1f%% needed a composition, %.0f ns each, "
                        + "%.2f ms per 4096-block section",
                windowBlocks, exposedFraction * 100.0, nsPerDerivation, msPerSection));
    }
}
