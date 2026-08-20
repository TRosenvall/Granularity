package com.tarosie.granularity.client;

import com.tarosie.granularity.content.CompositionHolder;
import com.tarosie.granularity.content.Costumes;
import com.tarosie.granularity.content.GranularityComponents;
import com.tarosie.granularity.content.Region;
import net.minecraft.world.item.ItemStack;
import com.tarosie.granularity.content.Dyes;
import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.GrainClass;
import com.tarosie.granularity.core.LatticeColour;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The tints a crafted composite is drawn with.
 *
 * <ul>
 *   <li><b>0</b> — the matrix: the average of everything in the block, muted like natural stone.</li>
 *   <li><b>1–9</b> — the nine stones, each the <i>exact</i> colour of the grain in that slot.</li>
 *   <li><b>30–41</b> — the matrix of a dyed face, two runs of six; see {@link #DYE_BASE}.</li>
 * </ul>
 *
 * <p>Exact rather than muted for the stones on purpose. The whole point of a cobblestone is that you
 * can see what went into it; pulling those toward grey would undo it. The matrix is muted because
 * it is playing the same role stone plays.
 */
public class CompositeBlockColour implements BlockColor {

    private static final int NO_TINT = 0xFFFFFF;

    /** Tint indices at or above this address a double slab's upper half. */
    public static final int UPPER_BASE = 10;

    /**
     * The one tint that is not stone: timber.
     *
     * <p>Set above the double slab's 10–19 so the two ranges cannot meet. A piston's plate is wood,
     * and drawing it from the composition would say a spruce piston built out of slate has a slate
     * plate — which is exactly what it looked like.
     */
    public static final int WOOD_TINT = 20;

    /**
     * The other tint that is not stone: the metal in a block's fittings.
     *
     * <p>A piston's head is a wooden ram held together with metal — brackets at the corners of the
     * plate, corners and mid-braces along the band around its edge. Three materials on one block,
     * so three sources of colour.
     */
    public static final int METAL_TINT = 21;

    /**
     * A face that is a real face but takes no colour from the block: the furnace door.
     *
     * <p>It resolves to no tint at all, so the sprite draws exactly as it was made. The index exists
     * only so the face is <i>addressable</i> — {@code OverlayBakedModel} finds a block's surface by
     * tint index, and a face carrying none is invisible to it. Without this, moss could never grow
     * over a furnace door however much of the rest of the block it covered.
     */
    public static final int PLAIN_TINT = 22;

    /**
     * The matrix of a dyed face: 30–35, one per {@link Direction#get3DDataValue()}.
     *
     * <p>No quad is baked with these. {@link OverlayBakedModel} rewrites a matrix quad into one of
     * them when the face it sits on has been dyed, which is how a per-face colour reaches an API that
     * has no face in it. See that class for why this is the route rather than baking the indices into
     * the models or the colour into the vertices.
     */
    public static final int DYE_BASE = 30;

    /** The same six for a double slab's upper half: 36–41. */
    public static final int UPPER_DYE_BASE = 36;

    /** What a block with no metal recorded is drawn as: iron's own map colour. */
    private static final int DEFAULT_METAL = 0x9C9D97;

    /**
     * What a block with no timber recorded is drawn as: oak's own map colour, to the digit.
     *
     * <p>Reached by a piston from before this existed, or one handed out by a command. Oak rather
     * than grey, because a piston with no plank recorded is still a piston with a wooden plate.
     */
    private static final int DEFAULT_WOOD = 0x8F7748;

    @Override
    public int getColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level == null || pos == null) {
            return NO_TINT;
        }
        if (!(level.getBlockEntity(pos) instanceof CompositionHolder composite)) {
            // Nothing there — which is the normal state of affairs for a block a piston is moving.
            // It was replaced by MovingPistonBlock and is being drawn from its old position, so the
            // colours come from what MovingComposites kept. Without this the block draws in bare
            // greyscale for the two ticks of the stroke, which is most of what "the flash" was.
            return moving(MovingComposites.at(pos), tintIndex);
        }
        // A costume answers for whichever part of the block it is on, and which way it answers depends
        // on where that part's donor keeps its appearance. See Costumes for the two kinds.
        Costumes worn = composite.costumes();
        if (!worn.isEmpty()) {
            Integer dressed = costumed(worn, tintIndex);
            if (dressed != null) {
                return dressed;
            }
        }
        if (tintIndex == WOOD_TINT) {
            return woodTint(composite.wood());
        }
        if (tintIndex == METAL_TINT) {
            return metalTint(composite.metal());
        }
        if (tintIndex == PLAIN_TINT) {
            return NO_TINT;
        }
        if (tintIndex >= DYE_BASE) {
            return dyeTint(tintIndex, composite.dyes(), composite.upperDyes(),
                    composite.composition(), composite.upper());
        }
        if (tintIndex >= UPPER_BASE) {
            // A double slab renders its top half from tint indices 10-19 so the two halves can
            // differ; falling back to the lower composition keeps an unmixed double looking right.
            Composition upper = composite.upper();
            return tintFor(upper != null ? upper : composite.composition(), null,
                    tintIndex - UPPER_BASE);
        }
        return tintFor(composite.composition(), null, tintIndex);
    }

    /**
     * The colour of a part wearing a costume, or null where nothing is worn there.
     *
     * <p>Two answers, for the two kinds of donor:
     *
     * <ul>
     *   <li>A <b>foreign</b> donor keeps its appearance in a texture, which {@link TransmogBakedModel}
     *       has already lent to the quad. It must then be drawn in the donor's own colours, so the
     *       part takes no tint at all — the two halves have to agree or a sandstone costume comes out
     *       slate-coloured.</li>
     *   <li>One of <b>ours</b> keeps its appearance in nine grains, so the part is drawn as stone and
     *       coloured from the donor's composition rather than from this block's.</li>
     * </ul>
     *
     * <p>Tint indices at or above {@link Region#COSTUME_BASE} are quads the wrapper synthesised for a
     * part that is not stone — a piston's plate turned to rock — and they carry which region they came
     * from, so two parts wearing two different stones stay distinguishable here.
     */
    @Nullable
    private static Integer costumed(Costumes worn, int tintIndex) {
        Region synthesised = tintIndex >= Region.COSTUME_BASE ? Region.ofCostumeTint(tintIndex) : null;
        if (synthesised != null) {
            Costumes.Dressed part = worn.on(synthesised);
            int layer = Region.costumeLayer(tintIndex);
            Integer painted = paintOf(part.colorant());
            if (painted != null) {
                return layer == 0 ? LatticeColour.matrixTint(painted) : painted;
            }
            Composition lent = part.textureComposition();
            if (lent != null) {
                return tintFor(lent, null, layer);
            }
            // A masked part keeping its own sprite, wearing a block that could not lend a texture to
            // it. The block still says what colour it is: its map colour, which is the one thing every
            // block in the game has an opinion about.
            Integer flat = flatColour(part);
            return flat == null ? NO_TINT : flat;
        }
        if (tintIndex >= DYE_BASE) {
            // Dye is painted over a costume rather than hidden by it, so a dressed block can still be
            // a red one. Left to the ordinary path.
            return null;
        }
        if (tintIndex == PLAIN_TINT) {
            // A face is never touched at all — not its texture and not its colour. A furnace door and
            // an observer's eye are how you read the block, and a costume that changed them would be
            // hiding the one thing the block has to keep telling you.
            return null;
        }
        Costumes.Dressed dressed = worn.covering(tintIndex);
        if (dressed.isEmpty()) {
            return null;
        }
        Integer paint = paintOf(dressed.colorant());
        if (tintIndex == METAL_TINT || tintIndex == WOOD_TINT) {
            // Fittings and timber are one flat colour, so a colorant is all they need.
            if (paint != null) {
                return paint;
            }
            // A part that takes only a grain — a stonecutter's blade — carries its item in the
            // costume slot, and paints with it.
            Integer own = paintOf(dressed.costume());
            if (own != null) {
                return own;
            }
            // A log is drawn with our greyscale wood, so it takes the colour of the timber it is.
            if (dressed.isLog()) {
                return woodTint(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(dressed.costume().getItem()));
            }
            // A block was lent instead, so its own sprite is on this quad and it must be drawn in its
            // own colours. Falling through here multiplied a dark oak frame by the stonecutter's plank
            // colour and produced mud — the frame looked broken for exactly this reason.
            return dressed.costume().isEmpty() ? null : NO_TINT;
        }
        int layer = tintIndex >= UPPER_BASE && tintIndex < WOOD_TINT
                ? tintIndex - UPPER_BASE
                : tintIndex;
        if (paint != null) {
            // The colorant repaints every stone of the part, so the costume's pattern survives and
            // only its colour goes — a cobble texture in slate. The matrix is muted the way an
            // average always is here; the grains are exact. See tintFor.
            return layer == 0 ? LatticeColour.matrixTint(paint) : paint;
        }
        if (dressed.isLog()) {
            // Our greyscale wood is on the quad wherever a log was lent, stone parts included, so it
            // has to be told what colour of timber it is.
            return woodTint(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(dressed.costume().getItem()));
        }
        Composition lent = dressed.textureComposition();
        if (lent == null) {
            // A foreign block with no colorant: TransmogBakedModel has lent its texture and it must be
            // drawn in its own colours, or a sandstone costume comes out slate-coloured.
            return NO_TINT;
        }
        return tintFor(lent, null, layer);
    }

    /**
     * The one colour a part can be given when its texture cannot be replaced.
     *
     * <p>Falls back through everything that has an opinion: the colorant, then a grain worn as a
     * costume, then — for an ordinary block — its own {@code MapColor}, which is the colour Mojang
     * already chose to stand for that block and is defined for every block in the game, ours and
     * anyone else's.
     */
    @Nullable
    private static Integer flatColour(Costumes.Dressed part) {
        Integer paint = paintOf(part.colorant());
        if (paint != null) {
            return paint;
        }
        Integer own = paintOf(part.costume());
        if (own != null) {
            return own;
        }
        return part.costume().isEmpty()
                ? null
                : woodTint(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(part.costume().getItem()));
    }

    /**
     * The colour a grain item paints with, or null if it is not a colorant.
     *
     * <p>An <b>ingot is not the same colour as its ore</b>, and both are valid here: raw iron is the
     * pink of ore, an iron ingot is bright grey. So the item is asked for its own colour rather than
     * for its material's — the roster's tint if the item is a grain's own, and otherwise the colour of
     * the block it would be cast into, which is the same {@code x_ingot -> x_block} substitution the
     * recipe-built fittings already go through.
     */
    @Nullable
    private static Integer paintOf(ItemStack stack) {
        if (Costumes.grainOf(stack) == null) {
            return null;
        }
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        com.tarosie.granularity.core.Grain own = com.tarosie.granularity.core.Grains.byItem(id.toString());
        return own != null ? own.tint() : metalTint(id);
    }

    /**
     * The colour of one dyed face.
     *
     * <p>Falls back to the average when the face turns out not to be dyed after all. That should not
     * happen — nothing emits these indices except the wrapper, and only for dyed faces — but a mesh
     * built a moment before the dye was scraped is exactly the kind of thing that outlives its data,
     * and answering with the undyed colour is the correct thing to draw in that instant anyway.
     */
    private static int dyeTint(int tintIndex, Dyes dyes, Dyes upperDyes, Composition composition,
                               @Nullable Composition upperComposition) {
        boolean upper = tintIndex >= UPPER_DYE_BASE;
        int face = tintIndex - (upper ? UPPER_DYE_BASE : DYE_BASE);
        if (face < 0 || face > 5) {
            return NO_TINT;
        }
        Integer colour = (upper ? upperDyes : dyes).on(Direction.from3DDataValue(face));
        Composition half = upper && upperComposition != null ? upperComposition : composition;
        return tintFor(half, colour, 0);
    }

    /** The same resolution as above, against a composition that is no longer in the world. */
    private static int moving(@Nullable MovingComposites.Remembered kept, int tintIndex) {
        if (kept == null) {
            return NO_TINT;
        }
        if (tintIndex == WOOD_TINT) {
            return woodTint(kept.wood());
        }
        if (tintIndex == METAL_TINT) {
            return metalTint(kept.metal());
        }
        if (tintIndex == PLAIN_TINT) {
            return NO_TINT;
        }
        if (tintIndex >= DYE_BASE) {
            return dyeTint(tintIndex, kept.dyes(), kept.upperDyes(), kept.composition(), kept.upper());
        }
        if (tintIndex >= UPPER_BASE) {
            Composition upper = kept.upper();
            return tintFor(upper != null ? upper : kept.composition(), null, tintIndex - UPPER_BASE);
        }
        return tintFor(kept.composition(), null, tintIndex);
    }

    /**
     * The colour of a metal, for tinting a block's fittings.
     *
     * <p>An ingot has no colour of its own to read — {@code MapColor} belongs to blocks — so this
     * asks the block you would cast it into. {@code iron_ingot} becomes {@code iron_block} and
     * answers grey; gold answers gold, copper orange, netherite near-black. Substituting by name is
     * not elegant, but {@code x_ingot} / {@code x_block} is a convention essentially every mod
     * follows, and an id that already names a block is looked up unchanged.
     */
    public static int metalTint(@Nullable net.minecraft.resources.ResourceLocation metal) {
        if (metal == null) {
            return DEFAULT_METAL;
        }
        String path = metal.getPath();
        if (path.endsWith("_ingot")) {
            path = path.substring(0, path.length() - "_ingot".length()) + "_block";
        }
        net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        metal.getNamespace(), path));
        int colour = block == net.minecraft.world.level.block.Blocks.AIR
                ? 0
                : block.defaultMapColor().col;
        if (colour != 0) {
            return colour;
        }
        // No block to ask, which is every metal we added ourselves: silver and zinc have bars but
        // no block to cast them into. The roster knows what the material looks like, so ask it by
        // name — `silver_ingot` is the silver grain's metal. Any metal added to Grains gets a
        // colour here without needing a block first.
        //
        // `find`, not `byName`: byName throws on an unknown name while this code tests for null, so
        // an ingot from a mod with neither a block nor a grain — which is most of them — crashed the
        // renderer instead of falling back to grey. Asked in the ingot's own namespace, so another
        // mod's metal resolves against that mod's grain rather than against ours.
        String bare = path.endsWith("_block")
                ? path.substring(0, path.length() - "_block".length())
                : path;
        com.tarosie.granularity.core.Grain grain =
                com.tarosie.granularity.core.Grains.find(metal.getNamespace() + ":" + bare);
        return grain == null ? DEFAULT_METAL : grain.tint();
    }

    /**
     * The colour of a plank block, for tinting the one part of a block that is not stone.
     *
     * <p>Taken from the block's own {@code MapColor} rather than from a table of our own. It is the
     * colour Mojang already chose to stand for that wood on a map, it is one lookup with no texture
     * sampling, and — the reason that decides it — a wood from another mod answers correctly without
     * Granularity having heard of it.
     */
    public static int woodTint(@Nullable net.minecraft.resources.ResourceLocation wood) {
        if (wood == null) {
            return DEFAULT_WOOD;
        }
        net.minecraft.world.level.block.Block block =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(wood);
        if (block == net.minecraft.world.level.block.Blocks.AIR) {
            return DEFAULT_WOOD;
        }
        int colour = block.defaultMapColor().col;
        return colour == 0 ? DEFAULT_WOOD : colour;
    }

    /**
     * Shared with the item renderer, which reads the same composition off a stack.
     *
     * @param dye the colour this face has been dyed, or null for the average of the grains. Only
     *            consulted at tint index 0 — dye is a mortar colouring and never touches a stone.
     */
    public static int tintFor(Composition composition, @Nullable Integer dye, int tintIndex) {
        if (tintIndex == 0) {
            // A dyed matrix wins over the average: the player asked for a colour outright.
            if (dye != null) {
                return dye;
            }
            int average = composition.averageTint(null);
            return average < 0 ? NO_TINT : LatticeColour.matrixTint(average);
        }
        int slot = tintIndex - 1;
        if (slot < 0 || slot >= Composition.SLOTS) {
            return NO_TINT;
        }
        // Air and water have no colour worth showing; let the matrix show through instead.
        GrainClass clazz = composition.classAt(slot);
        if (clazz == GrainClass.AIR || clazz == GrainClass.WATER) {
            return NO_TINT;
        }
        return composition.grainAt(slot).tint();
    }
}
