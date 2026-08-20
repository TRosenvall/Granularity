package com.tarosie.granularity.content;

import com.tarosie.granularity.Granularity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Dressing a block, part by part.
 *
 * <p>A column of slots per {@link Region} the block offers, and the player's inventory below. A plain
 * block, slab, stair or wall offers a single region; a stonecutter offers four, because it is
 * genuinely built out of four things.
 *
 * <h2>Two slots per part: a texture and a colour</h2>
 * The upper slot takes a block and supplies its texture <i>and</i> its colour. The lower slot takes a
 * grain — chunk, ore, gem, ingot — and overrides the colour, leaving the texture alone. A
 * multicoloured cobble plus a slate chunk reads as slate cobblestone.
 *
 * <p>Separate because the two questions are separate. A block assembled for its properties lands on
 * whatever colour its grains happen to make, and being able to say "this texture, that rock's colour"
 * is what stops {@code docs/ALLOYS.md} forcing a choice between a block that performs and a block
 * that looks right.
 *
 * <h2>What each slot asks for</h2>
 * A costume asks the same of you as the recipe did: a stonecutter's blade was an iron ingot, so it
 * takes a grain item and has no colorant slot — it is already only a colour. A piston's plate was
 * planks, so it takes a block.
 *
 * <p><b>Every standard composite always takes a block</b>, whatever it was crafted from. Read
 * strictly the recipe rule would make almost every slot a chunk slot, and a chunk can only say "look
 * like this rock", never "look like sandstone" — which would remove the ability to disguise anything
 * as a non-Granularity block, the whole point of the feature.
 *
 * <h2>Bound to a CompositionHolder, not a CompositeBlockEntity</h2>
 * A furnace, a dispenser and a dropper had to extend vanilla's container block entities, so they are
 * not {@link CompositeBlockEntity}. Testing for the concrete class here meant the menu opened on a
 * furnace and silently did nothing at all — no error, no log, no crash, just a screen that never
 * appeared.
 */
public class TransmogMenu extends AbstractContainerMenu {

    /**
     * Where the slots sit.
     *
     * <h2>The layout is shared with the picture of it</h2>
     * {@code tools/gen_gui.py} draws the background from these same numbers. That matters more than it
     * looks: a slot is a hit box here and a bevelled square there, and if the two drift by a pixel the
     * result is a screen that feels subtly wrong and never says why.
     */
    public static final int SLOT_PITCH = 18;

    /**
     * Two rows: grains above, blocks below.
     *
     * <p>Sorted by <i>what a slot takes</i> rather than by which part it belongs to, so the two rows
     * each mean one thing — everything on the bottom lends a texture, everything on the top lends only
     * a colour. A part that is only ever a colour, like a stonecutter's blade, sits on the top row with
     * the other colours and leaves the space below it empty.
     */
    public static final int GRAIN_Y = 22;
    public static final int BLOCK_Y = 44;

    /** The columns start at the left edge, leaving the right of the panel for the instructions. */
    public static final int SLOTS_X = 8;
    public static final int PANEL_WIDTH = 176;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 84;
    public static final int HOTBAR_Y = 142;

    private static final int MAIN_SLOTS = 27;

    /**
     * One slot: which part it dresses, and whether it is the colour rather than the texture.
     *
     * @param region   the part this slot belongs to
     * @param colorant true for the lower slot of a pair
     */
    public record Cell(Region region, boolean colorant) {

        /** The outline drawn when this slot is empty. A slot that wants a chunk must not show a cube. */
        public ResourceLocation emptyIcon() {
            return ResourceLocation.fromNamespaceAndPath(Granularity.MODID,
                    colorant ? "item/empty_transmog_slot_grain" : region.emptyIcon());
        }

        /**
         * The tooltip naming what this slot dresses; a bare square says nothing on its own.
         *
         * <p>Resolved against the block first — a stonecutter's metal is a <i>blade</i>, and its lower
         * stone is not just "stone" when there is an upper one right beside it — and only then against
         * the generic name. See {@code TransmogScreen}, which does the falling back, because whether a
         * key exists is a question only the client can answer.
         */
        public String nameKey(String blockPath) {
            return "region.granularity." + blockPath + "." + region.id();
        }

        public String genericNameKey() {
            return "region.granularity." + region.id();
        }

        public int x(int column) {
            return slotX(column);
        }

        /** A slot's row is decided by what it takes, not by which part it belongs to. */
        public int y() {
            return takesGrain() ? GRAIN_Y : BLOCK_Y;
        }

        public boolean takesGrain() {
            return colorant || region.wears() == Region.Wears.GRAIN;
        }
    }

    private final Container held;
    private final List<Region> regions;
    private final List<Cell> cells = new ArrayList<>();
    private final Level level;
    private final BlockPos pos;

