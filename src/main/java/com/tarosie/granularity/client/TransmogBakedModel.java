package com.tarosie.granularity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a block wearing another block's face — transmogrification.
 *
 * <p>The mod's founding rule is that a block shows what it is made of. This suspends that on purpose,
 * so a wall of blast-resistant stone can look like cobblestone. What keeps it honest is where the
 * costume is kept: on the block entity and nowhere else, so it never reaches the item, and breaking
 * the block hands the donor back and reveals what was underneath all along.
 *
 * <h2>It is a sprite swap, not a model swap</h2>
 * Borrowing the donor's <i>model</i> would make a transmogged slab a full cube. So the geometry stays
 * ours and only the texture is taken — the same {@link OverlayBakedModel#retexture} the finishes and
 * overlays already run on. That is also what makes "visual only" structural rather than a promise:
 * nothing here can reach a blockstate, a block entity's composition, or a drop.
 *
 * <h2>Grain layers go, exactly as a finish takes them</h2>
 * A costumed block must not show its nine stones through the disguise, so only the base layer survives
 * and wears the donor's sprite. This is {@code FinishBakedModel.worked} with a different source for
 * the texture, and for the same reason.
 *
 * <h2>The other half is in the colour handler</h2>
 * A borrowed sprite has to be drawn in the donor's own colours, or a cobblestone costume comes out
 * slate-coloured. {@link CompositeBlockColour} answers no tint at all while a costume is on. The tint
 * <i>index</i> is deliberately left on the quad rather than stripped, so overlays can still find the
 * surface — moss grows on the disguise, which is what you would want.
 */
public class TransmogBakedModel extends BakedModelWrapper<BakedModel> {

    /** What a block entity is wearing, part by part, delivered through {@link ModelData}. */
    public static final ModelProperty<com.tarosie.granularity.content.Costumes> COSTUMES =
            new ModelProperty<>();

    /**
     * The donor's sprite per face, which is the only expensive part of dressing a block.
     *
     * <p>Deliberately <b>not</b> a cache of finished quads, which is what the sibling wrappers keep.
     * {@link FinishBakedModel} is the innermost of the three, so its key — state, side, render type,
     * finish — names everything its output depends on. This one wraps it, so the quads handed to
     * {@link #dressed} already vary by finish, by composition and by anything else an inner wrapper
     * decides, and a key naming only the donor would quietly serve one block's quads to another.
     *
     * <p>A sprite has no such problem: it depends on the donor and the face and nothing else, so the
     * key is the whole truth. Retexturing the handful of quads that remain afterwards is a loop over
     * ten quads and not worth the risk of getting the other key wrong.
     */
    private final Map<Key, TextureAtlasSprite> sprites = new ConcurrentHashMap<>();

    private record Key(net.minecraft.world.item.Item donor, @Nullable Direction face) {
    }

    public TransmogBakedModel(BakedModel wrapped) {
        super(wrapped);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = originalModel.getQuads(state, side, rand, data, renderType);
        com.tarosie.granularity.content.Costumes costumes = data.get(COSTUMES);
        if (costumes == null || costumes.isEmpty() || base.isEmpty()) {
            return base;
        }
        return dressed(base, state, rand, data, renderType, costumes);
    }

    /**
     * Every quad wearing whatever its own part of the block was given.
     *
     * <p>Which of the two treatments a quad gets is decided per region, not per block, so a piston can
     * wear a vanilla sandstone on its plate and one of our granites on its body at the same time.
     */
    private List<BakedQuad> dressed(List<BakedQuad> base, @Nullable BlockState state,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType,
                                    com.tarosie.granularity.content.Costumes costumes) {
        java.util.Set<Long> stoneFaces = coplanarWithStone(base);
        // Sprite sets by finish, built as they are needed. A part is redrawn in the stone its own
        // donor is made of, so a plate wearing smooth marble takes the *smooth* treatment — asking the
        // model with the block's own finish gave it the piston's cobbled sprites tinted white, which is
        // white cobble and not marble.
        java.util.Map<com.tarosie.granularity.core.Finish,
                java.util.Map<Direction, TextureAtlasSprite[]>> stoneByFinish = new java.util.HashMap<>();
        List<BakedQuad> out = new ArrayList<>(base.size());
        for (BakedQuad quad : base) {
            int tint = quad.getTintIndex();
            com.tarosie.granularity.content.Costumes.Dressed dressed = costumes.covering(tint);
            ItemStack costume = dressed.costume();
            if (!dressed.isEmpty() && !isSurfaceTint(tint) && stoneFaces.contains(place(quad))) {
                // A mask, not a surface. Some parts are not separate geometry at all: a stonecutter's
                // lower stone, upper stone and timber are three *coplanar* boxes at the identical
                // from/to, told apart only by the transparency in their sprites — 74 opaque pixels out
                // of 256 for the frame. Putting any opaque texture on one of those fills the whole
                // face and buries the stone underneath, which is what "changing the frame replaced the
                // whole base" was, from the first report of it onwards.
                //
                // So a masked part keeps its own sprite and takes only a colour. The tint moves into
                // the part's costume band so the colour handler knows a costume is answering for it
                // rather than the block's own timber.
                out.add(OverlayBakedModel.retexture(quad, quad.getSprite(), regionOf(tint).costumeTint(0)));
                continue;
            }
            if (tint == CompositeBlockColour.METAL_TINT) {
                // A metal fitting is dressed with an ingot, which has no faces to lend. It lends its
                // colour instead, in the colour handler — so the quad keeps its own sprite, which is
                // the shape of the bracket, and only what that shape is made of changes.
                out.add(quad);
                continue;
            }
            if (costume.isEmpty() || !(costume.getItem() instanceof BlockItem)) {
                // Undressed, wearing a colorant alone — which keeps this block's own texture and only
                // recolours it — or a costume off disk from a removed mod. A bare quad beats a crash.
                out.add(quad);
                continue;
            }
            if (dressed.textureComposition() == null) {
                if (dressed.isLog()) {
                    // A log is drawn with our own greyscale wood, so it can be coloured at all — by
                    // its own timber's colour, or by whatever colorant is on the part.
                    out.add(OverlayBakedModel.retexture(quad, logSprite(quad.getDirection()), tint));
                    continue;
                }
                if (!dressed.colorant().isEmpty()) {
                    // A colorant beats a foreign texture, and it has to.
                    //
                    // A block tint *multiplies* against the sprite. Our own part sprites are greyscale
                    // for exactly that reason — a tint reads as the colour you chose. A real dark oak
                    // log cannot be turned grey by multiplying it with grey; it can only be darkened,
                    // which is what a log framing an iron-coloured stonecutter looked like.
                    //
                    // So asking for a colour means asking for a surface that can take one, and this
                    // block's own greyscale sprite is the one that can. The donor still decides the
                    // texture whenever no colour is asked for.
                    out.add(quad);
                    continue;
                }
                // A foreign donor keeps its appearance in a texture, so lend it. The tint index is
                // left on the quad rather than stripped, so overlays can still find the surface and
                // moss grows on the disguise; CompositeBlockColour answers it with no tint at all.
                out.add(OverlayBakedModel.retexture(quad, spriteOf(costume, quad.getDirection()), tint));
                continue;
            }
            if (tint < CompositeBlockColour.WOOD_TINT) {
                // Anything that is already stone — a block's own, and a double slab's or a
                // stonecutter's second — is dressed by swapping the composition, upstream in
                // getModelData. That is better than anything doable here: it makes the model emit the
                // right number of grain layers with the right sprites rather than reusing this
                // block's. Leave the quad alone and let the colour handler read its own tint.
                //
                // The upper range especially. Rewriting a double slab's 10-19 into a costume band
                // asked the colour handler for a costume filed under UPPER_STONE, while a slab files
                // its one costume under ALL — so the top half found nothing and drew bare grey.
                out.add(quad);
                continue;
            }
            com.tarosie.granularity.core.Finish lent = dressed.finish();
            restone(out, quad, stoneByFinish.computeIfAbsent(lent,
                    finish -> stoneSprites(state, rand, asFinish(data, finish), renderType)));
        }
        return out;
    }

    /**
     * One non-stone quad turned to stone, so a costume covers all of a block.
     *
     * <p>Granularity is about seeing what a block is made of; transmogrification is the one place that
     * is suspended, and it has to be suspended completely. A piston wearing a stone costume with its
     * wooden plate still showing is not disguised, it is patched.
     *
     * <h2>The stone treatment is borrowed from the model itself</h2>
     * A stone face here is not one sprite but a stack of them: a base, and a layer per grain, coplanar
     * and drawn in order — which is why the layer models in {@code models/block} all share the base's
     * exact {@code from}/{@code to}. So a timber quad is not retextured, it is <i>replaced</i> by that
     * whole stack at the same geometry, using the sprites this model already uses for its own stone on
     * the same face. Nothing has to be invented and nothing has to be looked up.
     *
     * <p>Where a face carries no stone at all — a piston's front is entirely plate — there is nothing
     * to copy, so the particle icon stands in. That is a base sprite for every machine we have, so the
     * face comes out flat stone rather than layered: less detailed than the sides, but stone.
     */
    private void restone(List<BakedQuad> out, BakedQuad quad,
                         java.util.Map<Direction, TextureAtlasSprite[]> stone) {
        com.tarosie.granularity.content.Region region = regionOf(quad.getTintIndex());
        TextureAtlasSprite[] sprites = spritesFacing(stone, quad.getDirection());
        for (int layer = 0; layer < sprites.length; layer++) {
            if (sprites[layer] != null) {
                // Each region's layers carry their own band of tint indices, so two parts wearing two
                // different stones can still be told apart when they are coloured. The sprites are
                // shared and that is correct: a grain layer's sprite is a shape, and the colour that
                // makes it slate rather than granite arrives through the tint.
                out.add(OverlayBakedModel.retexture(quad, sprites[layer], region.costumeTint(layer)));
            }
        }
    }

    /** Whether a tint is one of the block's own stone layers rather than a part set into them. */
    private static boolean isSurfaceTint(int tint) {
        return tint >= 0 && tint < CompositeBlockColour.WOOD_TINT;
    }

    /**
     * Where the stone is, so a part sharing that space can be recognised as a mask over it.
     *
     * <p>Position rather than pixels: asking the atlas whether a sprite has transparency would work
     * too, but two quads occupying the exact same plane is the thing that actually matters, and the
     * geometry says so outright. A piston's plate is a separate box in front of its body and may be
     * retextured freely; a stonecutter's frame is the same box as its stone and may not.
     */
    private static java.util.Set<Long> coplanarWithStone(List<BakedQuad> base) {
        java.util.Set<Long> places = new java.util.HashSet<>();
        for (BakedQuad quad : base) {
            if (isSurfaceTint(quad.getTintIndex())) {
                places.add(place(quad));
            }
        }
        return places;
    }

    /** A quad's position, as a key. Four vertices of three floats, folded together. */
    private static long place(BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        long key = 1L;
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                key = key * 31 + vertices[vertex * stride + axis];
            }
        }
        return key * 7 + (quad.getDirection() == null ? 0 : quad.getDirection().ordinal());
    }

    /** The region a quad belongs to, for a quad that is about to be turned to stone. */
    private static com.tarosie.granularity.content.Region regionOf(int tint) {
        for (com.tarosie.granularity.content.Region region
                : com.tarosie.granularity.content.Region.values()) {
            if (region != com.tarosie.granularity.content.Region.ALL && region.covers(tint)) {
                return region;
            }
        }
        return com.tarosie.granularity.content.Region.ALL;
    }

    /**
     * The stone treatment this model already uses, read off its own quads.
     *
     * <p>A stone face is not one sprite but a stack of them — a base and a layer per grain, coplanar
     * and drawn in order, which is why the layer models all share the base's exact {@code from} and
     * {@code to}. Borrowing that stack means a timber quad can be replaced by real layered stone
     * without inventing geometry or looking anything up.
     *
     * <p>Where a face carries no stone at all — a piston's front is entirely plate — there is nothing
     * to copy and the particle icon stands in. That is a base sprite on every machine we have, so the
     * face comes out flat stone rather than layered: less detailed than the sides, but stone.
     */
    private java.util.Map<Direction, TextureAtlasSprite[]> stoneSprites(
            @Nullable BlockState state, RandomSource rand, ModelData data,
            @Nullable RenderType renderType) {
        java.util.Map<Direction, TextureAtlasSprite[]> found = new java.util.EnumMap<>(Direction.class);
        // Asked of the whole model, not of the quads for the side being drawn. Minecraft asks for one
        // side at a time, and a piston's top face is *entirely* wooden plate — no stone in that call at
        // all — so reading only the current slice found nothing for UP and fell back to the particle
        // icon, which is a side texture. The plate came out wearing the body's side on its top.
        //
        // Not cached: the inner wrappers cache their own quads, so these seven calls are map lookups
        // after the first, and a cache here would need a key naming the finish and the composition to
        // be honest about what it holds.
        collect(found, originalModel.getQuads(state, null, rand, data, renderType));
        for (Direction side : Direction.values()) {
            collect(found, originalModel.getQuads(state, side, rand, data, renderType));
        }
        return found;
    }

    /**
     * Our own greyscale log, end grain on the cut faces and bark on the sides.
     *
     * <p>Looked up rather than kept in a field: the atlas is rebuilt on every resource reload, and a
     * sprite held across one is a stale pointer into a texture that no longer exists.
     */
    private static TextureAtlasSprite logSprite(@Nullable Direction face) {
        boolean end = face == Direction.UP || face == Direction.DOWN;
        return Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        com.tarosie.granularity.Granularity.MODID,
                        end ? "block/log_top" : "block/log_side"));
    }

    /** This block's model data, but asking to be drawn with someone else's finish. */
    private static ModelData asFinish(ModelData data, com.tarosie.granularity.core.Finish finish) {
        return data.derive()
                .with(FinishBakedModel.FINISH, new FinishBakedModel.State(finish, finish))
                .build();
    }

    private static void collect(java.util.Map<Direction, TextureAtlasSprite[]> found,
                                List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            int tint = quad.getTintIndex();
            if (tint < 0 || tint > com.tarosie.granularity.core.Composition.SLOTS
                    || quad.getDirection() == null) {
                continue;
            }
            TextureAtlasSprite[] facing = found.computeIfAbsent(quad.getDirection(),
                    ignored -> new TextureAtlasSprite[com.tarosie.granularity.core.Composition.SLOTS + 1]);
            if (facing[tint] == null) {
                facing[tint] = quad.getSprite();
            }
        }
    }

    /**
     * The stone sprites for one face, falling back through the other faces and then to the particle.
     *
     * <p>Kept per direction, which the first version was not: it took the first sprite it saw for each
     * tint whatever way that quad faced, so a piston's plate came out wearing the body's <i>side</i>
     * texture on its top. A block's top and its sides are different pictures and the model already
     * says which is which — there is no reason to guess.
     *
     * <p>A face with no stone of its own borrows from a face that has some, because some parts are
     * entirely non-stone: a piston's front is all plate, so nothing on that side can be copied from.
     * Only if the model draws no stone at all does the particle icon stand in.
     */
    private TextureAtlasSprite[] spritesFacing(java.util.Map<Direction, TextureAtlasSprite[]> stone,
                                               @Nullable Direction face) {
        TextureAtlasSprite[] exact = face == null ? null : stone.get(face);
        if (exact != null && exact[0] != null) {
            return exact;
        }
        for (TextureAtlasSprite[] any : stone.values()) {
            if (any[0] != null) {
                return any;
            }
        }
        TextureAtlasSprite[] particle =
                new TextureAtlasSprite[com.tarosie.granularity.core.Composition.SLOTS + 1];
        particle[0] = originalModel.getParticleIcon();
        return particle;
    }

    /**
     * The donor's sprite for one face.
     *
     * <p>Read off the donor's own baked model, so a block with different faces — a log, a furnace —
     * lends the right one to the right side. The particle icon is the fallback for a face the donor
     * culls; it is what vanilla itself uses when it needs one sprite to stand for a block.
     *
     * <p>Only full blocks may be inserted — see {@code TransmogMenu.isWearable} — which is what makes
     * this well defined at all: a fence or a torch has no meaningful "north face".
     */
    private TextureAtlasSprite spriteOf(ItemStack costume, @Nullable Direction face) {
        return sprites.computeIfAbsent(new Key(costume.getItem(), face),
                key -> faceOf(costume, key.face()));
    }

    private static TextureAtlasSprite faceOf(ItemStack costume, @Nullable Direction face) {
        BlockState donor = ((BlockItem) costume.getItem()).getBlock().defaultBlockState();
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(donor);
        if (face != null) {
            List<BakedQuad> quads = model.getQuads(donor, face, RandomSource.create(42L));
            if (!quads.isEmpty()) {
                return quads.get(0).getSprite();
            }
        }
        return model.getParticleIcon(ModelData.EMPTY);
    }

    /** See {@link OverlayBakedModel#applyTransform}: a wrapper that returns the wrapped model is lost. */
    @Override
    public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context,
                                     com.mojang.blaze3d.vertex.PoseStack poseStack, boolean leftHand) {
        originalModel.applyTransform(context, poseStack, leftHand);
        return this;
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this);
    }
}
