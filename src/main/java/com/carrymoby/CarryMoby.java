package com.carrymoby;

import com.carrymoby.command.CarryCommand;
import com.carrymoby.core.CarryConfig;
import com.carrymoby.core.CarryManager;
import com.carrymoby.net.CarrySyncPayload;
import com.carrymoby.net.ToggleCarryPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CarryMoby implements ModInitializer {
	public static final String MOD_ID = "carrymoby";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		CarryConfig.load();

		PayloadTypeRegistry.playC2S().register(ToggleCarryPayload.ID, ToggleCarryPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CarrySyncPayload.ID, CarrySyncPayload.CODEC);

		// Fabric already runs this on the server thread. Deferring it once more with execute()
		// would let it land after the player disconnected and their data was saved, which
		// would delete the mob they were picking up.
		ServerPlayNetworking.registerGlobalReceiver(ToggleCarryPayload.ID,
				(payload, context) -> CarryManager.toggle(context.player()));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CarryCommand.register(dispatcher));

		// The mob lives in the player's save data, so it already survives teleports, dimension
		// changes and logouts. These hooks only make sure every client is told about it again
		// whenever the player entity is rebuilt or comes into view.
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			if (alive || CarryConfig.get().keepOnDeath) {
				CarryManager.holder(newPlayer).carrymoby$setCarried(CarryManager.getCarried(oldPlayer));
			} else if (CarryManager.isCarrying(oldPlayer)) {
				// Death drop: put the mob back where the player died rather than deleting it.
				CarryManager.release(oldPlayer, false);
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> CarryManager.sync(newPlayer));
		ServerPlayerEvents.JOIN.register(CarryManager::sync);

		EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
			if (trackedEntity instanceof ServerPlayer carrier) {
				CarryManager.syncTo(player, carrier);
			}
		});

		// Entity ids are recycled, so a viewer that stops seeing a carrier must forget them
		// rather than risk drawing their mob on whoever inherits the id.
		EntityTrackingEvents.STOP_TRACKING.register((trackedEntity, player) -> {
			if (trackedEntity instanceof ServerPlayer carrier) {
				CarryManager.clearFor(player, carrier);
			}
		});

		LOGGER.info("CarryMoby ready");
	}
}
