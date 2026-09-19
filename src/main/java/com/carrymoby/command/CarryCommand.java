package com.carrymoby.command;

import com.carrymoby.core.CarryConfig;
import com.carrymoby.core.CarryManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/** {@code /carrymoby drop|info|reload} — an escape hatch and a debugging aid. */
public final class CarryCommand {
	private CarryCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("carrymoby")
				.then(Commands.literal("drop")
						.executes(context -> drop(context.getSource(), List.of(context.getSource().getPlayerOrException())))
						.then(Commands.argument("targets", EntityArgument.players())
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(context -> drop(context.getSource(), EntityArgument.getPlayers(context, "targets")))))
				.then(Commands.literal("info")
						.executes(context -> info(context.getSource())))
				.then(Commands.literal("reload")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(context -> {
							CarryConfig.load();
							context.getSource().sendSuccess(() -> Component.translatable("carrymoby.command.reloaded"), true);
							return 1;
						})));
	}

	private static int drop(CommandSourceStack source, Collection<ServerPlayer> targets) {
		int dropped = 0;

		for (ServerPlayer player : targets) {
			if (CarryManager.release(player, false)) {
				dropped++;
			}
		}

		int count = dropped;
		source.sendSuccess(() -> Component.translatable("carrymoby.command.dropped", count), true);
		return dropped;
	}

	private static int info(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		CompoundTag tag = CarryManager.getCarried(player);

		if (tag == null) {
			source.sendSuccess(() -> Component.translatable("carrymoby.command.empty"), false);
		} else {
			String id = tag.getStringOr("id", "?");
			source.sendSuccess(() -> Component.translatable("carrymoby.command.carrying", id), false);
		}

		return 1;
	}
}
