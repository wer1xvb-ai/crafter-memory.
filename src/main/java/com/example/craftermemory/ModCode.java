// fabric.mod.json
{
  "schemaVersion": 1,
  "id": "crafter_memory",
  "version": "1.0.0",
  "name": "Crafter Memory Addon",
  "entrypoints": { "main": [ "com.example.craftermemory.CrafterMemoryMod" ] },
  "mixins": [ "crafter_memory.mixins.json" ],
  "depends": { "fabricloader": ">=0.15.0", "minecraft": "26.2", "java": ">=25" }
}
// crafter_memory.mixins.json
{
  "required": true,
  "package": "com.example.craftermemory.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": [ "CrafterBlockEntityMixin" ],
  "client": [ "CrafterScreenMixin" ]
}
package com.example.craftermemory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.util.math.BlockPos;

public class CrafterMemoryMod implements ModInitializer {
    public static final String MOD_ID = "crafter_memory";

    public record ActionPayload(BlockPos pos, int actionType) implements CustomPayload {
        public static final CustomPayload.Id<ActionPayload> ID = new CustomPayload.Id<>(Identifier.of(MOD_ID, "action"));
        
        public static final PacketCodec<PacketByteBuf, ActionPayload> CODEC = CustomPayload.codecOf(
            (value, buf) -> { buf.writeBlockPos(value.pos); buf.writeInt(value.actionType); },
            buf -> new ActionPayload(buf.readBlockPos(), buf.readInt())
        );

        @Override 
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.configurationC2S().register(ActionPayload.ID, ActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.ID, ActionPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                BlockPos pos = payload.pos();
                if (context.player().getWorld().getBlockEntity(pos) instanceof CrafterBlockEntity crafter) {
                    CrafterMemoryAccess access = (CrafterMemoryAccess) crafter;
                    if (payload.actionType() == 0) access.crafterMemory$remember(context.player());
                    else if (payload.actionType() == 1) access.crafterMemory$forget();
                }
            });
        });
    }
}
package com.example.craftermemory.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

interface CrafterMemoryAccess {
    boolean crafterMemory$hasSaved();
    void crafterMemory$remember(PlayerEntity player);
    void crafterMemory$forget();
}

@Mixin(CrafterBlockEntity.class)
abstract class CrafterBlockEntityMixin extends BlockEntity implements CrafterMemoryAccess {
    @Unique private final DefaultedList<ItemStack> recipeMemory = DefaultedList.ofSize(9, ItemStack.EMPTY);
    @Unique private boolean hasSavedRecipe = false;

    public CrafterBlockEntityMixin(BlockEntityType<?> t, BlockPos p, BlockState s) { super(t, p, s); }

    @Override public boolean crafterMemory$hasSaved() { return this.hasSavedRecipe; }

    @Override
    public void crafterMemory$remember(PlayerEntity player) {
        CrafterBlockEntity self = (CrafterBlockEntity) (Object) this;
        // Запоминаем текущую схему предметов в сетке как эталон
        for (int i = 0; i < 9; i++) {
            this.recipeMemory.set(i, self.getStack(i).copy());
        }
        this.hasSavedRecipe = true;
        this.markDirty();
    }

    @Override
    public void crafterMemory$forget() {
        for (int i = 0; i < 9; i++) {
            this.recipeMemory.set(i, ItemStack.EMPTY);
        }
        this.hasSavedRecipe = false;
        this.markDirty();
    }

    /**
     * ИНЪЕКЦИЯ ДЛЯ ВОРОНОК И ВЫБРАСЫВАТЕЛЕЙ
     * Перехватываем ванильный метод проверки: "Можно ли вставить этот предмет в этот слот?"
     * Вызывается воронкой перед тем, как затолкнуть предмет в Автокрафтер.
     */
    @Inject(method = "canInsert", at = @At("HEAD"), cancellable = true)
    private void injectCanInsert(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // Если рецепт не сохранен, блок ведет себя как стандартный ванильный Автокрафтер
        if (!this.hasSavedRecipe) return;

        CrafterBlockEntity self = (CrafterBlockEntity) (Object) this;
        ItemStack expectedTemplate = this.recipeMemory.get(slot);
        ItemStack currentStackInSlot = self.getStack(slot);

        // 1. Если по шаблону этот слот должен быть пустым — запрещаем воронке совать сюда что-либо
        if (expectedTemplate.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }

        // 2. Если воронка пытается засунуть предмет, который не совпадает с шаблоном для этого слота — запрещаем
        if (!ItemStack.areItemsAndComponentsEqual(stack, expectedTemplate)) {
            cir.setReturnValue(false);
            return;
        }

        // 3. Проверяем лимит стака (чтобы воронки наполняли слоты равномерно, а не забивали один слот до 64)
        // Если в слоте уже лежит предмет, и его количество равно или больше, чем в шаблоне памяти — временно блокируем слот,
        // заставляя воронку искать другие подходящие слоты для этого же ресурса в схеме крафта.
        if (!currentStackInSlot.isEmpty() && currentStackInSlot.getCount() >= expectedTemplate.getCount()) {
            cir.setReturnValue(false);
            return;
        }

        // Если все проверки пройдены, разрешаем воронке положить предмет именно сюда
        cir.setReturnValue(true);
    }

    @Inject(method = "readNbt", at = @At("TAIL"))
    private void injectReadNbt(NbtCompound nbt, RegistryWrapper.WrapperProvider lookup, CallbackInfo ci) {
        this.hasSavedRecipe = nbt.getBoolean("HasSavedRecipe");
        if (nbt.contains("SavedRecipeItems", 9)) {
            NbtList list = nbt.getList("SavedRecipeItems", 10);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound itemNbt = list.getCompound(i);
                int slot = itemNbt.getByte("Slot");
                if (slot >= 0 && slot < 9) this.recipeMemory.set(slot, ItemStack.fromNbtOrEmpty(lookup, itemNbt));
            }
        }
    }

    @Inject(method = "writeNbt", at = @At("TAIL"))
    private void injectWriteNbt(NbtCompound nbt, RegistryWrapper.WrapperProvider lookup, CallbackInfo ci) {
        nbt.putBoolean("HasSavedRecipe", this.hasSavedRecipe);
        NbtList list = new NbtList();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.recipeMemory.get(i);
            if (!stack.isEmpty()) {
                NbtCompound itemNbt = (NbtCompound) stack.encode(lookup);
                itemNbt.putByte("Slot", (byte) i);
                list.add(itemNbt);
            }
        }
        nbt.put("SavedRecipeItems", list);
    }
}
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
