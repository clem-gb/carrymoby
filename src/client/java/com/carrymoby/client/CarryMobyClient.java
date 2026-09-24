package com.carrymoby.client;

import com.carrymoby.CarryMoby;
import com.carrymoby.client.render.CarriedMobLayer;
import com.carrymoby.net.CarrySyncPayload;
import com.carrymoby.net.ToggleCarryPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.lwjgl.glfw.GLFW;

public class CarryMobyClient implements ClientModInitializer {
	/** Own section in Options → Controls, titled by {@code key.category.carrymoby.carrymoby}. */
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(CarryMoby.id("carrymoby"));

	public static final KeyMapping CARRY_KEY = new KeyMapping(
			"key.carrymoby.toggle",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_C,
			CATEGORY
	);

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(CARRY_KEY);

		// Already called on the client thread; deferring it could let a stale packet land after
		// the disconnect cleared the cache.
		ClientPlayNetworking.registerGlobalReceiver(CarrySyncPayload.ID, (payload, context) ->
				CarriedMobCache.put(payload.entityId(), payload.mob().orElse(null)));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CarriedMobCache.tick();

			while (CARRY_KEY.consumeClick()) {
				if (client.player != null) {
					ClientPlayNetworking.send(ToggleCarryPayload.INSTANCE);
				}
			}
		});

		// Carried mobs are keyed by entity id, which is only meaningful within one connection.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CarriedMobCache.clear());

		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?>) {
				@SuppressWarnings("unchecked")
				RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> parent =
						(RenderLayerParent<net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel>) renderer;
				helper.register(new CarriedMobLayer(parent));
			}
		});

		CarryMoby.LOGGER.info("CarryMoby client ready");
	}
}
