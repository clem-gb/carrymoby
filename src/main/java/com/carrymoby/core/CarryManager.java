package com.carrymoby.core;

import com.carrymoby.net.CarrySyncPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.carrymoby.CarryMoby;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** All the server-side carrying logic. */
public final class CarryManager {
	private CarryManager() {
	}

	public static CarriedMobHolder holder(Player player) {
		return (CarriedMobHolder) player;
	}

	@Nullable
	public static CompoundTag getCarried(Player player) {
		return holder(player).carrymoby$getCarried();
	}

	public static boolean isCarrying(Player player) {
		return getCarried(player) != null;
	}

	/** The key press: grab what the player is looking at, or put down what they already hold. */
	public static void toggle(ServerPlayer player) {
		if (isCarrying(player)) {
			release(player, true);
		} else {
			Entity target = pick(player);

			if (target == null) {
				player.displayClientMessage(Component.translatable("carrymoby.message.no_target"), true);
			} else {
				pickUp(player, target);
			}
		}
	}

	/** Cosine of the half-angle of the forgiving cone, about 30 degrees. */
	private static final double CONE_COSINE = 0.87;

	@Nullable
	private static Entity pick(ServerPlayer player) {
		double range = CarryConfig.get().pickupRange;
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		Vec3 end = eye.add(look.scale(range));
		AABB box = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, CarryManager::isCarryable, range * range);

		if (hit != null) {
			return hit.getEntity();
		}

		// Chickens and cats are small and spend their time at your feet, so a pixel-perfect ray
		// is a frustrating way to grab them. Fall back to whatever sits closest to the crosshair.
		Entity best = null;
		double bestAlignment = CONE_COSINE;

		for (Entity candidate : player.level().getEntities(player, player.getBoundingBox().inflate(range), CarryManager::isCarryable)) {
			Vec3 toward = candidate.getBoundingBox().getCenter().subtract(eye);

			if (toward.lengthSqr() > range * range || toward.lengthSqr() < 1.0E-4) {
				continue;
			}

			double alignment = toward.normalize().dot(look);

			if (alignment > bestAlignment) {
				bestAlignment = alignment;
				best = candidate;
			}
		}

		return best;
	}

	public static boolean isCarryable(Entity entity) {
		CarryConfig config = CarryConfig.get();

		if (entity.isPassenger() || entity.isRemoved()) {
			return false;
		}

		if (entity instanceof Player) {
			return false;
		}

		if (!(entity instanceof Mob mob)) {
			return false;
		}

		if (mob instanceof Enemy && !config.allowHostileMobs) {
			return false;
		}

		if (mob.getBbWidth() > config.maxWidth || mob.getBbHeight() > config.maxHeight) {
			return false;
		}

		String id = EntityType.getKey(mob.getType()).toString();
		return !config.blacklist.contains(id) && mob.getType().canSerialize();
	}

	/** Serialises the entity onto the player and removes it from the world. */
	public static boolean pickUp(ServerPlayer player, Entity entity) {
		if (isCarrying(player) || !isCarryable(entity)) {
			return false;
		}

		ServerLevel level = player.level();
		entity.ejectPassengers();
		entity.stopRiding();

		if (entity instanceof Leashable leashable) {
			leashable.removeLeash();
		}

		CompoundTag tag;

		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), CarryMoby.LOGGER)) {
			TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());

			if (!entity.save(output)) {
				return false;
			}

			tag = output.buildResult();
		}

		holder(player).carrymoby$setCarried(tag);
		entity.discard();
		sync(player);

		level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7F, 1.3F);
		player.displayClientMessage(Component.translatable("carrymoby.message.picked_up", entity.getDisplayName()), true);
		return true;
	}

	/**
	 * Puts the carried mob back into the world in front of the player.
	 *
	 * @param inFront when false the mob is dropped straight onto the player's feet, which is what
	 *                admin commands and forced drops want.
	 */
	public static boolean release(ServerPlayer player, boolean inFront) {
		CompoundTag tag = getCarried(player);

		if (tag == null) {
			return false;
		}

		ServerLevel level = player.level();
		Optional<Entity> created;

		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), CarryMoby.LOGGER)) {
			ValueInput input = TagValueInput.create(reporter, level.registryAccess(), tag);
			created = EntityType.create(input, level, EntitySpawnReason.LOAD);
		}

		// Clear the slot even if the entity could not be rebuilt, otherwise the player is stuck
		// carrying data no version of the game can read any more.
		holder(player).carrymoby$setCarried(null);
		sync(player);

		if (created.isEmpty()) {
			CarryMoby.LOGGER.warn("Dropping unreadable carried mob data of {}", player.getGameProfile().name());
			player.displayClientMessage(Component.translatable("carrymoby.message.lost"), true);
			return false;
		}

		Entity entity = created.get();
		Vec3 pos = dropPosition(player, entity, inFront);
		entity.snapTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0F);
		entity.setDeltaMovement(Vec3.ZERO);
		entity.fallDistance = 0.0;

		if (entity instanceof Mob mob) {
			mob.setNoActionTime(0);
		}

		if (!level.addFreshEntity(entity)) {
			return false;
		}

		level.playSound(null, BlockPos.containing(pos), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7F, 0.8F);
		player.displayClientMessage(Component.translatable("carrymoby.message.released", entity.getDisplayName()), true);
		return true;
	}

	/** Prefers a free spot in front of the player, and falls back to the player's own position. */
	private static Vec3 dropPosition(ServerPlayer player, Entity entity, boolean inFront) {
		Vec3 feet = player.position();

		if (inFront) {
			Vec3 look = player.getViewVector(1.0F);
			Vec3 candidate = feet.add(new Vec3(look.x, 0.0, look.z).normalize().scale(1.0 + entity.getBbWidth()));
			AABB box = entity.getDimensions(entity.getPose()).makeBoundingBox(candidate);

			if (player.level().noCollision(entity, box)) {
				return candidate;
			}
		}

		return feet;
	}

	private static final Identifier SLOWNESS_ID = CarryMoby.id("carrying");

	/** Applies or clears the optional movement penalty. Re-checked on every sync, so it also
	 * survives the attribute reset that comes with respawning. */
	private static void updateSlowness(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);

		if (speed == null) {
			return;
		}

		double factor = CarryConfig.get().slownessFactor;

		if (isCarrying(player) && factor > 0.0) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(SLOWNESS_ID, -factor, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else {
			speed.removeModifier(SLOWNESS_ID);
		}
	}

	/** Tells the owner and everyone who can see them what is currently riding those shoulders. */
	public static void sync(ServerPlayer player) {
		updateSlowness(player);

		CarrySyncPayload payload = new CarrySyncPayload(player.getId(), Optional.ofNullable(getCarried(player)));

		for (ServerPlayer viewer : PlayerLookup.tracking(player)) {
			ServerPlayNetworking.send(viewer, payload);
		}

		ServerPlayNetworking.send(player, payload);
	}

	/** Tells one viewer to forget about {@code carrier}, used when they stop being tracked. */
	public static void clearFor(ServerPlayer viewer, ServerPlayer carrier) {
		ServerPlayNetworking.send(viewer, new CarrySyncPayload(carrier.getId(), Optional.empty()));
	}

	/** Sends {@code carrier}'s state to a single viewer, used when tracking starts. */
	public static void syncTo(ServerPlayer viewer, ServerPlayer carrier) {
		ServerPlayNetworking.send(viewer, new CarrySyncPayload(carrier.getId(), Optional.ofNullable(getCarried(carrier))));
	}
}
