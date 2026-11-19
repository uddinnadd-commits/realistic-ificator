package io.github.uddinnadd.realisticificator.entity;

import io.github.uddinnadd.realisticificator.item.RealisticItems;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Turns every eligible block in an area into realistic bodies, one body per connected group of blocks. */
public final class AreaConversion {
	public static final int MAX_AREA_VOLUME = 64 * 64 * 64;
	public static final int MAX_BLOCKS = 4096;

	public sealed interface Result {
	}

	public record Converted(int blocks, int bodies) implements Result {
	}

	public record Nothing() implements Result {
	}

	public record TooBig(int blocks, int limit) implements Result {
	}

	private AreaConversion() {
	}

	public static Result convert(ServerLevel level, BoundingBox box) {
		if ((long) box.getXSpan() * box.getYSpan() * box.getZSpan() > MAX_AREA_VOLUME) {
			return new TooBig(box.getXSpan() * box.getYSpan() * box.getZSpan(), MAX_AREA_VOLUME);
		}
		Map<BlockPos, BlockState> eligible = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0 && RealisticItems.canBeRealistic(state)) {
				eligible.put(pos.immutable(), state);
			}
		}
		if (eligible.isEmpty()) {
			return new Nothing();
		}
		if (eligible.size() > MAX_BLOCKS) {
			return new TooBig(eligible.size(), MAX_BLOCKS);
		}

		List<Map<BlockPos, BlockState>> groups = connectedGroups(eligible);
		for (Map<BlockPos, BlockState> group : groups) {
			if (group.size() > RealisticBlockEntity.MAX_CELLS) {
				return new TooBig(group.size(), RealisticBlockEntity.MAX_CELLS);
			}
		}

		// Clear first so no body is born overlapping blocks that are about to become another body.
		for (Map.Entry<BlockPos, BlockState> e : eligible.entrySet()) {
			level.setBlock(e.getKey(), e.getValue().getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
		}
		for (Map<BlockPos, BlockState> group : groups) {
			BlockPos origin = group.keySet().iterator().next();
			for (BlockPos p : group.keySet()) {
				origin = new BlockPos(Math.min(origin.getX(), p.getX()), Math.min(origin.getY(), p.getY()), Math.min(origin.getZ(), p.getZ()));
			}
			Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
			for (Map.Entry<BlockPos, BlockState> e : group.entrySet()) {
				BlockState state = e.getValue();
				if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
					state = state.setValue(BlockStateProperties.WATERLOGGED, false);
				}
				cells.put(e.getKey().subtract(origin), state);
			}
			BlockStructure structure = BlockStructure.of(cells);
			// Cell c's centre is origin + c + 0.5, and the entity position is the centre of mass.
			Vec3 com = Vec3.atCenterOf(origin).add(structure.centerOfMass());
			level.addFreshEntity(new RealisticBlockEntity(level, com, structure));
		}
		return new Converted(eligible.size(), groups.size());
	}

	private static List<Map<BlockPos, BlockState>> connectedGroups(Map<BlockPos, BlockState> blocks) {
		List<Map<BlockPos, BlockState>> groups = new ArrayList<>();
		Map<BlockPos, BlockState> remaining = new HashMap<>(blocks);
		while (!remaining.isEmpty()) {
			BlockPos seed = remaining.keySet().iterator().next();
			Map<BlockPos, BlockState> group = new LinkedHashMap<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(seed);
			group.put(seed, remaining.remove(seed));
			while (!queue.isEmpty()) {
				BlockPos p = queue.poll();
				for (Direction d : Direction.values()) {
					BlockPos n = p.relative(d);
					BlockState s = remaining.remove(n);
					if (s != null) {
						group.put(n, s);
						queue.add(n);
					}
				}
			}
			groups.add(group);
		}
		return groups;
	}
}
