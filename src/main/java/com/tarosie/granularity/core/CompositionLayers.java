package com.tarosie.granularity.core;

/**
 * Reduces nine slots to what the renderer draws: one averaged base plus up to six mineral overlays.
 *
 * <h2>The base colour is an average, not a majority</h2>
 * Design §3 does the colour arithmetic explicitly: crafted cobblestone <i>"shows the constituent
 * colours as distinct rocks"</i>, and smelting <i>"averages the colours into a single colour"</i>.
 * Natural stone is never said to show distinct rocks — that is cobblestone's job — so its base tint
 * is the mean of its rock slots.
 *
 * <p>Averaging is what makes the world continuous. A region boundary becomes a gradient rather than
 * a seam: five red and four blue is a single stone in the colour between them, and the block beside
 * it, sampling the field slightly differently, is a shade further along. §4's per-slot jitter
 * already produces the mixture; averaging is what turns a mixture into a gradient.
 *
 * <p>It also makes §4's purity gradient literal. Compositions are "muddier near the surface, purer
 * with depth" — and an average of disagreeing colours <i>is</i> muddier, pulling toward grey,
 * while a pure block at bedrock averages to exactly its own colour. The same arithmetic that
 * smelting uses produces the depth cue for free.
 *
 * <h2>Minerals do not average</h2>
 * Ore, precious ore and gem carry vanilla's iron, copper and diamond specks with their grey matrix
 * subtracted — and unlike rock they are <b>not</b> averaged. Rock averages because it is one
 * continuous surface whose colour shifts with the fields; a mineral is a discrete thing sitting in
 * that surface, and averaging iron with copper would produce a single muddy midpoint that is
 * neither. Each class therefore keeps its two most common colours as separate overlays.
 *
 * <p>There is no rock overlay: the averaged base is precisely what replaces it.
 *
 * <h2>The quantisation §5 budgeted for is not needed</h2>
 * §5 asks for "~5–6 discrete levels", which is right for a continuous quantity. An overlay's
 * strength here is a slot count — an integer from 0 to 9 — so the step <i>is</i> the count and
 * nothing is rounded away.
 *
 * <p>Tints are stored resolved rather than as grain ids, because an average is not a grain. Pure,
 * so the reduction can be tested without a client.
 */
public record CompositionLayers(int baseTint, Family ore, Family precious, Family gem) {

    /** One overlay: the resolved tint, and how many of the nine slots it covers. */
    public record Overlay(int tint, int count) {
        public boolean isPresent() {
            return count > 0;
        }
    }

    /**
     * One mineral class, keeping its two most common colours.
     *
     * <p>A block can hold iron and copper at once — the mineral fields are independent of the rock
     * and of each other, so an ore-province boundary puts two colours of the same class in one
     * block. Averaging them, as an earlier version did, turned iron-and-copper into a single muddy
     * midpoint and lost the fact that there were two minerals at all.
     *
     * <p>{@code primary} is the larger; {@code secondary} draws on top of it at a slightly greater
     * outset and on the complementary sprite set, so the two occupy different specks rather than
     * one recolouring the other.
     *
     * <p>A third colour in the same class is dropped. Nine slots split three ways is rare, and each
     * additional colour costs a sprite family and a tint index.
     */
    public record Family(Overlay primary, Overlay secondary) {
        public static final Family NONE =
                new Family(new Overlay(0xFFFFFF, 0), new Overlay(0xFFFFFF, 0));

        public boolean isPresent() {
            return primary.isPresent();
        }

        public int total() {
            return primary.count() + secondary.count();
        }
    }

    /** An ore family can fill every slot, so each needs a sprite per count. */
    public static final int MAX_ORE = Composition.SLOTS;

    /** Untinted white — a multiply by this leaves the greyscale sprite as it is. */
    private static final int NO_TINT = 0xFFFFFF;

    public CompositionLayers {
        require(ore, "ore");
        require(precious, "precious ore");
        require(gem, "gem");
    }

    private static void require(Family family, String what) {
        check(family.primary(), what + " primary");
        check(family.secondary(), what + " secondary");
        if (family.secondary().count() > family.primary().count()) {
            throw new IllegalArgumentException(
                    what + " secondary (" + family.secondary().count() + ") exceeds primary ("
                            + family.primary().count() + "); the smaller draws on top");
        }
    }

