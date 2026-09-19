package com.carrymoby.test;

import com.carrymoby.client.CarryMobyClient;
import com.carrymoby.core.CarryManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

/**
 * Walks the whole feature in a real client: grab a chicken, die, respawn, change dimension,
 * put it back down. Screenshots land in {@code run/screenshots}.
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
			server.runCommand("execute as @a at @s run summon minecraft:chicken ~ ~ ~2");
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

			context.getInput().pressKey(CarryMobyClient.CARRY_KEY);
			context.waitTicks(10);
			context.takeScreenshot("02-carrying");
			assertCarrying(server, true, "after pressing the carry key");
			assertChickensInWorld(server, 0, "after pickup");

			server.runCommand("kill @a");
			context.waitTicks(10);
			context.clickScreenButton("deathScreen.respawn");
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			context.takeScreenshot("03-after-respawn");
			assertCarrying(server, true, "after dying and respawning");

			server.runCommand("execute in minecraft:the_nether run tp @a 0 80 0");
			context.waitTicks(40);
			context.takeScreenshot("04-after-dimension-change");
			assertCarrying(server, true, "after changing dimension");

			context.getInput().pressKey(CarryMobyClient.CARRY_KEY);
			context.waitTicks(10);
			context.takeScreenshot("05-released");
			assertCarrying(server, false, "after putting the mob down");
			assertChickensInWorld(server, 1, "after release");
		}
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

	private static void assertChickensInWorld(TestServerContext server, int expected, String when) {
		int actual = server.computeOnServer(minecraftServer -> {
			int count = 0;

			for (net.minecraft.server.level.ServerLevel level : minecraftServer.getAllLevels()) {
				for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
					if (entity.getType() == EntityType.CHICKEN) {
						count++;
					}
				}
			}

			return count;
		});

		if (actual != expected) {
			throw new AssertionError("Expected " + expected + " chicken(s) in the world " + when + ", found " + actual);
		}
	}
}
