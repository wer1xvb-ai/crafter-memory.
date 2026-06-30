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
        for (int i = 0; i < 9; i++) this.recipeMemory.set(i, self.getStack(i).copy());
        this.hasSavedRecipe = true;
        this.markDirty();
    }

    @Override
    public void crafterMemory$forget() {
        for (int i = 0; i < 9; i++) this.recipeMemory.set(i, ItemStack.EMPTY);
        this.hasSavedRecipe = false;
        this.markDirty();
    }

    @Inject(method = "canInsert", at = @At("HEAD"), cancellable = true)
    private void injectCanInsert(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!this.hasSavedRecipe) return;
        CrafterBlockEntity self = (CrafterBlockEntity) (Object) this;
        ItemStack expectedTemplate = this.recipeMemory.get(slot);
        ItemStack currentStackInSlot = self.getStack(slot);

        if (expectedTemplate.isEmpty()) { cir.setReturnValue(false); return; }
        if (!ItemStack.areItemsAndComponentsEqual(stack, expectedTemplate)) { cir.setReturnValue(false); return; }
        if (!currentStackInSlot.isEmpty() && currentStackInSlot.getCount() >= expectedTemplate.getCount()) {
            cir.setReturnValue(false);
            return;
        }
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
