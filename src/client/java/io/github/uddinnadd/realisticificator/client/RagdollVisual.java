package io.github.uddinnadd.realisticificator.client;

import io.github.uddinnadd.realisticificator.RealisticIficator;
import io.github.uddinnadd.realisticificator.client.mixin.ModelPartAccessor;
import io.github.uddinnadd.realisticificator.entity.RagdollEntity;
import io.github.uddinnadd.realisticificator.entity.RagdollInfo;
import io.github.uddinnadd.realisticificator.physics.RagdollSkeleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The mob's own model, cut into pieces that each follow one ragdoll bone.
 * Built once per corpse from a throwaway client-side copy of the mob, so variants and textures match.
 */
public final class RagdollVisual {
	/** A model part's own cubes (no children) and where they sit in canonical space when the mob stands at rest. */
	public record Segment(ModelPart cubes, Matrix4f rest, int bone) {
	}

	public final List<Segment> segments;
	public final RenderType renderType;

	private RagdollVisual(List<Segment> segments, RenderType renderType) {
		this.segments = segments;
		this.renderType = renderType;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static @Nullable RagdollVisual build(RagdollEntity ragdoll) {
		RagdollInfo info = ragdoll.getInfo();
		Identifier id = Identifier.tryParse(info.entityType());
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(id);
		if (type == null) {
			return null;
		}
		try {
			Entity dummy = type.create(ragdoll.level(), EntitySpawnReason.LOAD);
			if (dummy == null) {
				return null;
			}
			try {
				dummy.load(TagValueInput.create(ProblemReporter.DISCARDING, ragdoll.level().registryAccess(), info.entityData()));
			} catch (RuntimeException ignored) {
				// Unknown data just means the default look.
			}
			dummy.setPos(ragdoll.position());
			EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(dummy);
			if (!(renderer instanceof LivingEntityRenderer living)) {
				return null;
			}
			LivingEntityRenderState state = (LivingEntityRenderState) ((EntityRenderer) living).createRenderState(dummy, 0.0F);
			Identifier texture = living.getTextureLocation(state);
			EntityModel<?> model = living.getModel();
			RenderType renderType = model.renderType().apply(texture);

			// What LivingEntityRenderer does for a mob with zero body yaw: canonical space faces +Z, feet at 0.
			Matrix4f canonical = new Matrix4f().rotateY((float) Math.PI).scale(-1.0F, -1.0F, 1.0F).translate(0.0F, -1.501F, 0.0F);
			List<Segment> segments = new ArrayList<>();
			RagdollSkeleton.Kind kind = ragdoll.skeleton().kind;
			collect(model.root(), "root", canonical, RagdollSkeleton.TORSO, kind, segments);
			dummy.discard();
			return segments.isEmpty() ? null : new RagdollVisual(segments, renderType);
		} catch (RuntimeException e) {
			RealisticIficator.LOGGER.warn("Couldn't build ragdoll model for {}", info.entityType(), e);
			return null;
		}
	}

	private static void collect(ModelPart part, String name, Matrix4f parent, int inheritedBone, RagdollSkeleton.Kind kind, List<Segment> out) {
		if (part.skipDraw || !part.visible) {
			return;
		}
		PartPose pose = part.getInitialPose();
		Matrix4f m = new Matrix4f(parent).translate(pose.x() / 16.0F, pose.y() / 16.0F, pose.z() / 16.0F);
		if (pose.xRot() != 0 || pose.yRot() != 0 || pose.zRot() != 0) {
			m.rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
		}
		if (pose.xScale() != 1 || pose.yScale() != 1 || pose.zScale() != 1) {
			m.scale(pose.xScale(), pose.yScale(), pose.zScale());
		}
		int bone = boneFor(name, inheritedBone, kind);
		ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
		List<ModelPart.Cube> cubes = accessor.realisticIficator$getCubes();
		if (!cubes.isEmpty()) {
			out.add(new Segment(new ModelPart(cubes, Map.of()), m, bone));
		}
		for (Map.Entry<String, ModelPart> child : accessor.realisticIficator$getChildren().entrySet()) {
			collect(child.getValue(), child.getKey(), m, bone, kind, out);
		}
	}

	/** Maps a model part name onto a skeleton bone; parts that aren't a head or limb follow their parent. */
	static int boneFor(String name, int inherited, RagdollSkeleton.Kind kind) {
		if (kind == RagdollSkeleton.Kind.BLOB) {
			return RagdollSkeleton.TORSO;
		}
		String n = name.toLowerCase(Locale.ROOT);
		if (n.contains("head") || n.contains("beak") || n.contains("nose") || n.contains("jaw") || n.contains("snout") || n.contains("red_thing")) {
			return RagdollSkeleton.HEAD;
		}
		boolean right = n.contains("right"), left = n.contains("left");
		if (!right && !left) {
			return inherited;
		}
		if (kind == RagdollSkeleton.Kind.BIPED) {
			if (n.contains("arm") || n.contains("wing") || n.contains("hand")) {
				return right ? RagdollSkeleton.LIMB_A : RagdollSkeleton.LIMB_B;
			}
			if (n.contains("leg") || n.contains("foot")) {
				return right ? RagdollSkeleton.LIMB_C : RagdollSkeleton.LIMB_D;
			}
			return inherited;
		}
		if (n.contains("leg")) {
			boolean front = n.contains("front") || n.contains("arm");
			if (front) {
				return right ? RagdollSkeleton.LIMB_A : RagdollSkeleton.LIMB_B;
			}
			return right ? RagdollSkeleton.LIMB_C : RagdollSkeleton.LIMB_D;
		}
		return inherited;
	}
}
