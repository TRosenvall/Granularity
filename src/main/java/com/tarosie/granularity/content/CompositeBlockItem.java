package com.tarosie.granularity.content;

import com.tarosie.granularity.core.Composition;
import com.tarosie.granularity.core.Grain;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * A crafted block's item form, named by the arithmetic of what it is made of.
 *
 * <p>A block of one grain earns that grain's name — "Granite Cobblestone", "Iron Gravel". Anything
 * mixed is plain "Cobblestone" or "Gravel".
 *
 * <p><b>Nearness does not count.</b> Five chalk and four shale average to almost exactly diorite,
 * and it is still just "Cobblestone". The name follows what a block is made of, never what its
 * average happens to resemble — an earlier version snapped the average to the nearest named stone
 * and produced blocks claiming to be things they were not.
 */
public class CompositeBlockItem extends BlockItem {

    public CompositeBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Composition composition = stack.get(GranularityComponents.COMPOSITION.get());
        Grain sole = composition == null ? null : composition.soleGrain();
        // Each block names itself differently: a cobblestone of slate is "Slate Cobblestone", but
        // smelted it is "Smooth Slate", not "Slate Smooth Stone". The pattern belongs to the block,
        // so it lives beside the block's own name rather than in one shared format string.
        // A finish renames the block rather than prefixing it, because English does not prefix it:
        // smelted slate is "Smooth Slate", not "Smooth Slate Cobblestone". So the finish selects the
        // key and the stone fills the blank, which also lets a finish that reads differently in
        // another language say so without any of this changing.
        String key = getDescriptionId();
        com.tarosie.granularity.core.Finish finish = Finishes.of(stack);
        if (finish != com.tarosie.granularity.core.Finish.COBBLED) {
            key = key + "." + finish.id();
        }
        // English orders its adjectives, and a colour goes *after* a quality and immediately before
        // the material: "Smooth Red Basalt", never "Red Smooth Basalt". Prefixing the colour to the
        // finished name got that backwards, which Timothy caught on a dyed smooth basalt.
        //
        // The fix needs no new grammar, because the wording already has a slot in the right place. A
        // named block's key is "Smooth %s" and the blank is exactly where the material goes — so the
        // dye attaches to the *stone*, and "Red Basalt" dropped into "Smooth %s" comes out ordered.
        // It works for every style at once, including the awkward ones: "Fine %s Brick Slab" gives
        // "Fine Red Slate Brick Slab", with quality, colour, material and form all in place.
        //
        // A block with no single stone has no such slot in its plain name — "Smooth Stone" names no
        // material to sit beside — so it takes a third wording, ".dyed", which is the plain one with
        // the blank cut into it: "Smooth %s Stone". See tools/gen_style_lang.py.
        Component dye = dyeName(stack);
        Component name;
        if (sole != null) {
            Component stone = Component.translatable(sole.translationKey());
            name = Component.translatable(key + ".named",
                    dye == null ? stone : Component.translatable("granularity.overlay.prefixed", dye, stone));
        } else if (dye != null) {
            name = Component.translatable(key + ".dyed", dye);
        } else {
            name = Component.translatable(key);
        }
        // Every overlay adds a prefix rather than each combination earning its own key, because the
        // combinations are unbounded and any mod may add to them. "Mossy Bloodied Slate Slab" reads
        // acceptably; a key per subset does not exist to be written.
        for (Overlay overlay : Moss.of(stack).faces().keySet()) {
            net.minecraft.resources.ResourceLocation id = GranularityOverlays.idOf(overlay);
            if (id == null) {
                continue;
            }
            name = Component.translatable("granularity.overlay.prefixed",
                    Component.translatable("overlay." + id.getNamespace() + "." + id.getPath()), name);
        }
        return name;
    }

    /**
     * What to call the colour, or null for a block nobody has dyed.
     *
     * <p>A block dyed one colour all over earns that dye's own name — {@code color.minecraft.red} is
     * vanilla's, already translated into every language the game ships, so sixteen colours cost no
     * lang entries. The match is exact rather than nearest, because the stored value <i>came</i> from
     * a {@code DyeItem} and so is one of those sixteen to the digit; a nearest-colour search would be
     * the same mistake {@link CompositeBlockItem} already refuses to make about stone.
     *
     * <p>Faces of different colours get a plain "Dyed". There is no honest single adjective for a
     * block that is red on one side and blue on another, and inventing one would misdescribe it.
     */
    @Nullable
    private static Component dyeName(ItemStack stack) {
        Dyes dyes = Dyes.of(stack);
        if (dyes.isEmpty()) {
            return null;
        }
        Integer uniform = dyes.uniform();
        if (uniform != null) {
            for (DyeColor colour : DyeColor.values()) {
                if ((colour.getTextureDiffuseColor() & 0xFFFFFF) == uniform) {
                    return Component.translatable("color.minecraft." + colour.getName());
                }
            }
        }
        return Component.translatable("granularity.dye.mixed");
    }
}
