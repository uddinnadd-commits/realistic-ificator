package io.github.uddinnadd.realisticificator.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** Position of the centre of mass plus orientation, synced to clients as one value so both always arrive together. */
public record BodyPose(Vec3 position, Quaternionfc rotation) {
	public static final BodyPose ORIGIN = new BodyPose(Vec3.ZERO, new Quaternionf());
	public static final StreamCodec<RegistryFriendlyByteBuf, BodyPose> STREAM_CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, BodyPose::position,
		ByteBufCodecs.QUATERNIONF, BodyPose::rotation,
		BodyPose::new
	);
	public static final EntityDataSerializer<BodyPose> SERIALIZER = EntityDataSerializer.forValueType(STREAM_CODEC);
}
