package io.github.uddinnadd.realisticificator.mixin;

import com.google.common.collect.ImmutableList;
import io.github.uddinnadd.realisticificator.entity.RealisticBlockEntity;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla treats every collidable entity as one box. Multi-block realistic structures (L shapes, stairs of
 * blocks...) instead contribute one box per cell so you can walk into the gaps and stand on every step.
 * <p>
 * Mirrors {@code EntityGetter#getEntityCollisions}; Level doesn't override that interface default, so this adds it.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
		if (testArea.getSize() < 1.0E-7) {
			return List.of();
		}
		Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		List<Entity> colliding = ((Level) (Object) this).getEntities(source, testArea.inflate(1.0E-7), canCollide);
		if (colliding.isEmpty()) {
			return List.of();
		}
		ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(colliding.size());
		for (Entity entity : colliding) {
			if (entity instanceof RealisticBlockEntity body) {
				for (AABB cell : body.getCellBoxes()) {
					if (cell.intersects(testArea.inflate(1.0E-7))) {
						shapes.add(Shapes.create(cell));
					}
				}
			} else {
				shapes.add(Shapes.create(entity.getBoundingBox()));
			}
		}
		return shapes.build();
	}
}
