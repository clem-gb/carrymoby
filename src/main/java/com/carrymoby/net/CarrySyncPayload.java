package com.carrymoby.net;

import com.carrymoby.CarryMoby;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;

/**
 * Server to client: player {@code entityId} is carrying {@code mob}, or nothing when empty.
 *
 * <p>The full mob NBT is sent so the client can rebuild a display-only copy of the entity and
 * render it with its real variant, age, colour, name and equipment.
 */
public record CarrySyncPayload(int entityId, Optional<CompoundTag> mob) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CarrySyncPayload> ID =
			new CustomPacketPayload.Type<>(CarryMoby.id("sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CarrySyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CarrySyncPayload::entityId,
			ByteBufCodecs.OPTIONAL_COMPOUND_TAG, CarrySyncPayload::mob,
			CarrySyncPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
