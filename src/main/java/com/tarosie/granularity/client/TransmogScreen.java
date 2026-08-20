package com.tarosie.granularity.client;

import com.tarosie.granularity.Granularity;
import com.tarosie.granularity.content.TransmogMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The transmogrification screen: one costume slot over the player's inventory.
 *
 * <p>All of the work is in the background sprite, which {@code tools/gen_gui.py} draws from the same
 * constants {@link TransmogMenu} lays its slots out with — so the holes in the picture are exactly where
 * the hit boxes are. See that script for why this is generated rather than painted.
 */
public class TransmogScreen extends AbstractContainerScreen<TransmogMenu> {

    /** Three quarters, which fits the two lines beside four columns without crowding the rule. */
    private static final float HELP_SCALE = 0.75F;

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(Granularity.MODID, "textures/gui/transmog_panel.png");

    public TransmogScreen(TransmogMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // A three-row chest's height, so the empty area above the inventory is a size players already
        // have an intuition for.
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // The costume slots are drawn here rather than baked into the background, because how many
        // there are depends on what the block is made of — one for a wall, four for a stonecutter.
        // The square is copied from a slot already on the sheet, so there is no second sprite that
        // could drift out of step with the first.
        var regions = menu.regions();
        for (TransmogMenu.Cell cell : menu.cells()) {
            graphics.blit(BACKGROUND,
                    leftPos + cell.x(regions.indexOf(cell.region())) - 1,
                    topPos + cell.y() - 1,
                    TransmogMenu.INVENTORY_X, TransmogMenu.INVENTORY_Y,
                    TransmogMenu.SLOT_PITCH, TransmogMenu.SLOT_PITCH);
        }

        // A rule between the slots and the words, so the panel reads as two things rather than as one
        // crowded one. Vanilla's own sunken-border greys, so it sits in the panel rather than on it.
        int divider = leftPos + TransmogMenu.dividerX(regions.size());
        graphics.fill(divider, topPos + 16, divider + 1, topPos + 74, 0xFF373737);
        graphics.fill(divider + 1, topPos + 16, divider + 2, topPos + 74, 0xFFFFFFFF);

        // Drawn small, and measured before it is drawn. The instructions have to fit between the
        // divider and the panel's edge *and* stop above the inventory: at full size they ran straight
        // down into the player's own slots, which reads as a broken screen rather than a full one.
        // Scaling is the only one of the three levers — wider panel, smaller text, less text — that
        // holds however many columns the block turns out to have.
        int textX = divider + 6;
        int room = leftPos + TransmogMenu.PANEL_WIDTH - 7 - textX;
        graphics.pose().pushPose();
        graphics.pose().translate(textX, topPos + 19, 0);
        graphics.pose().scale(HELP_SCALE, HELP_SCALE, 1.0F);
        graphics.drawWordWrap(font, Component.translatable("screen.granularity.transmog.help"),
                0, 0, (int) (room / HELP_SCALE), 0xFF404040);
        graphics.pose().popPose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        // Without this the item under the cursor has no tooltip, which reads as the screen being
        // broken rather than empty.
        renderTooltip(graphics, mouseX, mouseY);
        namePart(graphics, mouseX, mouseY);
    }

    /**
     * Names the part an empty costume slot dresses.
     *
     * <p>A row of four identical empty squares over a stonecutter says nothing about which one is the
     * blade. The slots have no other label — a name under each would not fit at this width — so the
     * only place the mapping can be stated is here, on hover.
     */
    private void namePart(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredSlot == null || hoveredSlot.hasItem()) {
            return;
        }
        int index = hoveredSlot.index;
        if (index < 0 || index >= menu.cells().size()) {
            return;
        }
        TransmogMenu.Cell cell = menu.cells().get(index);
        // The block's own word for the part where it has one — a stonecutter's metal is a blade, and
        // its lower stone is not merely "stone" with an upper one beside it.
        String specific = cell.nameKey(menu.blockPath());
        Component part = Component.translatable(
                net.minecraft.client.resources.language.I18n.exists(specific)
                        ? specific : cell.genericNameKey());
        graphics.renderTooltip(font, cell.colorant()
                ? Component.translatable("region.granularity.colour_of", part)
                : part, mouseX, mouseY);
    }
}
