package com.carrymoby.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.carrymoby.CarryMoby;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Simple JSON config, loaded once at startup and reloadable with {@code /carrymoby reload}. */
public final class CarryConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("carrymoby.json");

	private static CarryConfig instance = new CarryConfig();

	/** Allow carrying hostile mobs (zombies, creepers...). */
	public boolean allowHostileMobs = false;
	/** Maximum hitbox width of a carryable mob, in blocks. */
	public double maxWidth = 1.0;
	/** Maximum hitbox height of a carryable mob, in blocks. */
	public double maxHeight = 1.5;
	/** Reach, in blocks, when grabbing a mob. */
	public double pickupRange = 4.5;
	/** Keep the mob through death and respawn. */
	public boolean keepOnDeath = true;
	/** Movement speed penalty while carrying, as a fraction (0.15 = -15%). */
	public double slownessFactor = 0.0;
	/** Entity ids that can never be carried, whatever the other settings say. */
	public List<String> blacklist = List.of(
			"minecraft:ender_dragon",
			"minecraft:wither",
			"minecraft:warden"
	);

	public static CarryConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				instance = GSON.fromJson(Files.readString(PATH), CarryConfig.class);
				if (instance == null) {
					instance = new CarryConfig();
				}
			} else {
				instance = new CarryConfig();
				save();
			}
		} catch (Exception e) {
			CarryMoby.LOGGER.error("Could not read {}, falling back to defaults", PATH, e);
			instance = new CarryConfig();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(instance));
		} catch (IOException e) {
			CarryMoby.LOGGER.error("Could not write {}", PATH, e);
		}
	}
}
