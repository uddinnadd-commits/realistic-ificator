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
import net.minecraft.world.phys.Vec3;

/** Draws every cell like a falling block, rotated as a whole about the centre of mass. */
public class RealisticBlockRenderer extends EntityRenderer<RealisticBlockEntity, RealisticBlockRenderState> {
	public RealisticBlockRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.5F;
	}

	@Override
	public RealisticBlockRenderState createRenderState() {
		return new RealisticBlockRenderState();
	}

	@Override
	public void extractRenderState(RealisticBlockEntity entity, RealisticBlockRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		BlockStructure structure = entity.getStructure();
		Vec3 renderPos = new Vec3(state.x, state.y, state.z);
		int i = 0;
		for (Map.Entry<BlockPos, BlockState> cell : structure.cells().entrySet()) {
			if (state.cells.size() <= i) {
				state.cells.add(new MovingBlockRenderState());
				state.cellOffsets.add(BlockPos.ZERO);
			}
			BlockPos offset = cell.getKey();
			BlockPos lightPos = BlockPos.containing(renderPos.x + offset.getX(), renderPos.y + offset.getY() + 0.5, renderPos.z + offset.getZ());
			MovingBlockRenderState block = state.cells.get(i);
			block.blockState = cell.getValue();
			block.blockPos = lightPos;
			block.randomSeedPos = offset;
			if (entity.level() instanceof ClientLevel level) {
				block.biome = level.getBiome(lightPos);
				block.cardinalLighting = level.cardinalLighting();
				block.lightEngine = level.getLightEngine();
			}
			state.cellOffsets.set(i, offset);
			i++;
		}
		state.cellCount = i;
		entity.getRenderOrientation(partialTicks, state.rotation);
		Vec3 com = structure.centerOfMass();
		state.pivot.set((float) com.x, (float) com.y, (float) com.z);
		this.shadowRadius = (float) Math.min(2.0, 0.5 + 0.1 * structure.size());
	}

	@Override
	public void submit(RealisticBlockRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotateAround(state.rotation, state.pivot.x, state.pivot.y, state.pivot.z);
		for (int i = 0; i < state.cellCount; i++) {
			BlockPos offset = state.cellOffsets.get(i);
			poseStack.pushPose();
			poseStack.translate(offset.getX() - 0.5, offset.getY(), offset.getZ() - 0.5);
			collector.submitMovingBlock(poseStack, state.cells.get(i), state.outlineColor);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
