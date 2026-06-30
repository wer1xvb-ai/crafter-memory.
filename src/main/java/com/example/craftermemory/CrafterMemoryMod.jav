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
