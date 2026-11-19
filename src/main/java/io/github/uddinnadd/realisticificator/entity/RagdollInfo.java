package io.github.uddinnadd.realisticificator.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;

/** What a ragdoll is a corpse of: the mob type and its saved data (for its look/variant), plus skeleton sizing. */
public record RagdollInfo(String entityType, CompoundTag entityData, int kind, float width, float height) {
	public static final RagdollInfo EMPTY = new RagdollInfo("minecraft:zombie", new CompoundTag(), 0, 0.6F, 1.95F);
	public static final Codec<RagdollInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("entity_type").forGetter(RagdollInfo::entityType),
		CompoundTag.CODEC.fieldOf("entity_data").forGetter(RagdollInfo::entityData),
		Codec.INT.fieldOf("kind").forGetter(RagdollInfo::kind),
		Codec.FLOAT.fieldOf("width").forGetter(RagdollInfo::width),
		Codec.FLOAT.fieldOf("height").forGetter(RagdollInfo::height)
	).apply(i, RagdollInfo::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, RagdollInfo> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, RagdollInfo::entityType,
		ByteBufCodecs.TRUSTED_COMPOUND_TAG, RagdollInfo::entityData,
		ByteBufCodecs.VAR_INT, RagdollInfo::kind,
		ByteBufCodecs.FLOAT, RagdollInfo::width,
		ByteBufCodecs.FLOAT, RagdollInfo::height,
		RagdollInfo::new
	);
	public static final EntityDataSerializer<RagdollInfo> SERIALIZER = EntityDataSerializer.forValueType(STREAM_CODEC);

	public static final StreamCodec<RegistryFriendlyByteBuf, List<BodyPose>> POSES_STREAM_CODEC = BodyPose.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new));
	public static final EntityDataSerializer<List<BodyPose>> POSES_SERIALIZER = EntityDataSerializer.forValueType(POSES_STREAM_CODEC);
}
