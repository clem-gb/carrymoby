package com.carrymoby.client.render;

import com.carrymoby.client.CarriedMobCache;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.Entity;

/** Draws the carried mob sitting on the player's shoulders. */
public class CarriedMobLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	/** Height of the shoulder line above the feet, in blocks. */
	private static final float SHOULDER_HEIGHT = 1.32F;
	private static final float CROUCHING_SHOULDER_HEIGHT = 1.08F;
	/** Sideways offset, so the mob sits on a shoulder instead of swallowing the head. */
	private static final float SHOULDER_SIDE = -0.42F;
	/** Undoes the {@code translate(0, -1.501, 0)} the living entity renderer applied. */
	private static final float MODEL_ORIGIN_OFFSET = 1.501F;
	/** Mobs bigger than this are shrunk so they look carried rather than worn. */
	private static final float TARGET_HEIGHT = 0.7F;
	private static final float TARGET_WIDTH = 0.5F;

	public CarriedMobLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState playerState, float yRot, float xRot) {
		Entity carried = CarriedMobCache.get(playerState.id);

		if (carried == null || playerState.isInvisible) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		EntityRenderState state = dispatcher.extractEntity(carried, partialTick);
		prepare(state, light);

		float scale = Math.min(1.0F, Math.min(TARGET_HEIGHT / carried.getBbHeight(), TARGET_WIDTH / carried.getBbWidth()));
		float shoulder = playerState.isCrouching ? CROUCHING_SHOULDER_HEIGHT : SHOULDER_HEIGHT;

		poseStack.pushPose();
		// Back out of the player model's flipped, head-anchored space into a normal, upright
		// one centred on the player's feet, then climb up to the shoulders.
		poseStack.translate(0.0F, MODEL_ORIGIN_OFFSET, 0.0F);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		poseStack.translate(SHOULDER_SIDE, shoulder, -0.05F);
		poseStack.scale(scale, scale, scale);

		CameraRenderState camera = new CameraRenderState();
		camera.orientation = minecraft.gameRenderer.getMainCamera().rotation();
		dispatcher.submit(state, camera, 0.0, 0.0, 0.0, poseStack, collector);
		poseStack.popPose();
	}

	/** Strips everything that only makes sense for an entity standing in the world. */
	private static void prepare(EntityRenderState state, int light) {
		state.lightCoords = light;
		state.displayFireAnimation = false;
		state.leashStates = null;
		state.nameTag = null;
		state.shadowRadius = 0.0F;
		state.shadowPieces.clear();

		if (state instanceof LivingEntityRenderState living) {
			// 180 degrees cancels out the rotation the player model already applied, so the mob
			// looks the same way its carrier does. yRot is the head yaw *relative* to the body,
			// so it has to stay at zero or the mob ends up looking over its own back.
			living.bodyRot = 180.0F;
			living.yRot = 0.0F;
			living.xRot = 0.0F;
			living.walkAnimationSpeed = 0.0F;
			living.deathTime = 0.0F;
		}
	}
}
