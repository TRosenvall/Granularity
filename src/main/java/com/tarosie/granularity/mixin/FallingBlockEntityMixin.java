package com.tarosie.granularity.mixin;

import com.tarosie.granularity.content.CompositeBlockEntity;
import com.tarosie.granularity.content.CompositeStone;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A falling composite that cannot land must still drop what it was made of.
 *
 * <p>{@code FallingBlockEntity} has three ways to give up — the placement is refused (gravel landing
 * on a torch, which is how people collect it), {@code setBlock} fails, or it falls for 600 ticks and
 * is written off — and all three drop {@code spawnAtLocation(block)}, a bare item with no components
 * at all. For vanilla gravel that is exactly right, because vanilla gravel is only ever gravel. Ours
 * would come back as the default stone, losing the composition and handing over a laundering hole:
 * drop a gold-bearing gravel onto a torch and it is plain again.
 *
 * <p>Redirecting rather than injecting, because the fix is precisely "call this with a better
 * argument". All three call sites match one redirect, which is the point — the third has no
 * {@code onBrokenAfterFall} before it, so a {@link net.minecraft.world.level.block.Fallable} hook
 * could not have covered it, and setting {@code dropItem = false} skips the drop <i>and</i> the hook
 * and loses the item outright. This is the only seam that reaches all three.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;"
                            + "spawnAtLocation(Lnet/minecraft/world/level/ItemLike;)"
                            + "Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity granularity$dropWhatItWasMadeOf(FallingBlockEntity self, ItemLike item) {
        if (!(self.getBlockState().getBlock() instanceof CompositeStone)) {
            return self.spawnAtLocation(item);
        }
        // blockData is the block entity's own tag, put there by CompositeGravelBlock before the
        // block left the world. Null means the block never carried a composition, so vanilla's
        // answer is already the right one.
        ItemStack stack = CompositeBlockEntity.itemFrom(item, self.blockData);
        return self.spawnAtLocation(stack);
    }
}
