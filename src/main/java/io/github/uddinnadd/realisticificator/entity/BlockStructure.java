package io.github.uddinnadd.realisticificator.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.uddinnadd.realisticificator.physics.Densities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
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
	private final double mass;
	private final Vec3 centerOfMass;
	private final Matrix3d inverseInertia;
	private final List<Vec3> samplePoints;
	private final double radius;

	private BlockStructure(Map<BlockPos, BlockState> cells) {
		this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
		double m = 0, x = 0, y = 0, z = 0;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			double d = Densities.of(e.getValue());
			BlockPos p = e.getKey();
			m += d;
			x += d * p.getX();
			y += d * p.getY();
			z += d * p.getZ();
		}
		this.mass = Math.max(m, 1.0E-3);
		this.centerOfMass = m > 0 ? new Vec3(x / m, y / m, z / m) : Vec3.ZERO;

		// Inertia tensor about the centre of mass: each cube's own m/6 plus the parallel-axis term.
		Matrix3d inertia = new Matrix3d().zero();
		double maxR = 0;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			double d = Densities.of(e.getValue());
			double rx = e.getKey().getX() - this.centerOfMass.x, ry = e.getKey().getY() - this.centerOfMass.y, rz = e.getKey().getZ() - this.centerOfMass.z;
			double r2 = rx * rx + ry * ry + rz * rz;
			maxR = Math.max(maxR, Math.sqrt(r2));
			inertia.m00 += d / 6.0 + d * (r2 - rx * rx);
			inertia.m11 += d / 6.0 + d * (r2 - ry * ry);
			inertia.m22 += d / 6.0 + d * (r2 - rz * rz);
			inertia.m01 -= d * rx * ry;
			inertia.m02 -= d * rx * rz;
			inertia.m12 -= d * ry * rz;
		}
		inertia.m10 = inertia.m01;
		inertia.m20 = inertia.m02;
		inertia.m21 = inertia.m12;
		this.inverseInertia = this.cells.isEmpty() ? new Matrix3d() : inertia.invert();
		this.radius = maxR + Math.sqrt(3) / 2;
		this.samplePoints = computeSamplePoints(this.cells.keySet());
	}

	/** Corners, edge midpoints and face centres of every cell, de-duplicated, minus points buried inside the body. */
	private static List<Vec3> computeSamplePoints(Set<BlockPos> cells) {
		Set<BlockPos> doubled = new HashSet<>();
		for (BlockPos c : cells) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx != 0 || dy != 0 || dz != 0) {
							doubled.add(new BlockPos(c.getX() * 2 + dx, c.getY() * 2 + dy, c.getZ() * 2 + dz));
						}
					}
				}
			}
		}
		List<Vec3> points = new ArrayList<>();
		for (BlockPos p : doubled) {
			if (!isBuried(p, cells)) {
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

	public static BlockStructure single(BlockState state) {
		return new BlockStructure(Map.of(BlockPos.ZERO, state));
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
