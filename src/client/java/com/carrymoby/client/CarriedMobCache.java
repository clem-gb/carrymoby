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
	private static final Int2ObjectMap<Entity> CARRIED = new Int2ObjectOpenHashMap<>();

	private CarriedMobCache() {
	}

	public static void put(int carrierId, @Nullable CompoundTag tag) {
		if (tag == null) {
			CARRIED.remove(carrierId);
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return;
		}

		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(CarryMoby.LOGGER)) {
			ValueInput input = TagValueInput.create(reporter, level.registryAccess(), tag);
			Optional<Entity> created = EntityType.create(input, level, EntitySpawnReason.LOAD);

			if (created.isEmpty()) {
				CARRIED.remove(carrierId);
			} else {
				Entity entity = created.get();
				entity.setOldPosAndRot();
				CARRIED.put(carrierId, entity);
			}
		} catch (Exception e) {
			CarryMoby.LOGGER.warn("Could not rebuild a carried mob for rendering", e);
			CARRIED.remove(carrierId);
		}
	}

	@Nullable
	public static Entity get(int carrierId) {
		return CARRIED.get(carrierId);
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
	}
}
