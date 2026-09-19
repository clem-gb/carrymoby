package com.carrymoby.mixin;

import com.carrymoby.core.CarriedMobHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stores the carried mob inside the player's own save data. */
@Mixin(Player.class)
public abstract class PlayerCarryDataMixin implements CarriedMobHolder {
	@Unique
	private static final String CARRY_KEY = "CarryMoby";

	@Unique
	@Nullable
	private CompoundTag carrymoby$carried;

	@Override
	public @Nullable CompoundTag carrymoby$getCarried() {
		return this.carrymoby$carried;
	}

	@Override
	public void carrymoby$setCarried(@Nullable CompoundTag tag) {
		this.carrymoby$carried = tag;
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void carrymoby$save(ValueOutput output, CallbackInfo info) {
		if (this.carrymoby$carried != null) {
			output.store(CARRY_KEY, CompoundTag.CODEC, this.carrymoby$carried);
		}
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void carrymoby$load(ValueInput input, CallbackInfo info) {
		this.carrymoby$carried = input.read(CARRY_KEY, CompoundTag.CODEC).orElse(null);
	}
}
