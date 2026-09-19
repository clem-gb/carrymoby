package com.carrymoby.net;

import com.carrymoby.CarryMoby;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the carry key was pressed. The server does the target picking itself, so a
 * modified client cannot ask for a mob it is not actually looking at.
 */
public record ToggleCarryPayload() implements CustomPacketPayload {
	public static final ToggleCarryPayload INSTANCE = new ToggleCarryPayload();

	public static final CustomPacketPayload.Type<ToggleCarryPayload> ID =
			new CustomPacketPayload.Type<>(CarryMoby.id("toggle"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ToggleCarryPayload> CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
