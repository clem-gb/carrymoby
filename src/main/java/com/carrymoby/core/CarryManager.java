package com.carrymoby.core;

import com.carrymoby.net.CarrySyncPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.carrymoby.CarryMoby;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
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
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
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

	/** Minimum delay between two key presses, in ticks. Stops a client from spamming the packet. */
	private static final int TOGGLE_COOLDOWN = 5;

	/**
	 * The key press: grab what the player is looking at, or put down what they already hold.
	 *
	 * <p>The packet carries no data, but it can still be sent at any time by a modified client,
	 * so every state the real key cannot be pressed in is refused here.
	 */
	public static void toggle(ServerPlayer player) {
		if (player.hasDisconnected() || !player.isAlive() || player.isSpectator() || player.isSleeping()) {
			return;
		}

		// Keys do not fire while a screen is open. Refusing here also keeps a player from
		// pulling a villager or a llama out from under its own trading or inventory menu.
		if (player.containerMenu != player.inventoryMenu) {
			return;
		}

		int now = player.level().getServer().getTickCount();
		CarriedMobHolder holder = holder(player);

		if (now - holder.carrymoby$getLastToggle() < TOGGLE_COOLDOWN) {
			return;
		}

		holder.carrymoby$setLastToggle(now);

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

	@Nullable
	private static Entity pick(ServerPlayer player) {
		double range = CarryConfig.get().pickupRange;
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		Vec3 end = eye.add(look.scale(range));

		// Stop the ray at the first block, like the vanilla crosshair does, so mobs cannot be
		// grabbed through walls, floors or closed doors.
		HitResult blockHit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		double reachSqr = range * range;

		if (blockHit.getType() != HitResult.Type.MISS) {
			end = blockHit.getLocation();
			reachSqr = eye.distanceToSqr(end);
		}

		AABB box = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, entity -> canCarry(player, entity), reachSqr);

		return hit == null ? null : hit.getEntity();
	}

	/**
	 * {@link #isCarryable} plus the checks that depend on who is asking: nobody gets to walk off
	 * with a mob another player owns, leads, rides or is trading with.
	 */
	public static boolean canCarry(ServerPlayer player, Entity entity) {
		if (!isCarryable(entity)) {
			return false;
		}

		if (!CarryConfig.get().allowCarryingOthersPets && entity instanceof OwnableEntity ownable) {
			EntityReference<?> owner = ownable.getOwnerReference();

			if (owner != null && !owner.getUUID().equals(player.getUUID())) {
				return false;
			}
		}

		if (entity instanceof Leashable leashable && leashable.getLeashHolder() instanceof Player holder && holder != player) {
			return false;
		}

		if (entity.getPassengers().stream().anyMatch(passenger -> passenger instanceof Player && passenger != player)) {
			return false;
		}

		return !(entity instanceof AbstractVillager villager && villager.isTrading());
	}

	public static boolean isCarryable(Entity entity) {
		CarryConfig config = CarryConfig.get();

		if (entity.isPassenger() || entity.isRemoved() || !entity.isAlive()) {
			return false;
		}

		// Map makers and servers mark their shop keepers and NPCs invulnerable: leave them be.
		if (entity.isInvulnerable()) {
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
		if (isCarrying(player) || !canCarry(player, entity)) {
			return false;
		}

		ServerLevel level = player.level();
		entity.ejectPassengers();
		entity.stopRiding();

		// dropLeash, not removeLeash: the latter deletes the lead item instead of giving it back.
		if (entity instanceof Leashable leashable && leashable.isLeashed()) {
			leashable.dropLeash();
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

		if (created.isEmpty()) {
			CarryMoby.LOGGER.warn("Dropping unreadable carried mob data of {}", player.getGameProfile().name());
			player.displayClientMessage(Component.translatable("carrymoby.message.lost"), true);
			sync(player);
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
			// Usually another mod vetoed the spawn. Keep the mob on the shoulders rather than
			// deleting it, unless an entity with the same UUID already exists, in which case
			// holding on to this copy would amount to a duplicate.
			if (level.getEntity(entity.getUUID()) == null) {
				holder(player).carrymoby$setCarried(tag);
			} else {
				CarryMoby.LOGGER.warn("Carried mob of {} already exists in the world, discarding the copy", player.getGameProfile().name());
			}

			player.displayClientMessage(Component.translatable("carrymoby.message.release_failed"), true);
			sync(player);
			return false;
		}

		sync(player);

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

			// The spot can be free while a wall stands between it and the player: without the
			// ray, a wide enough mob would be pushed straight through a one block thick wall.
			Vec3 lift = new Vec3(0.0, Math.min(entity.getBbHeight(), player.getBbHeight()) * 0.5, 0.0);
			ClipContext path = new ClipContext(feet.add(lift), candidate.add(lift), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);

			if (player.level().noCollision(entity, box)
					&& player.level().getWorldBorder().isWithinBounds(box)
					&& player.level().clip(path).getType() == HitResult.Type.MISS) {
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

		CarrySyncPayload payload = new CarrySyncPayload(player.getId(), displayTag(getCarried(player)));

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
		ServerPlayNetworking.send(viewer, new CarrySyncPayload(carrier.getId(), displayTag(getCarried(carrier))));
	}

	/**
	 * Keys that the client does not need to draw the mob but that would give away where it
	 * came from (bed, workstation, hive, the spot it was picked up at, who hurt it...) to every
	 * player who happens to see the carrier, or that can make the packet needlessly large.
	 */
	private static final List<String> PRIVATE_KEYS = List.of(
			"Pos", "Motion", "Brain", "Gossips", "Offers", "Inventory", "Items", "Leash", "leash",
			"home_pos", "sleeping_pos", "hive_pos", "flower_pos", "patrol_target", "wander_target",
			"bound_pos", "last_hurt_by_player", "last_hurt_by_mob", "AngryAt", "angry_at", "Trusted",
			"DeathLootTable", "DeathLootTableSeed"
	);

	/** Custom payloads above 1 MiB kick the receiving player, keep well below that. */
	private static final int MAX_DISPLAY_BYTES = 256 * 1024;

	/** Builds the copy of the carried mob that is sent to clients, which only draw it. */
	public static Optional<CompoundTag> displayTag(@Nullable CompoundTag tag) {
		if (tag == null) {
			return Optional.empty();
		}

		CompoundTag display = tag.copy();
		PRIVATE_KEYS.forEach(display::remove);
		// Block positions are stored as three ints: catch the ones the list does not know about.
		display.entrySet().removeIf(entry -> entry.getValue() instanceof IntArrayTag array && array.size() == 3);

		if (display.sizeInBytes() > MAX_DISPLAY_BYTES) {
			// A mob holding a shulker box full of books: drop the gear rather than the player.
			display.remove("equipment");
		}

		if (display.sizeInBytes() > MAX_DISPLAY_BYTES) {
			CompoundTag minimal = new CompoundTag();
			minimal.putString("id", tag.getStringOr("id", ""));
			return Optional.of(minimal);
		}

		return Optional.of(display);
	}
}
