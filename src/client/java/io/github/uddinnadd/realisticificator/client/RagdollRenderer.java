package io.github.uddinnadd.realisticificator.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.uddinnadd.realisticificator.entity.BodyPose;
import io.github.uddinnadd.realisticificator.entity.RagdollEntity;
import io.github.uddinnadd.realisticificator.physics.RagdollSkeleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public class RagdollRenderer extends EntityRenderer<RagdollEntity, RagdollRenderer.State> {
	private final Map<RagdollEntity, RagdollVisual> visuals = new WeakHashMap<>();

	public static class State extends EntityRenderState {
		@Nullable RagdollVisual visual;
		@Nullable RagdollSkeleton skeleton;
		final List<Vector3f> bonePositions = new ArrayList<>(); // relative to the render origin
		final List<Quaternionf> boneRotations = new ArrayList<>();
		float shadowSize;
	}

	public RagdollRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(RagdollEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		RagdollVisual visual = this.visuals.get(entity);
		if (visual == null && !this.visuals.containsKey(entity)) {
			visual = RagdollVisual.build(entity);
			this.visuals.put(entity, visual);
		}
		state.visual = visual;
		state.skeleton = entity.skeleton();

		// Render origin sits on the lowest point of the corpse so its shadow lands on the ground under it.
		int count = entity.boneCount();
		List<BodyPose> poses = new ArrayList<>(count);
		AABB bounds = null;
		for (int i = 0; i < count; i++) {
			BodyPose pose = entity.getRenderPose(i, partialTicks);
			poses.add(pose);
			Vec3 p = pose.position();
			AABB b = new AABB(p, p).inflate(0.3);
			bounds = bounds == null ? b : bounds.minmax(b);
		}
		if (bounds == null) {
			return;
		}
		Vec3 origin = new Vec3(bounds.getCenter().x, bounds.minY + 0.1, bounds.getCenter().z);
		state.x = origin.x;
		state.y = origin.y;
		state.z = origin.z;
		state.bonePositions.clear();
		state.boneRotations.clear();
		for (BodyPose pose : poses) {
			Vec3 rel = pose.position().subtract(origin);
			state.bonePositions.add(new Vector3f((float) rel.x, (float) rel.y, (float) rel.z));
			state.boneRotations.add(new Quaternionf(pose.rotation()));
		}
		state.shadowSize = (float) Math.min(1.5, Math.max(bounds.getXsize(), bounds.getZsize()) / 2.0);
	}

	@Override
	protected float getShadowRadius(State state) {
		return state.shadowSize;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.visual != null && state.skeleton != null) {
			for (RagdollVisual.Segment segment : state.visual.segments) {
				int bone = segment.bone();
				if (bone >= state.bonePositions.size()) {
					continue;
				}
				Vector3f p = state.bonePositions.get(bone);
				Vec3 rest = state.skeleton.bones.get(bone).center();
				poseStack.pushPose();
				poseStack.translate(p.x, p.y, p.z);
				poseStack.rotate(state.boneRotations.get(bone));
				poseStack.translate(-rest.x, -rest.y, -rest.z);
				poseStack.mulPose(segment.rest());
				collector.submitModelPart(segment.cubes(), poseStack, state.visual.renderType, state.lightCoords, OverlayTexture.NO_OVERLAY, null);
				poseStack.popPose();
			}
		}
		super.submit(state, poseStack, collector, camera);
	}
}