    private static void check(Overlay overlay, String what) {
        if (overlay.count() < 0 || overlay.count() > MAX_ORE) {
            throw new IllegalArgumentException(
                    what + " count out of range: " + overlay.count() + " (max " + MAX_ORE + ")");
        }
    }

    public static CompositionLayers of(Composition composition) {
        // Nine slots hold at most nine distinct grains, so everything below is bounded by nine
        // regardless of how big the roster has grown. This used to work from grainCounts(), which is
        // as long as the roster and is scanned eight times here — once per average and twice per
        // mineral family. That was a few hundred iterations per block with a fixed roster of three
        // dozen and would have grown without limit once datapacks could add grains. This runs for
        // every block of every chunk section rebuild; see CompositionCostTest.
        int[] ids = new int[Composition.SLOTS];
        int[] counts = new int[Composition.SLOTS];
        int distinct = composition.tally(ids, counts);

        int rockTint = averageTint(ids, counts, distinct, GrainClass.ROCK);
        if (rockTint < 0) {
            // A block with no rock still needs a base to sit on: average whatever it does have.
            rockTint = averageTint(ids, counts, distinct, null);
        }
        int baseTint = rockTint < 0 ? NO_TINT : LatticeColour.rockTint(rockTint);
        // Rock averages -- one continuous stone surface whose colour shifts with the fields.
        // Minerals do not: they are discrete things sitting in it.

        return new CompositionLayers(baseTint,
                family(ids, counts, distinct, GrainClass.ORE),
                family(ids, counts, distinct, GrainClass.PRECIOUS_ORE),
                family(ids, counts, distinct, GrainClass.GEM));
    }

    /**
     * The slot-weighted mean of a class's grain tints, or -1 if it holds none.
     *
     * <p>A null class averages every grain that has a tint, which is the fallback for a block with
     * no rock at all. Design §3's smelt arithmetic, applied to whatever is actually present.
     */
    private static int averageTint(int[] ids, int[] counts, int distinct, GrainClass only) {
        long r = 0;
        long g = 0;
        long b = 0;
        int total = 0;
        for (int i = 0; i < distinct; i++) {
            int n = counts[i];
            Grain grain = Grains.byId(ids[i]);
            if (only != null ? grain.clazz() != only : !grain.clazz().isObtainable()) {
                continue;
            }
            r += (long) ((grain.tint() >> 16) & 0xFF) * n;
            g += (long) ((grain.tint() >> 8) & 0xFF) * n;
            b += (long) (grain.tint() & 0xFF) * n;
            total += n;
        }
        if (total == 0) {
            return -1;
        }
        int half = total / 2;
        return (int) ((((r + half) / total) << 16) | (((g + half) / total) << 8) | ((b + half) / total));
    }

    /** The two most common grains of one mineral class, largest first. */
    private static Family family(int[] ids, int[] counts, int distinct, GrainClass clazz) {
        int first = argmax(ids, counts, distinct, clazz, -1);
        if (first < 0) {
            return Family.NONE;
        }
        int second = argmax(ids, counts, distinct, clazz, first);
        return new Family(
                new Overlay(Grains.byId(ids[first]).mineralTint(), counts[first]),
                second < 0
                        ? new Overlay(NO_TINT, 0)
                        : new Overlay(Grains.byId(ids[second]).mineralTint(), counts[second]));
    }

    /**
     * Index into the tally of the highest-count grain of a class, skipping {@code exclude}.
     *
     * <p>Ties go to the lower grain id, which the tally does not give for free — its entries are in
     * the order the slots happened to mention them. Comparing the ids explicitly is what keeps this
     * answering exactly as the roster-wide ascending scan did, and so keeps the golden file valid.
     */
    private static int argmax(int[] ids, int[] counts, int distinct, GrainClass clazz, int exclude) {
        int best = -1;
        for (int i = 0; i < distinct; i++) {
            if (i == exclude || Grains.byId(ids[i]).clazz() != clazz) {
                continue;
            }
            if (best < 0 || counts[i] > counts[best]
                    || (counts[i] == counts[best] && ids[i] < ids[best])) {
                best = i;
            }
        }
        return best;
    }

    /** True when the block is plain rock with nothing in it — the common case. */
    public boolean isPlainRock() {
        return !ore.isPresent() && !precious.isPresent() && !gem.isPresent();
    }
}
