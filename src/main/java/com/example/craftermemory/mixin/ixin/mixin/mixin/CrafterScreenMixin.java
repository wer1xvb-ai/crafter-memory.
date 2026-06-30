package com.example.craftermemory.mixin;

import com.example.craftermemory.CrafterMemoryMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CrafterScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeScreenProvider;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrafterScreen.class)
abstract class CrafterScreenMixin extends HandledScreen<CrafterScreenHandler> implements RecipeScreenProvider {
    @Unique private static final Identifier RECIPE_BTN = Identifier.of("minecraft", "textures/gui/recipe_button.png");
    @Unique private final RecipeBookWidget recipeBook = new RecipeBookWidget();
    @Unique private ButtonWidget btnRemember;
    @Unique private ButtonWidget btnForget;

    public CrafterScreenMixin(CrafterScreenHandler h, PlayerInventory i, Text t) { super(h, i, t); }

    @Inject(method = "init", at = @At("TAIL"))
    private void injectInit(CallbackInfo ci) {
        this.recipeBook.initialize(this.width, this.height, this.client, this.width < 379, this.handler);
        this.x = this.recipeBook.findLeftEdge(this.width, this.backgroundWidth);

        this.addDrawableChild(new TexturedButtonWidget(this.x + 5, this.height / 2 - 49, 20, 18, 0, 0, 19, RECIPE_BTN, (b) -> {
            this.recipeBook.toggleOpen();
            this.x = this.recipeBook.findLeftEdge(this.width, this.backgroundWidth);
            b.setPosition(this.x + 5, this.height / 2 - 49);
            this.btnRemember.setPosition(this.x + 10, this.y + 6);
            this.btnForget.setPosition(this.x + 10, this.y + 6);
        }));

        this.btnRemember = ButtonWidget.builder(Text.literal("Запомнить"), b -> sendPacket(0)).dimensions(this.x + 10, this.y + 6, 60, 12).build();
        this.btnForget = ButtonWidget.builder(Text.literal("Забыть"), b -> sendPacket(1)).dimensions(this.x + 10, this.y + 6, 50, 12).build();
        this.addDrawableChild(this.btnRemember);
        this.addDrawableChild(this.btnForget);
        this.addSelectableChild(this.recipeBook);
    }

    @Unique
    private void sendPacket(int type) {
        if (this.handler.getBlockEntity() instanceof CrafterBlockEntity crafter) {
            ClientPlayNetworking.send(new CrafterMemoryMod.ActionPayload(crafter.getPos(), type));
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void injectRender(DrawContext ctx, int mx, int my, float d, CallbackInfo ci) {
        if (this.handler.getBlockEntity() instanceof CrafterMemoryAccess a) {
            this.btnRemember.visible = !a.crafterMemory$hasSaved();
            this.btnForget.visible = a.crafterMemory$hasSaved();
        }
        this.recipeBook.render(ctx, mx, my, d);
        super.render(ctx, mx, my, d);
        this.recipeBook.drawGhostSlots(ctx, this.x, this.y, true, d);
        this.drawMouseoverTooltip(ctx, mx, my);
        this.recipeBook.drawTooltip(ctx, this.x, this.y, mx, my);
        ci.cancel();
    }

    @Override public RecipeBookWidget getRecipeBookWidget() { return this.recipeBook; }
}
