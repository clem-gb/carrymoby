package com.carrymoby.core;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Implemented on {@link net.minecraft.world.entity.player.Player} by a mixin.
 *
 * <p>The carried mob is kept as raw NBT on the player instead of as a live entity or a
 * passenger. That is what makes it survive absolutely everything: teleports, dimension
 * changes, chunk unloads, death and respawn, logout and server restart.
 */
public interface CarriedMobHolder {
	@Nullable
	CompoundTag carrymoby$getCarried();

	void carrymoby$setCarried(@Nullable CompoundTag tag);
}
