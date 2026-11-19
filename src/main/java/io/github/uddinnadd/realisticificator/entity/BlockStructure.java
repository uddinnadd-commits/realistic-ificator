package io.github.uddinnadd.realisticificator.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.uddinnadd.realisticificator.physics.Densities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.function.ToDoubleFunction;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;

/**
 * Immutable set of unit cubes that make up one rigid body, plus its mass properties.
 * <p>
 * "Structure space": cell {@code c} is the unit cube centred on the integer point {@code c}.
 * The body's local frame is structure space translated so the centre of mass is the origin.
 */
public final class BlockStructure {
	public record Cell(BlockPos pos, BlockState state) {
		public static final Codec<Cell> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Cell::pos),
			BlockState.CODEC.fieldOf("state").forGetter(Cell::state)
		).apply(i, Cell::new));
	}

	public static final BlockStructure EMPTY = new BlockStructure(Map.of());
	public static final Codec<BlockStructure> CODEC = Cell.CODEC.listOf().xmap(BlockStructure::fromCells, BlockStructure::toCells);
	public static final StreamCodec<RegistryFriendlyByteBuf, BlockStructure> STREAM_CODEC = StreamCodec.of(
		(buf, structure) -> {
			buf.writeVarInt(structure.cells.size());
			structure.cells.forEach((pos, state) -> {
				buf.writeLong(pos.asLong());
				buf.writeVarInt(Block.getId(state));
			});
		},
		buf -> {
			int size = buf.readVarInt();
			Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
			for (int i = 0; i < size; i++) {
				cells.put(BlockPos.of(buf.readLong()), Block.stateById(buf.readVarInt()));
			}
			return new BlockStructure(cells);
		}
	);
	public static final EntityDataSerializer<BlockStructure> SERIALIZER = EntityDataSerializer.forValueType(STREAM_CODEC);

	private final Map<BlockPos, BlockState> cells;
	/** Collision boxes of each cell, relative to the cell centre (a full cube is -0.5..0.5). */
	private final Map<BlockPos, List<AABB>> shapes;
	private final Set<BlockPos> fullCells;
	private final double mass;
	private final Vec3 centerOfMass;
	private final Matrix3d inverseInertia;
	private final List<Vec3> samplePoints;
	private final double radius;

	private BlockStructure(Map<BlockPos, BlockState> cells) {
		this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
		Map<BlockPos, List<AABB>> shapes = new HashMap<>();
		Set<BlockPos> full = new HashSet<>();
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			BlockState state = e.getValue();
			List<AABB> boxes = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream().map(b -> b.move(-0.5, -0.5, -0.5)).toList();
			if (boxes.isEmpty()) {
				boxes = List.of(new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5));
			}
			shapes.put(e.getKey(), boxes);
			if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				full.add(e.getKey());
			}
		}
		this.shapes = Map.copyOf(shapes);
		this.fullCells = Set.copyOf(full);

		double m = 0, x = 0, y = 0, z = 0;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			double d = Densities.of(e.getValue());
			BlockPos p = e.getKey();
			for (AABB box : this.shapes.get(p)) {
				double bm = d * box.getXsize() * box.getYsize() * box.getZsize();
				Vec3 c = box.getCenter();
				m += bm;
				x += bm * (p.getX() + c.x);
				y += bm * (p.getY() + c.y);
				z += bm * (p.getZ() + c.z);
			}
		}
		this.mass = Math.max(m, 1.0E-3);
		this.centerOfMass = m > 0 ? new Vec3(x / m, y / m, z / m) : Vec3.ZERO;

		// Inertia tensor about the centre of mass: each box's own inertia plus the parallel-axis term.
		Matrix3d inertia = new Matrix3d().zero();
		double maxR = 0;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			double d = Densities.of(e.getValue());
			BlockPos p = e.getKey();
			for (AABB box : this.shapes.get(p)) {
				double sx = box.getXsize(), sy = box.getYsize(), sz = box.getZsize();
				double bm = d * sx * sy * sz;
				Vec3 c = box.getCenter();
				double rx = p.getX() + c.x - this.centerOfMass.x, ry = p.getY() + c.y - this.centerOfMass.y, rz = p.getZ() + c.z - this.centerOfMass.z;
				double r2 = rx * rx + ry * ry + rz * rz;
				maxR = Math.max(maxR, Math.sqrt(r2));
				inertia.m00 += bm * (sy * sy + sz * sz) / 12.0 + bm * (r2 - rx * rx);
				inertia.m11 += bm * (sx * sx + sz * sz) / 12.0 + bm * (r2 - ry * ry);
				inertia.m22 += bm * (sx * sx + sy * sy) / 12.0 + bm * (r2 - rz * rz);
				inertia.m01 -= bm * rx * ry;
				inertia.m02 -= bm * rx * rz;
				inertia.m12 -= bm * ry * rz;
			}
		}
		inertia.m10 = inertia.m01;
		inertia.m20 = inertia.m02;
		inertia.m21 = inertia.m12;
		this.inverseInertia = this.cells.isEmpty() ? new Matrix3d() : inertia.invert();
		this.radius = maxR + Math.sqrt(3) / 2;
		this.samplePoints = this.computeSamplePoints();
	}

	/**
	 * Full cubes contribute corners, edge midpoints and face centres on a shared half-block lattice (minus points buried
	 * inside the body); partial blocks such as slabs and stairs contribute the same 26 points for each of their boxes.
	 */
	private List<Vec3> computeSamplePoints() {
		Set<BlockPos> doubled = new HashSet<>();
		Set<Vec3> partial = new HashSet<>();
		for (BlockPos c : this.cells.keySet()) {
			if (this.fullCells.contains(c)) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							if (dx != 0 || dy != 0 || dz != 0) {
								doubled.add(new BlockPos(c.getX() * 2 + dx, c.getY() * 2 + dy, c.getZ() * 2 + dz));
							}
						}
					}
				}
				continue;
			}
			for (AABB box : this.shapes.get(c)) {
				double[] xs = {box.minX, (box.minX + box.maxX) / 2, box.maxX};
				double[] ys = {box.minY, (box.minY + box.maxY) / 2, box.maxY};
				double[] zs = {box.minZ, (box.minZ + box.maxZ) / 2, box.maxZ};
				for (int i = 0; i < 3; i++) {
					for (int j = 0; j < 3; j++) {
						for (int k = 0; k < 3; k++) {
							if (i != 1 || j != 1 || k != 1) {
								partial.add(new Vec3(c.getX() + xs[i], c.getY() + ys[j], c.getZ() + zs[k]));
							}
						}
					}
				}
			}
		}
		List<Vec3> points = new ArrayList<>(partial);
		for (BlockPos p : doubled) {
			if (!isBuried(p, this.fullCells)) {
				points.add(new Vec3(p.getX() / 2.0, p.getY() / 2.0, p.getZ() / 2.0));
			}
		}
		return List.copyOf(points);
	}

	private static boolean isBuried(BlockPos doubled, Set<BlockPos> cells) {
		int[] xs = touching(doubled.getX()), ys = touching(doubled.getY()), zs = touching(doubled.getZ());
		for (int x : xs) {
			for (int y : ys) {
				for (int z : zs) {
					if (!cells.contains(new BlockPos(x, y, z))) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static int[] touching(int doubled) {
		return (doubled & 1) == 0 ? new int[]{doubled / 2} : new int[]{(doubled - 1) / 2, (doubled + 1) / 2};
	}

	/** Collision boxes of a cell, in structure space. */
	public List<AABB> boxesOf(BlockPos cell) {
		List<AABB> local = this.shapes.getOrDefault(cell, List.of());
		List<AABB> out = new ArrayList<>(local.size());
		for (AABB box : local) {
			out.add(box.move(cell.getX(), cell.getY(), cell.getZ()));
		}
		return out;
	}

	public boolean isFull(BlockPos cell) {
		return this.fullCells.contains(cell);
	}

	public record LocalHit(double depth, Direction face) {
	}

	/**
	 * If a structure-space point is inside the body, the shallowest exposed face to leave through.
	 *
	 * @param bias extra score per face; lower wins, used to prefer sensible directions when depths are similar
	 * @param rejectAmbiguous ignore points sitting on an edge of the body, where the exit face is a coin toss
	 */
	public @Nullable LocalHit penetration(Vec3 sp, ToDoubleFunction<Direction> bias, boolean rejectAmbiguous) {
		BlockPos cell = new BlockPos(Mth.floor(sp.x + 0.5), Mth.floor(sp.y + 0.5), Mth.floor(sp.z + 0.5));
		List<AABB> boxes = this.shapes.get(cell);
		if (boxes == null) {
			return null;
		}
		double lx = sp.x - cell.getX(), ly = sp.y - cell.getY(), lz = sp.z - cell.getZ();
		LocalHit best = null;
		double bestScore = Double.MAX_VALUE;
		int shallowFaces = 0;
		for (AABB box : boxes) {
			if (lx < box.minX || lx > box.maxX || ly < box.minY || ly > box.maxY || lz < box.minZ || lz > box.maxZ) {
				continue;
			}
			for (Direction d : Direction.values()) {
				double faceCoord = switch (d) {
					case UP -> box.maxY;
					case DOWN -> -box.minY;
					case EAST -> box.maxX;
					case WEST -> -box.minX;
					case SOUTH -> box.maxZ;
					default -> -box.minZ;
				};
				boolean onCellBoundary = faceCoord >= 0.5 - 1.0E-6;
				if (onCellBoundary && this.fullCells.contains(cell.relative(d))) {
					continue; // face buried against a neighbouring full block of the same body
				}
				double depth = faceCoord - (lx * d.getStepX() + ly * d.getStepY() + lz * d.getStepZ());
				if (depth < 0.03) {
					shallowFaces++;
				}
				double score = depth + bias.applyAsDouble(d);
				if (score < bestScore) {
					bestScore = score;
					best = new LocalHit(depth, d);
				}
			}
		}
		if (rejectAmbiguous && shallowFaces >= 2) {
			return null;
		}
		return best;
	}
	public static BlockStructure single(BlockState state) {
		return new BlockStructure(Map.of(BlockPos.ZERO, state));
	}

	public static BlockStructure of(Map<BlockPos, BlockState> cells) {
		return new BlockStructure(cells);
	}

	private static BlockStructure fromCells(List<Cell> list) {
		Map<BlockPos, BlockState> map = new LinkedHashMap<>();
		list.forEach(c -> map.put(c.pos(), c.state()));
		return new BlockStructure(map);
	}

	private List<Cell> toCells() {
		List<Cell> list = new ArrayList<>();
		this.cells.forEach((pos, state) -> list.add(new Cell(pos, state)));
		return list;
	}

	public Map<BlockPos, BlockState> cells() {
		return this.cells;
	}

	public int size() {
		return this.cells.size();
	}

	public boolean isEmpty() {
		return this.cells.isEmpty();
	}

	public boolean contains(BlockPos pos) {
		return this.cells.containsKey(pos);
	}

	public boolean contains(int x, int y, int z) {
		return this.cells.containsKey(new BlockPos(x, y, z));
	}

	public BlockState get(BlockPos pos) {
		return this.cells.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	/** The block every cell is made of; structures only grow with more of the same kind. */
	public Block kind() {
		return this.cells.isEmpty() ? Blocks.AIR : this.cells.values().iterator().next().getBlock();
	}

	public BlockState primaryState() {
		return this.cells.isEmpty() ? Blocks.AIR.defaultBlockState() : this.cells.values().iterator().next();
	}

	public BlockStructure with(BlockPos pos, BlockState state) {
		Map<BlockPos, BlockState> copy = new LinkedHashMap<>(this.cells);
		copy.put(pos.immutable(), state);
		return new BlockStructure(copy);
	}

	/** Total mass, in units of one block of water. */
	public double mass() {
		return this.mass;
	}

	/** Centre of mass in structure space. */
	public Vec3 centerOfMass() {
		return this.centerOfMass;
	}

	/** Inverse inertia tensor in the body frame. Do not mutate. */
	public Matrix3d inverseInertia() {
		return this.inverseInertia;
	}

	/** Contact sample points in structure space. */
	public List<Vec3> samplePoints() {
		return this.samplePoints;
	}

	/** Radius of a sphere around the centre of mass that contains the whole body. */
	public double radius() {
		return this.radius;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || o instanceof BlockStructure other && this.cells.equals(other.cells);
	}

	@Override
	public int hashCode() {
		return this.cells.hashCode();
	}
}
