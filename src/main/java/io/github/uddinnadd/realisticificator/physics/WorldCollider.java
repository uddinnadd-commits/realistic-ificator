package io.github.uddinnadd.realisticificator.physics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
		return this.penetration(p, null);
	}

	/**
	 * Like {@link #penetration(Vec3)}, but exits facing away from {@code prefer} (the side the rest of the body is on)
	 * are penalised, so a point that has sunk past the middle of a thin wall or pane goes back, not through.
	 */
	public @Nullable Hit penetration(Vec3 p, @Nullable Vec3 prefer) {
		BlockPos bp = BlockPos.containing(p);
		Hit best = this.penetration(p, bp, prefer);
		BlockInfo below = this.blockInfo(bp.below());
		for (AABB box : below.boxes()) {
			if (box.maxY > bp.getY()) { // fences and walls are taller than one block
				Hit tall = this.penetration(p, bp.below(), prefer);
				if (tall != null && (best == null || tall.depth() < best.depth())) {
					best = tall;
				}
				break;
			}
		}
		return best;
	}

	private @Nullable Hit penetration(Vec3 p, BlockPos owner, @Nullable Vec3 prefer) {
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
				if (prefer != null && d.getStepX() * prefer.x + d.getStepY() * prefer.y + d.getStepZ() * prefer.z < -1.0E-3) {
					score += 0.5;
				}
				if (score < bestScore) {
					bestScore = score;
					best = new Hit(depth, d, info.state());
				}
			}
		}
		return best;
	}

	/** First terrain surface a moving point enters: the entry point and the face's outward normal. */
	public record Sweep(Vec3 point, Direction normal) {
	}

	/** Swept test so fast or thin things can't tunnel: where moving from {@code from} to {@code to} first enters terrain. */
	public @Nullable Sweep sweep(Vec3 from, Vec3 to) {
		if (from.distanceToSqr(to) < 1.0E-10) {
			return null;
		}
		Sweep best = null;
		double bestDist = Double.MAX_VALUE;
		int x0 = Mth.floor(Math.min(from.x, to.x)), x1 = Mth.floor(Math.max(from.x, to.x));
		int y0 = Mth.floor(Math.min(from.y, to.y)) - 1, y1 = Mth.floor(Math.max(from.y, to.y));
		int z0 = Mth.floor(Math.min(from.z, to.z)), z1 = Mth.floor(Math.max(from.z, to.z));
		for (BlockPos bp : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
			for (AABB box : this.blockInfo(bp).boxes()) {
				if (box.contains(from)) {
					continue; // already inside: penetration contacts deal with that
				}
				Vec3 hit = box.clip(from, to).orElse(null);
				if (hit == null) {
					continue;
				}
				double dist = hit.distanceToSqr(from);
				if (dist < bestDist) {
					bestDist = dist;
					best = new Sweep(hit, faceOf(box, hit));
				}
			}
		}
		return best;
	}

	private static Direction faceOf(AABB box, Vec3 p) {
		double[] gaps = {Math.abs(p.x - box.minX), Math.abs(p.x - box.maxX), Math.abs(p.y - box.minY), Math.abs(p.y - box.maxY), Math.abs(p.z - box.minZ), Math.abs(p.z - box.maxZ)};
		Direction[] faces = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
		int best = 0;
		for (int i = 1; i < 6; i++) {
			if (gaps[i] < gaps[best]) {
				best = i;
			}
		}
		return faces[best];
	}
}
