package com.tarosie.granularity.core;

/**
 * The second axis: which rock family a region belongs to.
 *
 * <p>Orthogonal to the grain roster and mediating it — a family decides which stones the ground is
 * made of and which minerals it can hold. Sedimentary country admits no precious ore and no gems at
 * all, so finding igneous or metamorphic ground is worth travelling for.
 *
 * <p>Membership lives in {@link Grains}, not here: a grain declares which families it occurs in, and
 * {@link Grains#admitted} inverts that. This enum is only the families themselves and how much of
 * the world each covers, which is what keeps the roster extensible — a mod adding an ore names its
 * families and needs to touch nothing else.
 *
 * <h2>The weights</h2>
 * Sedimentary rock covers roughly three quarters of Earth's land surface while making up under a
 * tenth of the crust by volume — a thin veneer over an igneous and metamorphic basement. These lean
 * on the areal figures, pulled down from ~73% so the world does not read as overwhelmingly warm.
 *
 * <p><b>Known simplification.</b> Family is a property of the region, a 2D field per design §4's
 * bedrock-as-map. Real geology stacks these vertically. A depth axis would be more honest and is not
 * in the design; noted rather than assumed away.
 */
public enum BedrockType {

    /** Basalt, gabbro, granite, diorite, andesite. Diamond country, and copper. */
    IGNEOUS(25),

    /** Marble, slate, schist, gneiss, quartzite. Where lapis and emerald live. */
    METAMORPHIC(15),

    /** Sandstone, limestone, shale, mudstone, chalk, conglomerate. Coal and zinc; no gems. */
    SEDIMENTARY(60);

    private final int weight;

    BedrockType(int weight) {
        this.weight = weight;
    }

    /** Relative areal share, in arbitrary units. */
    public int weight() {
        return weight;
    }

    private static final BedrockType[] VALUES = values();
    private static final int TOTAL_WEIGHT;

    static {
        int total = 0;
        for (BedrockType type : VALUES) {
            total += type.weight;
        }
        TOTAL_WEIGHT = total;
    }

    /**
     * Picks a family by areal weight from a uniform draw.
     *
     * <p>Weight first, then a stone from that family — never a stone drawn uniformly, which would
     * give every family area in proportion to how many stones it happens to have.
     *
     * @param u uniform in [0, 1)
     */
    public static BedrockType pick(double u) {
        int target = (int) (u * TOTAL_WEIGHT);
        int running = 0;
        for (BedrockType type : VALUES) {
            running += type.weight;
            if (target < running) {
                return type;
            }
        }
        return VALUES[VALUES.length - 1];
    }
}
