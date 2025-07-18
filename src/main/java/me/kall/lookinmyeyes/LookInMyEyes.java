package me.kall.lookinmyeyes;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Mod(LookInMyEyes.MOD_ID)
public final class LookInMyEyes {
    public static final String MOD_ID = "lookinmyeyes";
    private static final Logger LOGGER = LogManager.getLogger(LookInMyEyes.class);

    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.parse(MOD_ID + ":main"), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
    private static int packetId = 0;

    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.DoubleValue VIEW_FIELD;
    private static final ModConfigSpec.IntValue MOBS_CHECK_SOUND_SOURCE_CHANCE;
    private static final ModConfigSpec.BooleanValue MOBS_CHECK_SOUND_SOURCE, SNEAKING_NO_SOUND;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("LookInMyEyes");
        VIEW_FIELD = builder.defineInRange("ViewField(Angle, default 180°)", 180.00, 0.00, 360.00);
        MOBS_CHECK_SOUND_SOURCE = builder.comment("If enabled, PathfinderMobs would turn to the sound source when they heard sth.").define("MobsCheckSoundSource", true);
        MOBS_CHECK_SOUND_SOURCE_CHANCE = builder.comment("The possibility of mobs checking sound source when they heard sth.").defineInRange("MobsCheckSoundSourceChance(%)", 30, 0, 100);
        SNEAKING_NO_SOUND = builder.comment("If enabled, you will not play any sound when sneaking").define("SneakNoSound", true);
        builder.pop();
        CONFIG = builder.build();
    }

    public LookInMyEyes(IEventBus modEventBus, Dist dist, @NotNull ModContainer container) {
        LOGGER.info("Look in my eyes!");
        container.registerConfig(ModConfig.Type.COMMON, CONFIG);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onTargetChange);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onSoundPlay);

        CHANNEL.registerMessage(packetId++, SoundAlertPacket.class, SoundAlertPacket::encode, SoundAlertPacket::new, this::handleSoundAlert);
    }

    public void onTargetChange(@NotNull LivingChangeTargetEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getNewAboutToBeSetTarget();
        LivingEntity observer = event.getEntity();
        if (observer.level().isClientSide() || target == null) return;
        if (observer.getPersistentData().getBoolean(MOD_ID)) {
            observer.getPersistentData().remove(MOD_ID);
            return;
        }
        if (!isInFieldOfView(observer, target)) event.setCanceled(true);
    }

    public void onSoundPlay(@NotNull PlayLevelSoundEvent.AtEntity event) {
        if (event.isCanceled() || !MOBS_CHECK_SOUND_SOURCE.get() || !event.getSource().equals(SoundSource.PLAYERS)) return;
        if (event.getEntity() instanceof Player player) {
            if (player.isSteppingCarefully() && SNEAKING_NO_SOUND.get()) {
                event.setCanceled(true);
                return;
            }

            if (ThreadLocalRandom.current().nextInt(0, 101) <= MOBS_CHECK_SOUND_SOURCE_CHANCE.get()) {
                CHANNEL.sendToServer(new SoundAlertPacket(player.getId(), event.getNewVolume()));
            }
        }
    }

    public static boolean isInFieldOfView(@NotNull LivingEntity observer, @NotNull LivingEntity target) {
        double x = target.getX() - observer.getX();
        double y = target.getEyeY() - observer.getEyeY();
        double z = target.getZ() - observer.getZ();
        return Math.toDegrees(Math.acos(observer.getViewVector(1.0F).dot(new Vec3(x, y, z).normalize()))) < VIEW_FIELD.get() / 2.0D;
    }

    private void handleSoundAlert(SoundAlertPacket packet, @NotNull Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel level = (ServerLevel) player.level();
            float radius = packet.volume * 16.0F;
            AABB soundRadius = player.getBoundingBox().inflate(radius);

            level.getEntitiesOfClass(PathfinderMob.class, soundRadius, LivingEntity::isAlive).forEach(entity -> {
                Vec3 toSound = player.position().subtract(entity.position()).normalize();

                double yaw = Math.toDegrees(Math.atan2(toSound.z, toSound.x)) - 90;

                double pitch = -Math.toDegrees(Math.atan2(toSound.y, Math.sqrt(toSound.x * toSound.x + toSound.z * toSound.z)));

                entity.setYRot((float) yaw);
                entity.setXRot((float) pitch);

                entity.yRotO = (float) yaw;
                entity.xRotO = (float) pitch;

                entity.setYHeadRot((float) yaw);

                if (player.isCreative()) return;

                entity.getPersistentData().putBoolean(MOD_ID, true);
                entity.setTarget(player);
            });
        });
        ctx.get().setPacketHandled(true);
    }

    private static class SoundAlertPacket {
        private final int playerId;
        private final float volume;

        SoundAlertPacket(int playerId, float volume) {
            this.playerId = playerId;
            this.volume = volume;
        }

        SoundAlertPacket(@NotNull FriendlyByteBuf buf) {
            this.playerId = buf.readInt();
            this.volume = buf.readFloat();
        }

        void encode(@NotNull FriendlyByteBuf buf) {
            buf.writeInt(playerId);
            buf.writeFloat(volume);
        }
    }
}