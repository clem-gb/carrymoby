package com.carrymoby.test;

import com.carrymoby.core.CarryConfig;
import com.carrymoby.core.CarryManager;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side rules of CarryMoby, one scenario per test, run headless with
 * {@code ./gradlew runGameTest}.
 *
 * <p>Every test drives the mod through {@link CarryManager#toggle}, the exact entry point the
 * key packet reaches, so what is checked here is what a (possibly modified) client can do.
 *
 * <p>Layout, in structure coordinates: a stone floor at y = 0, the player standing at
 * (1.5, 1, 1.5) and the mob three blocks further along +Z.
 */
public class CarryServerGameTest {
	private static final Vec3 PLAYER_POS = new Vec3(1.5, 1.0, 1.5);
	private static final Vec3 MOB_POS = new Vec3(1.5, 1.0, 4.5);

	// ---------------------------------------------------------------- pick up and put down

	@GameTest
	public void pickUpAndPutDown(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		UUID uuid = sheep.getUUID();
		aimAt(player, sheep);

		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "the sheep in the crosshair should be picked up");
		helper.assertTrue(sheep.isRemoved(), "the picked up sheep should leave the world");
		helper.assertValueEqual(CarryManager.getCarried(player).getStringOr("id", ""), "minecraft:sheep", "carried entity type");

		helper.runAfterDelay(10, () -> {
			player.snapTo(helper.absoluteVec(PLAYER_POS), 0.0F, 0.0F);
			CarryManager.toggle(player);
			helper.assertTrue(!CarryManager.isCarrying(player), "pressing again should put the sheep down");

			Entity released = helper.getLevel().getEntity(uuid);
			helper.assertTrue(released instanceof Sheep, "the same sheep, with the same UUID, should be back");
			// Put down in front: 1 block plus its own width along the look direction.
			Vec3 expected = helper.absoluteVec(PLAYER_POS.add(0.0, 0.0, 1.0 + released.getBbWidth()));
			helper.assertTrue(released.position().distanceTo(expected) < 0.01,
					"the sheep should land in front of the player, got " + helper.relativeVec(released.position()));
			finish(helper, player);
		});
	}

	@GameTest
	public void carriedMobKeepsItsData(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		sheep.setColor(DyeColor.RED);
		sheep.setAge(-12000);
		sheep.setHealth(3.0F);
		sheep.setCustomName(Component.literal("Dolly"));
		UUID uuid = sheep.getUUID();
		aimAt(player, sheep);

		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "the sheep should be picked up");
		CarryManager.release(player, false);

		if (!(helper.getLevel().getEntity(uuid) instanceof Sheep back)) {
			throw helper.assertionException(Component.literal("the sheep did not come back"));
		}

		helper.assertValueEqual(back.getColor(), DyeColor.RED, "colour");
		helper.assertTrue(back.isBaby(), "should still be a lamb");
		helper.assertValueEqual(back.getHealth(), 3.0F, "health");
		helper.assertValueEqual(back.getCustomName().getString(), "Dolly", "name");
		finish(helper, player);
	}

	@GameTest
	public void carriedMobIsSavedWithThePlayer(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		UUID uuid = sheep.getUUID();
		aimAt(player, sheep);
		CarryManager.toggle(player);

		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		player.saveWithoutId(output);
		CompoundTag saved = output.buildResult();
		helper.assertTrue(saved.contains("CarryMoby"), "the carried mob should be part of the player's save data");

		// Load it into a brand new player object, as a relog or a server restart would.
		ServerPlayer reloaded = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), player.getGameProfile(), player.clientInformation());
		reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
		helper.assertTrue(CarryManager.isCarrying(reloaded), "the reloaded player should still carry the sheep");
		helper.assertValueEqual(CarryManager.getCarried(reloaded).read("UUID", UUIDUtil.CODEC).orElse(null), uuid, "UUID of the reloaded mob");
		finish(helper, player);
	}

	@GameTest
	public void putDownFallsBackToFeetAgainstAWall(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Pig pig = helper.spawn(EntityType.PIG, MOB_POS);
		UUID uuid = pig.getUUID();
		aimAt(player, pig);
		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "the pig should be picked up");

		// A one block thick wall right in front of the player's nose. The spot behind it is
		// free, which used to be enough to push a pig straight through.
		wall(helper, 2, Blocks.STONE);
		player.snapTo(helper.absoluteVec(new Vec3(1.5, 1.0, 1.69)), 0.0F, 0.0F);
		CarryManager.release(player, true);

		Entity released = helper.getLevel().getEntity(uuid);
		helper.assertTrue(released != null, "the pig should be back in the world");
		helper.assertTrue(helper.relativeVec(released.position()).z < 2.0,
				"the pig must stay on the player's side of the wall, got " + helper.relativeVec(released.position()));
		finish(helper, player);
	}

	// ---------------------------------------------------------------- aiming

	@GameTest
	public void cannotPickUpThroughAWall(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		wall(helper, 3, Blocks.GLASS);
		aimAt(player, sheep);

		CarryManager.toggle(player);
		helper.assertTrue(!CarryManager.isCarrying(player), "a sheep behind glass must not be grabbed");
		helper.assertTrue(!sheep.isRemoved(), "the sheep behind the wall must stay in the world");
		finish(helper, player);
	}

	@GameTest
	public void mustAimAtTheMob(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		// 20 degrees to the side: the old forgiving cone would have grabbed it anyway.
		Vec3 centre = sheep.getBoundingBox().getCenter();
		Vec3 eye = player.getEyePosition();
		Vec3 offAxis = eye.add(centre.subtract(eye).yRot((float) Math.toRadians(20.0)));
		player.lookAt(EntityAnchorArgument.Anchor.EYES, offAxis);

		CarryManager.toggle(player);
		helper.assertTrue(!CarryManager.isCarrying(player), "a sheep outside the crosshair must not be grabbed");
		finish(helper, player);
	}

	@GameTest
	public void cannotPickUpOutOfRange(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(1.5, 1.0, 7.5));
		aimAt(player, sheep);

		CarryManager.toggle(player);
		helper.assertTrue(!CarryManager.isCarrying(player), "a sheep 6 blocks away is beyond the 4.5 block reach");
		finish(helper, player);
	}

	// ---------------------------------------------------------------- which mobs

	@GameTest
	public void refusesMobsOutsideTheConfig(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		CarryConfig config = CarryConfig.get();

		assertRefused(helper, player, helper.spawn(EntityType.HORSE, MOB_POS), "a horse is wider than maxWidth");
		assertRefused(helper, player, helper.spawn(EntityType.SILVERFISH, MOB_POS), "hostile mobs are off by default");

		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		List<String> blacklist = config.blacklist;
		try {
			config.blacklist = List.of("minecraft:sheep");
			assertRefused(helper, player, sheep, "blacklisted mobs are never carryable");
		} finally {
			config.blacklist = blacklist;
		}
		assertAccepted(helper, player, sheep, "the same sheep once off the blacklist");

		boolean hostiles = config.allowHostileMobs;
		try {
			config.allowHostileMobs = true;
			assertAccepted(helper, player, helper.spawn(EntityType.SILVERFISH, MOB_POS), "hostile mobs once allowed");
		} finally {
			config.allowHostileMobs = hostiles;
		}

		finish(helper, player);
	}

	@GameTest
	public void refusesProtectedMobs(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);

		Sheep invulnerable = helper.spawn(EntityType.SHEEP, MOB_POS);
		invulnerable.setInvulnerable(true);
		assertRefused(helper, player, invulnerable, "invulnerable mobs (server NPCs) are off limits");
		invulnerable.setInvulnerable(false);
		assertAccepted(helper, player, invulnerable, "the same sheep once vulnerable");

		Sheep dying = helper.spawn(EntityType.SHEEP, MOB_POS);
		dying.setHealth(0.0F);
		assertRefused(helper, player, dying, "a mob playing its death animation cannot be picked up");

		finish(helper, player);
	}

	@GameTest
	public void refusesSomeoneElsesPet(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);

		Wolf theirs = helper.spawn(EntityType.WOLF, MOB_POS);
		theirs.setTame(true, false);
		theirs.setOwnerReference(EntityReference.of(UUID.randomUUID()));
		theirs.setOrderedToSit(false);
		assertRefused(helper, player, theirs, "another player's wolf");

		Wolf mine = helper.spawn(EntityType.WOLF, MOB_POS);
		mine.tame(player);
		assertAccepted(helper, player, mine, "your own wolf");

		finish(helper, player);
	}

	@GameTest
	public void refusesMobsInAnotherPlayersHands(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		ServerPlayer other = spawnPlayer(helper, new Vec3(5.5, 1.0, 4.5));

		Sheep leashed = helper.spawn(EntityType.SHEEP, MOB_POS);
		leashed.setLeashedTo(other, true);
		assertRefused(helper, player, leashed, "a sheep on someone else's lead");
		leashed.dropLeash();
		assertAccepted(helper, player, leashed, "the same sheep once let go");

		Pig ridden = helper.spawn(EntityType.PIG, MOB_POS);
		other.startRiding(ridden, true, false);
		assertRefused(helper, player, ridden, "a pig someone is riding");
		other.stopRiding();
		assertAccepted(helper, player, ridden, "the same pig once dismounted");

		// A baby: an adult villager is too tall to be carried anyway, which would hide the rule.
		Villager villager = helper.spawn(EntityType.VILLAGER, MOB_POS);
		villager.setBaby(true);
		villager.setTradingPlayer(other);
		assertRefused(helper, player, villager, "a villager in the middle of a trade");
		villager.setTradingPlayer(null);
		assertAccepted(helper, player, villager, "the same villager once the trade is over");

		helper.getLevel().getServer().getPlayerList().remove(other);
		finish(helper, player);
	}

	@GameTest
	public void pickingUpALeashedMobGivesTheLeadBack(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		sheep.setLeashedTo(player, true);
		aimAt(player, sheep);

		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "your own leashed sheep can be picked up");

		AABB around = helper.absoluteAABB(new AABB(0, 0, 0, 8, 4, 8));
		boolean leadDropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).stream()
				.anyMatch(item -> item.getItem().is(Items.LEAD));
		helper.assertTrue(leadDropped, "the lead must be dropped, not deleted");
		finish(helper, player);
	}

	// ---------------------------------------------------------------- forged packets

	@GameTest
	public void ignoresTheKeyInImpossibleStates(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);

		player.setGameMode(GameType.SPECTATOR);
		aimAt(player, sheep);
		CarryManager.toggle(player);
		helper.assertTrue(!CarryManager.isCarrying(player), "spectators fly through walls and must not grab mobs");
		player.setGameMode(GameType.SURVIVAL);

		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory), Component.literal("chest")));
		CarryManager.toggle(player);
		helper.assertTrue(!CarryManager.isCarrying(player), "keys do not work with a menu open");
		player.closeContainer();

		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "back to normal, the key works");
		finish(helper, player);
	}

	@GameTest
	public void rateLimitsTheKey(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		aimAt(player, sheep);

		// An even number of presses: without the cooldown the second one would put it back down.
		CarryManager.toggle(player);
		CarryManager.toggle(player);
		helper.assertTrue(CarryManager.isCarrying(player), "a second press in the same tick is ignored");
		helper.assertTrue(helper.getLevel().getEntity(sheep.getUUID()) == null, "the sheep must not have been put back down");

		helper.runAfterDelay(6, () -> {
			CarryManager.toggle(player);
			helper.assertTrue(!CarryManager.isCarrying(player), "once the cooldown is over the key works again");
			finish(helper, player);
		});
	}

	// ---------------------------------------------------------------- death

	@GameTest
	public void keepsTheMobThroughDeath(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		aimAt(player, sheep);
		CarryManager.toggle(player);

		// Past the key cooldown, so that only being dead can explain a refused press.
		helper.runAfterDelay(6, () -> {
			player.kill(helper.getLevel());
			helper.assertTrue(!player.isAlive(), "the player should be dead");

			CarryManager.toggle(player);
			helper.assertTrue(CarryManager.isCarrying(player), "a dead player cannot put the mob down");

			ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
			helper.assertTrue(CarryManager.isCarrying(respawned), "keepOnDeath: the mob comes back with the player");
			finish(helper, respawned);
		});
	}

	@GameTest
	public void dropsTheMobOnDeathWhenConfigured(GameTestHelper helper) {
		ServerPlayer player = setUp(helper);
		Sheep sheep = helper.spawn(EntityType.SHEEP, MOB_POS);
		UUID uuid = sheep.getUUID();
		aimAt(player, sheep);
		CarryManager.toggle(player);

		CarryConfig config = CarryConfig.get();
		boolean keep = config.keepOnDeath;
		ServerPlayer respawned;

		try {
			config.keepOnDeath = false;
			player.kill(helper.getLevel());
			respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
		} finally {
			config.keepOnDeath = keep;
		}

		helper.assertTrue(!CarryManager.isCarrying(respawned), "the mob should not follow the player");
		Entity dropped = helper.getLevel().getEntity(uuid);
		helper.assertTrue(dropped != null, "the mob should be dropped where the player died, not deleted");
		helper.assertTrue(dropped.position().distanceTo(helper.absoluteVec(PLAYER_POS)) < 1.0, "dropped at the place of death");
		finish(helper, respawned);
	}

	// ---------------------------------------------------------------- what other clients see

	@GameTest
	public void syncedDataHidesPositions(GameTestHelper helper) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:villager");
		tag.put("Pos", new ListTag());
		tag.put("Brain", new CompoundTag());
		tag.put("sleeping_pos", new IntArrayTag(new int[] {120, 64, -3400}));
		tag.put("some_mod_pos", new IntArrayTag(new int[] {1, 2, 3}));
		tag.put("UUID", new IntArrayTag(new int[] {1, 2, 3, 4}));
		tag.putString("CustomName", "Bob");

		CompoundTag shown = CarryManager.displayTag(tag).orElseThrow();
		for (String hidden : List.of("Pos", "Brain", "sleeping_pos", "some_mod_pos")) {
			helper.assertTrue(!shown.contains(hidden), hidden + " must not be sent to other players");
		}
		for (String kept : List.of("id", "UUID", "CustomName")) {
			helper.assertTrue(shown.contains(kept), kept + " is needed to draw the mob");
		}
		helper.assertTrue(tag.contains("Pos"), "the carried data itself must stay untouched");
		helper.succeed();
	}

	@GameTest
	public void syncedDataStaysSmall(GameTestHelper helper) {
		String huge = "x".repeat(400_000);

		CompoundTag heavyGear = new CompoundTag();
		heavyGear.putString("id", "minecraft:fox");
		heavyGear.putString("CustomName", "Rusty");
		CompoundTag equipment = new CompoundTag();
		equipment.putString("mainhand", huge);
		heavyGear.put("equipment", equipment);

		CompoundTag shown = CarryManager.displayTag(heavyGear).orElseThrow();
		helper.assertTrue(!shown.contains("equipment"), "oversized gear is dropped from the synced copy");
		helper.assertTrue(shown.contains("CustomName"), "the rest of the mob is still sent");

		CompoundTag heavyElsewhere = new CompoundTag();
		heavyElsewhere.putString("id", "minecraft:fox");
		heavyElsewhere.putString("modded_blob", huge);
		shown = CarryManager.displayTag(heavyElsewhere).orElseThrow();
		helper.assertValueEqual(shown.keySet(), Set.of("id"), "fallback to the bare entity id");

		helper.assertTrue(CarryManager.displayTag(null).isEmpty(), "nothing carried, nothing sent");
		helper.succeed();
	}

	// ---------------------------------------------------------------- helpers

	/** Lays a stone floor and puts a fresh survival player at {@link #PLAYER_POS}. */
	private static ServerPlayer setUp(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}

		return spawnPlayer(helper, PLAYER_POS);
	}

	/**
	 * A real, connected {@link ServerPlayer}, like vanilla's mock player but whose game mode can
	 * be changed (the vanilla one is stuck in creative).
	 */
	private static ServerPlayer spawnPlayer(GameTestHelper helper, Vec3 pos) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "carry-test"), false);
		ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		// What a real client sends once the world has loaded; until then the player can't be hurt.
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		player.setGameMode(GameType.SURVIVAL);
		player.snapTo(helper.absoluteVec(pos), 0.0F, 0.0F);
		return player;
	}

	/** A wall across the whole structure, blocks z = {@code z}, from y = 1 to 3. */
	private static void wall(GameTestHelper helper, int z, Block block) {
		for (int x = 0; x < 8; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(x, y, z, block);
			}
		}
	}

	private static void aimAt(ServerPlayer player, Entity target) {
		player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
	}

	/**
	 * Goes through {@link CarryManager#pickUp} rather than the key, which has a cooldown: the
	 * rules are the same, and the aiming is covered by its own tests.
	 */
	private static void assertRefused(GameTestHelper helper, ServerPlayer player, Entity mob, String what) {
		helper.assertTrue(!CarryManager.pickUp(player, mob) && !CarryManager.isCarrying(player), "should be refused: " + what);
		helper.assertTrue(!mob.isRemoved(), "a refused mob must stay in the world: " + what);
	}

	/** Picks the mob up and empties the shoulders again, ready for the next check. */
	private static void assertAccepted(GameTestHelper helper, ServerPlayer player, Entity mob, String what) {
		helper.assertTrue(CarryManager.pickUp(player, mob), "should be carryable: " + what);
		CarryManager.holder(player).carrymoby$setCarried(null);
	}

	private static void finish(GameTestHelper helper, ServerPlayer player) {
		helper.getLevel().getServer().getPlayerList().remove(player);
		helper.succeed();
	}
}
