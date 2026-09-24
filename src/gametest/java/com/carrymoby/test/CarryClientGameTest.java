package com.carrymoby.test;

import com.carrymoby.client.CarriedMobCache;
import com.carrymoby.client.CarryMobyClient;
import com.carrymoby.core.CarryManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.sheep.Sheep;

/**
 * Walks the whole feature in a real client, through the real key and network: miss, grab a
 * sheep, die, respawn, change dimension, put it back down. Checks both the server state and
 * what the client draws. The rules themselves are covered by {@link CarryServerGameTest}.
 * Screenshots land in {@code build/run/clientGameTest/screenshots}.
 */
public class CarryClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamerule doImmediateRespawn false");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode creative @a");
			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 35");
			context.waitTicks(2);
			server.runCommand("execute as @a at @s run summon minecraft:sheep ~ ~ ~2");
			context.waitTicks(5);
			// Aim from the client: in singleplayer the client owns the camera and would
			// immediately overwrite a rotation set by /tp.
			context.runOnClient(client -> {
				client.player.setYRot(0.0F);
				client.player.setXRot(35.0F);
				client.player.setOldPosAndRot();
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(5);
			context.takeScreenshot("01-before-pickup");

			context.runOnClient(client -> {
				if (CarryMobyClient.CARRY_KEY.getCategory() != CarryMobyClient.CATEGORY) {
					throw new AssertionError("The carry key should sit in its own CarryMoby category");
				}
			});

			// Looking at the sky: there is no forgiving cone any more, so nothing gets picked up.
			context.runOnClient(client -> {
				client.player.setXRot(-60.0F);
				client.player.setOldPosAndRot();
			});
			context.waitTicks(2);
			context.getInput().pressKey(CarryMobyClient.CARRY_KEY);
			context.waitTicks(10);
			assertCarrying(server, false, "after pressing the key while looking at the sky");
			assertMobsInWorld(server, 1, "after missing");

			context.runOnClient(client -> {
				client.player.setXRot(35.0F);
				client.player.setOldPosAndRot();
			});
			context.waitTicks(2);

			context.getInput().pressKey(CarryMobyClient.CARRY_KEY);
			context.waitTicks(10);
			context.takeScreenshot("02-carrying");
			assertCarrying(server, true, "after pressing the carry key");
			assertMobsInWorld(server, 0, "after pickup");
			assertClientDraws(context, true, "after pickup");

			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 90 10");
			context.runOnClient(client -> {
				client.player.setYRot(90.0F);
				client.player.setXRot(10.0F);
				client.player.setOldPosAndRot();
			});
			context.waitTicks(5);
			context.takeScreenshot("02b-carrying-side");

			server.runCommand("kill @a");
			context.waitTicks(10);
			context.clickScreenButton("deathScreen.respawn");
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			context.takeScreenshot("03-after-respawn");
			assertCarrying(server, true, "after dying and respawning");
			assertClientDraws(context, true, "after dying and respawning");

			server.runCommand("execute in minecraft:the_nether run tp @a 0 80 0");
			context.waitTicks(40);
			context.takeScreenshot("04-after-dimension-change");
			assertCarrying(server, true, "after changing dimension");
			assertClientDraws(context, true, "after changing dimension");

			context.getInput().pressKey(CarryMobyClient.CARRY_KEY);
			context.waitTicks(10);
			context.takeScreenshot("05-released");
			assertCarrying(server, false, "after putting the mob down");
			assertMobsInWorld(server, 1, "after release");
			assertClientDraws(context, false, "after release");
		}
	}

	/**
	 * Checks the display copy the client renders on the local player's shoulders. It must be a
	 * sheep built for the level the client is in now: one built for a previous level would
	 * keep that whole level in memory.
	 */
	private static void assertClientDraws(ClientGameTestContext context, boolean expected, String when) {
		context.runOnClient(client -> {
			Entity drawn = CarriedMobCache.get(client.player.getId());

			if (!expected) {
				if (drawn != null) {
					throw new AssertionError("The client still draws a carried " + drawn.getType() + " " + when);
				}

				return;
			}

			if (!(drawn instanceof Sheep)) {
				throw new AssertionError("The client should draw a sheep on the shoulders " + when + ", got " + drawn);
			}

			if (drawn.level() != client.level) {
				throw new AssertionError("The drawn sheep belongs to a stale client level " + when);
			}
		});
	}

	private static void assertCarrying(TestServerContext server, boolean expected, String when) {
		boolean actual = server.computeOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			return CarryManager.isCarrying(player);
		});

		if (actual != expected) {
			throw new AssertionError("Expected carrying=" + expected + " " + when + ", got " + actual);
		}
	}

	private static void assertMobsInWorld(TestServerContext server, int expected, String when) {
		int actual = server.computeOnServer(minecraftServer -> {
			int count = 0;

			for (net.minecraft.server.level.ServerLevel level : minecraftServer.getAllLevels()) {
				for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
					if (entity.getType() == EntityType.SHEEP) {
						count++;
					}
				}
			}

			return count;
		});

		if (actual != expected) {
			throw new AssertionError("Expected " + expected + " sheep in the world " + when + ", found " + actual);
		}
	}
}
