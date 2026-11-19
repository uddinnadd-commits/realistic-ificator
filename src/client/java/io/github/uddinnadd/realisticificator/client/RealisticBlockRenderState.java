package io.github.uddinnadd.realisticificator.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class RealisticBlockRenderState extends EntityRenderState {
	/** Pooled per-cell block states; only the first {@link #cellCount} are valid this frame. */
	public final List<MovingBlockRenderState> cells = new ArrayList<>();
	public final List<BlockPos> cellOffsets = new ArrayList<>();
	public int cellCount;
	public final Quaternionf rotation = new Quaternionf();
	public final Vector3f pivot = new Vector3f();
	public float originToCenter;
	public float shadowSize;
}
