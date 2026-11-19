package io.github.uddinnadd.realisticificator.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.uddinnadd.realisticificator.entity.BlockStructure;
import io.github.uddinnadd.realisticificator.entity.RealisticBlockEntity;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Draws every cell like a falling block, with the whole body rotated about its centre of mass. */
public class RealisticBlockRenderer extends EntityRenderer<RealisticBlockEntity, RealisticBlockRenderState> {
	public RealisticBlockRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public RealisticBlockRenderState createRenderState() {
		return new RealisticBlockRenderState();
	}

	@Override
	public void extractRenderState(RealisticBlockEntity entity, RealisticBlockRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		// Interpolate between the last two server poses ourselves; vanilla would snap on every position packet.
		Vec3 pos = entity.getRenderPosition(partialTicks);
		entity.getRenderRotation(partialTicks, state.rotation);

		BlockStructure structure = entity.getStructure();
		Vec3 com = structure.centerOfMass();
		int i = 0;
		double minY = Double.MAX_VALUE;
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (Map.Entry<BlockPos, BlockState> cell : structure.cells().entrySet()) {
			if (state.cells.size() <= i) {
				state.cells.add(new MovingBlockRenderState());
				state.cellOffsets.add(BlockPos.ZERO);
			}
			BlockPos c = cell.getKey();
			Vector3f world = state.rotation.transform(new Vector3f((float) (c.getX() - com.x), (float) (c.getY() - com.y), (float) (c.getZ() - com.z)));
			minY = Math.min(minY, pos.y + world.y - 0.5);
			minX = Math.min(minX, pos.x + world.x - 0.5);
			maxX = Math.max(maxX, pos.x + world.x + 0.5);
			minZ = Math.min(minZ, pos.z + world.z - 0.5);
			maxZ = Math.max(maxZ, pos.z + world.z + 0.5);
			MovingBlockRenderState block = state.cells.get(i);
			block.blockState = cell.getValue();
			block.blockPos = lightPos(entity, pos.x + world.x, pos.y + world.y, pos.z + world.z);
			block.randomSeedPos = c;
			if (entity.level() instanceof ClientLevel level) {
				block.biome = level.getBiome(block.blockPos);
				block.cardinalLighting = level.cardinalLighting();
				block.lightEngine = level.getLightEngine();
			}
			state.cellOffsets.set(i, c);
			i++;
		}
		state.cellCount = i;
		state.pivot.set((float) com.x, (float) com.y, (float) com.z);

		// Vanilla casts the shadow from the render origin downwards, so put the origin under the body's bottom.
		double originY = Math.min(pos.y, minY + 0.05);
		state.x = pos.x;
		state.y = originY;
		state.z = pos.z;
		state.originToCenter = (float) (pos.y - originY);
		state.shadowSize = (float) Math.min(3.0, Math.max(maxX - minX, maxZ - minZ) * 0.55);
	}

	/**
	 * Light is sampled at the cell centre; when that is buried in terrain (a tilted body resting in a corner) it
	 * reads as pitch black, so step up to the first open space instead.
	 */
	private static BlockPos lightPos(RealisticBlockEntity entity, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		for (int i = 0; i < 2 && entity.level().getBlockState(pos).isSolidRender(); i++) {
			pos = pos.above();
		}
		return pos;
	}

	@Override
	protected float getShadowRadius(RealisticBlockRenderState state) {
		return state.shadowSize;
	}

	@Override
	protected AABB getBoundingBoxForCulling(RealisticBlockEntity entity, float partialTicks) {
		return entity.getBoundingBox().inflate(0.5);
	}

	@Override
	public void submit(RealisticBlockRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0.0F, state.originToCenter, 0.0F);
		poseStack.rotate(state.rotation);
		for (int i = 0; i < state.cellCount; i++) {
			BlockPos c = state.cellOffsets.get(i);
			poseStack.pushPose();
			poseStack.translate(c.getX() - state.pivot.x - 0.5F, c.getY() - state.pivot.y - 0.5F, c.getZ() - state.pivot.z - 0.5F);
			collector.submitMovingBlock(poseStack, state.cells.get(i), state.outlineColor);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
