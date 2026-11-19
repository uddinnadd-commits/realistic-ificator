package io.github.uddinnadd.realisticificator.physics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Point-vs-terrain queries with a per-tick block cache. Create one per tick per simulated object. */
public final class WorldCollider {
	public record BlockInfo(BlockState state, List<AABB> boxes, boolean full) {
	}

	/** Depth and outward face of the shallowest way out of the terrain. */
	public record Hit(double depth, Direction normal, BlockState state) {
	}

	private final Level level;
	private final Map<BlockPos, BlockInfo> cache = new HashMap<>();

	public WorldCollider(Level level) {
		this.level = level;
	}

	public void clear() {
		this.cache.clear();
	}

	public BlockInfo blockInfo(BlockPos pos) {
		BlockInfo cached = this.cache.get(pos);
		if (cached != null) {
			return cached;
		}
		BlockPos p = pos.immutable();
		BlockInfo info;
		if (!this.level.hasChunkAt(p)) {
			// Treat unloaded terrain as solid so nothing falls out of the loaded world.
			info = new BlockInfo(Blocks.BEDROCK.defaultBlockState(), List.of(new AABB(p)), true);
		} else {
			BlockState state = this.level.getBlockState(p);
			List<AABB> boxes = state.getCollisionShape(this.level, p).toAabbs().stream().map(b -> b.move(p)).toList();
			info = new BlockInfo(state, boxes, state.isCollisionShapeFullBlock(this.level, p));
		}
		this.cache.put(p, info);
		return info;
	}

	public @Nullable Hit penetration(Vec3 p) {
		BlockPos bp = BlockPos.containing(p);
		Hit best = this.penetration(p, bp);
		BlockInfo below = this.blockInfo(bp.below());
		for (AABB box : below.boxes()) {
			if (box.maxY > bp.getY()) { // fences and walls are taller than one block
				Hit tall = this.penetration(p, bp.below());
				if (tall != null && (best == null || tall.depth() < best.depth())) {
					best = tall;
				}
				break;
			}
		}
		return best;
	}

	private @Nullable Hit penetration(Vec3 p, BlockPos owner) {
		BlockInfo info = this.blockInfo(owner);
		Hit best = null;
		double bestScore = Double.MAX_VALUE;
		for (AABB box : info.boxes()) {
			if (p.x < box.minX || p.x > box.maxX || p.y < box.minY || p.y > box.maxY || p.z < box.minZ || p.z > box.maxZ) {
				continue;
			}
			for (Direction d : Direction.values()) {
				double depth;
				boolean onBoundary;
				switch (d) {
					case UP -> { depth = box.maxY - p.y; onBoundary = box.maxY >= owner.getY() + 1 - 1.0E-6; }
					case DOWN -> { depth = p.y - box.minY; onBoundary = box.minY <= owner.getY() + 1.0E-6; }
					case EAST -> { depth = box.maxX - p.x; onBoundary = box.maxX >= owner.getX() + 1 - 1.0E-6; }
					case WEST -> { depth = p.x - box.minX; onBoundary = box.minX <= owner.getX() + 1.0E-6; }
					case SOUTH -> { depth = box.maxZ - p.z; onBoundary = box.maxZ >= owner.getZ() + 1 - 1.0E-6; }
					default -> { depth = p.z - box.minZ; onBoundary = box.minZ <= owner.getZ() + 1.0E-6; }
				}
				if (onBoundary && this.blockInfo(owner.relative(d)).full()) {
					continue; // internal face between two solid blocks: never push out through it
				}
				double score = depth + (d == Direction.UP ? 0.0 : d == Direction.DOWN ? 0.1 : 0.05);
				if (score < bestScore) {
					bestScore = score;
					best = new Hit(depth, d, info.state());
				}
			}
		}
		return best;
	}
}
