package com.carrymoby.client;

import com.carrymoby.CarryMoby;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Display-only copies of the mobs players are carrying, keyed by the carrier's entity id.
 *
 * <p>These entities are never added to the world: they exist purely so the renderer has
 * something real to extract a render state from, which is what gives us correct variants,
 * babies, collars, saddles and name tags for free.
 */
public final class CarriedMobCache {
	/** The NBT the server sent, kept so the display copy can be rebuilt for a new level. */
	private static final Int2ObjectMap<CompoundTag> TAGS = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectMap<Entity> CARRIED = new Int2ObjectOpenHashMap<>();

	private CarriedMobCache() {
	}

	public static void put(int carrierId, @Nullable CompoundTag tag) {
		CARRIED.remove(carrierId);

		if (tag == null) {
			TAGS.remove(carrierId);
		} else {
			TAGS.put(carrierId, tag);
		}
	}

	/**
	 * The display copy is built lazily, for whatever level the client is in right now. A
	 * dimension change or respawn swaps the client level, and a copy built for the old one
	 * would keep that whole level alive in memory, so it is rebuilt when the level changes.
	 */
	@Nullable
	public static Entity get(int carrierId) {
		ClientLevel level = Minecraft.getInstance().level;
		Entity entity = CARRIED.get(carrierId);

		if (level == null || (entity != null && entity.level() == level)) {
			return entity;
		}

		CARRIED.remove(carrierId);
		CompoundTag tag = TAGS.get(carrierId);

		if (tag == null) {
			return null;
		}

		entity = create(level, tag);

		if (entity == null) {
			// Unreadable data: forget it instead of retrying every frame.
			TAGS.remove(carrierId);
		} else {
			CARRIED.put(carrierId, entity);
		}

		return entity;
	}

	@Nullable
	private static Entity create(ClientLevel level, CompoundTag tag) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(CarryMoby.LOGGER)) {
			ValueInput input = TagValueInput.create(reporter, level.registryAccess(), tag);
			Optional<Entity> created = EntityType.create(input, level, EntitySpawnReason.LOAD);

			// The server only ever sends mobs; anything else would come from a server trying
			// to make the renderer draw something it was never meant to.
			if (created.isPresent() && created.get() instanceof Mob mob) {
				mob.setOldPosAndRot();
				return mob;
			}
		} catch (Exception e) {
			CarryMoby.LOGGER.warn("Could not rebuild a carried mob for rendering", e);
		}

		return null;
	}

	/**
	 * Advances the animation clock of every displayed mob. The entities are never ticked for
	 * real -- they are outside the world, so running their AI would be both wrong and unsafe.
	 */
	public static void tick() {
		for (Entity entity : CARRIED.values()) {
			entity.tickCount++;
		}
	}

	public static void clear() {
		CARRIED.clear();
		TAGS.clear();
	}
}