    /** Client-side constructor: the position rides along in the opening packet. */
    public TransmogMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, inventory.player.level(), buffer.readBlockPos());
    }

    public TransmogMenu(int containerId, Inventory inventory, Level level, BlockPos pos) {
        super(GranularityMenus.TRANSMOG.get(), containerId);
        this.level = level;
        this.pos = pos;
        this.regions = Region.of(level.getBlockState(pos));

        for (Region region : regions) {
            cells.add(new Cell(region, false));
            if (region.takesColorant()) {
                cells.add(new Cell(region, true));
            }
        }
        this.held = new SimpleContainer(cells.size());

        // Seeded from the block, so opening the screen shows what it is already wearing.
        if (level.getBlockEntity(pos) instanceof CompositionHolder dressed) {
            Costumes worn = dressed.costumes();
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                Costumes.Dressed on = worn.on(cell.region());
                held.setItem(i, (cell.colorant() ? on.colorant() : on.costume()).copy());
            }
        }

        for (int i = 0; i < cells.size(); i++) {
            final int index = i;
            Cell cell = cells.get(i);
            addSlot(new Slot(held, i, cell.x(regions.indexOf(cell.region())), cell.y()) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(cell, stack);
                }

                @Override
                public int getMaxStackSize() {
                    // One is all a costume can be. A stack would leave the rest stranded when the
                    // block is broken and one item comes back.
                    return 1;
                }

                @Override
                public void setChanged() {
                    super.setChanged();
                    dress(index);
                }
            }.setBackground(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, cell.emptyIcon()));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9,
                        INVENTORY_X + col * SLOT_PITCH, INVENTORY_Y + row * SLOT_PITCH));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * SLOT_PITCH, HOTBAR_Y));
        }
    }

    public static int slotX(int column) {
        return SLOTS_X + column * SLOT_PITCH;
    }

    /** Where the rule separating the slots from the instructions is drawn. */
    public static int dividerX(int columns) {
        return SLOTS_X + columns * SLOT_PITCH + 4;
    }

    /** The parts this screen is showing, in column order. */
    public List<Region> regions() {
        return regions;
    }

    /** Every slot this screen is showing, in slot order. */
    public List<Cell> cells() {
        return cells;
    }

    /** The block being dressed, for naming its parts in its own terms. */
    public String blockPath() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /**
     * Whether a slot will accept an item.
     *
     * <p>Every test is asked of tags, of the grain roster, or of the block's own state rather than
     * kept as a list of items, so a plank, an ingot or a rock from another mod is eligible the moment
     * it qualifies. That is the same rule the grain roster and the wood tint already follow.
     */
    public static boolean accepts(Cell cell, ItemStack stack) {
        if (cell.takesGrain()) {
            return Costumes.grainOf(stack) != null;
        }
        return stack.getItem() instanceof BlockItem item
                && item.getBlock().defaultBlockState().isSolidRender(
                        net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Puts what is in one slot onto its part of the block, or takes that part's costume off. */
    private void dress(int index) {
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof CompositionHolder dressed) {
            Cell cell = cells.get(index);
            ItemStack stack = held.getItem(index);
            dressed.setCostumes(cell.colorant()
                    ? dressed.costumes().withColorant(cell.region(), stack)
                    : dressed.costumes().withCostume(cell.region(), stack));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack before = inSlot.copy();
        int parts = cells.size();

        boolean moved;
        int free = firstFreeSlot(inSlot);
        if (index < parts) {
            // Out of a costume slot, back into the player's hands.
            moved = moveItemStackTo(inSlot, parts, slots.size(), true);
        } else if (free >= 0) {
            // Shift-clicking puts it on, which is the gesture people will try first. It fills the
            // first bare slot that will have it, so a chunk goes to a colorant slot rather than
            // bouncing off the block slot in front of it.
            moved = moveItemStackTo(inSlot, free, free + 1, false);
        } else {
            boolean fromMainRows = index < parts + MAIN_SLOTS;
            moved = fromMainRows
                    ? moveItemStackTo(inSlot, parts + MAIN_SLOTS, slots.size(), false)
                    : moveItemStackTo(inSlot, parts, parts + MAIN_SLOTS, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (inSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return before;
    }

    private int firstFreeSlot(ItemStack stack) {
        for (int i = 0; i < cells.size(); i++) {
            if (!slots.get(i).hasItem() && accepts(cells.get(i), stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The costumes stay on the block when the screen closes.
     *
     * <p>Deliberately <b>not</b> returned to the player, unlike a crafting grid's contents: they are
     * being worn, and they come back when the block is broken. Dropping them here would take the
     * disguise off the moment you stopped looking at it.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
    }

    /**
     * Still in reach, still a composite, and still allowed to build.
     *
     * <p>The build check is repeated here rather than trusted from the moment the screen opened: a
     * mode change while it is open would otherwise leave an adventure-mode player holding a working
     * transmogrification screen, and the slots write straight through to the block.
     */
    @Override
    public boolean stillValid(Player player) {
        return level.getBlockEntity(pos) instanceof CompositionHolder
                && player.mayBuild()
                && player.canInteractWithBlock(pos, 4.0);
    }
}
