package io.github.uddinnadd.realisticificator.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.uddinnadd.realisticificator.physics.Densities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Immutable set of block cells that make up one rigid body.
 * <p>
 * Cell {@code c} occupies, relative to the entity position {@code P},
 * {@code [P.x + c.x - 0.5, P.x + c.x + 0.5] x [P.y + c.y, P.y + c.y + 1] x [P.z + c.z - 0.5, P.z + c.z + 0.5]},
 * so a single cell at the origin matches vanilla's bottom-centre entity position.
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

	private BlockStructure(Map<BlockPos, BlockState> cells) {
		this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
		double m = 0, x = 0, y = 0, z = 0;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			double d = Densities.of(e.getValue());
			BlockPos p = e.getKey();
			m += d;
			x += d * p.getX();
			y += d * (p.getY() + 0.5);
			z += d * p.getZ();
		}
		this.mass = m;
		this.centerOfMass = m > 0 ? new Vec3(x / m, y / m, z / m) : new Vec3(0, 0.5, 0);
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

	/** Total mass in "water-filled cell" units. */
	public double mass() {
		return this.mass;
	}

	/** Centre of mass relative to the entity position. */
	public Vec3 centerOfMass() {
		return this.centerOfMass;
	}

	public double averageDensity() {
		return this.cells.isEmpty() ? 1.0 : this.mass / this.cells.size();
	}

	public int minY() {
		return this.cells.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
	}

	/**
	 * Rotates all cells by an axis-aligned rotation (a signed permutation matrix) about the centre of mass.
	 *
	 * @return the rotated structure and the offset the entity position must move by to keep every cell
	 * exactly where the rotation put it.
	 */
	public Rotated rotate(Matrix3f rotation) {
		Map<BlockPos, BlockState> rotated = new LinkedHashMap<>();
		Vec3 com = this.centerOfMass;
		Vec3 offset = null;
		for (Map.Entry<BlockPos, BlockState> e : this.cells.entrySet()) {
			BlockPos p = e.getKey();
			Vector3f rel = new Vector3f((float) (p.getX() - com.x), (float) (p.getY() + 0.5 - com.y), (float) (p.getZ() - com.z));
			rotation.transform(rel);
			double cx = rel.x + com.x, cy = rel.y + com.y - 0.5, cz = rel.z + com.z;
			BlockPos np = BlockPos.containing(Math.round(cx), Math.round(cy), Math.round(cz));
			if (offset == null) {
				offset = new Vec3(cx - np.getX(), cy - np.getY(), cz - np.getZ());
			}
			rotated.put(np, rotateState(e.getValue(), rotation));
		}
		return new Rotated(new BlockStructure(rotated), offset == null ? Vec3.ZERO : offset);
	}

	public record Rotated(BlockStructure structure, Vec3 positionOffset) {
	}

	private static BlockState rotateState(BlockState state, Matrix3f r) {
		Vector3f up = r.transform(new Vector3f(0, 1, 0));
		if (Math.round(up.y) == 1) {
			Vector3f east = r.transform(new Vector3f(1, 0, 0));
			int ex = Math.round(east.x), ez = Math.round(east.z);
			Rotation rot = ex == 1 ? Rotation.NONE : ez == 1 ? Rotation.CLOCKWISE_90 : ex == -1 ? Rotation.CLOCKWISE_180 : Rotation.COUNTERCLOCKWISE_90;
			return state.rotate(rot);
		}
		if (state.hasProperty(BlockStateProperties.AXIS)) {
			Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
			Vector3f v = r.transform(new Vector3f(axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0));
			Direction.Axis newAxis = Math.abs(v.x) > 0.5F ? Direction.Axis.X : Math.abs(v.y) > 0.5F ? Direction.Axis.Y : Direction.Axis.Z;
			return state.setValue(BlockStateProperties.AXIS, newAxis);
		}
		return state;
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
