package io.github.uddinnadd.realisticificator.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** The two corners picked with a Structure Wand. */
public record WandSelection(Optional<BlockPos> first, Optional<BlockPos> second) {
	public static final WandSelection EMPTY = new WandSelection(Optional.empty(), Optional.empty());
	public static final Codec<WandSelection> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.optionalFieldOf("first").forGetter(WandSelection::first),
		BlockPos.CODEC.optionalFieldOf("second").forGetter(WandSelection::second)
	).apply(i, WandSelection::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, WandSelection> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), WandSelection::first,
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), WandSelection::second,
		WandSelection::new
	);

	public boolean isComplete() {
		return this.first.isPresent() && this.second.isPresent();
	}

	public Optional<BoundingBox> box() {
		return this.isComplete() ? Optional.of(BoundingBox.fromCorners(this.first.get(), this.second.get())) : Optional.empty();
	}
}
