package me.kall.lookinmyeyes;

import com.google.common.base.Predicates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Enemy;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Mod(LookInMyEyes.MOD_ID)
@SuppressWarnings("deprecation")
public final class LookInMyEyes {
    public static final String MOD_ID = "lookinmyeyes";
    private static final Logger LOGGER = LogManager.getLogger(LookInMyEyes.class);

    private static final ModConfigSpec CONFIG;
    private static final ModConfigSpec.DoubleValue VIEW_FIELD;
    private static final ModConfigSpec.IntValue MOBS_CHECK_SOUND_SOURCE_CHANCE;
    private static final ModConfigSpec.BooleanValue MOBS_CHECK_SOUND_SOURCE, SNEAKING_NO_SOUND;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DEAF, BLIND;

    private static Set<EntityType<?>> deafEntities, blindEntities;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("LookInMyEyes");
        VIEW_FIELD = builder.defineInRange("ViewField(Angle, default 180°)", 180.00, 0.00, 360.00);
        MOBS_CHECK_SOUND_SOURCE = builder.comment("If enabled, PathfinderMobs would turn to the sound source when they heard sth.").define("MobsCheckSoundSource", true);
        MOBS_CHECK_SOUND_SOURCE_CHANCE = builder.comment("The possibility of mobs checking sound source when they heard sth.").defineInRange("MobsCheckSoundSourceChance(%)", 30, 0, 100);
        SNEAKING_NO_SOUND = builder.comment("If enabled, you will not play any sound when sneaking").define("SneakNoSound", true);
        DEAF = builder.comment("Deaf entities that fail to hear anything").defineList("Deaf", List.of(), Predicates.alwaysTrue());
        BLIND = builder.comment("Blind entities that fail to see anything").defineList("Blind", List.of(), Predicates.alwaysTrue());
        builder.pop();
        CONFIG = builder.build();
    }

    public LookInMyEyes(@NotNull IEventBus modEventBus, Dist dist, @NotNull ModContainer container) {
        LOGGER.info("Look in my eyes!");
        container.registerConfig(ModConfig.Type.COMMON, CONFIG);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onTargetChange);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onSoundPlay);
        modEventBus.addListener(this::registerPacket);
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
        if (isInFieldOfView(observer, target)) {
            if (getBlindEntities().contains(observer.getType()) || observer.hasEffect(MobEffects.DARKNESS) || observer.hasEffect(MobEffects.BLINDNESS)) event.setCanceled(true);
        } else {
            event.setCanceled(true);
        }
    }

    public void onSoundPlay(@NotNull PlayLevelSoundEvent.AtEntity event) {
        if (event.isCanceled() || !MOBS_CHECK_SOUND_SOURCE.get() || !event.getSource().equals(SoundSource.PLAYERS)) return;
        if (event.getEntity() instanceof Player player) {
            if (player.isSteppingCarefully() && SNEAKING_NO_SOUND.get()) {
                event.setCanceled(true);
                return;
            }

            if (ThreadLocalRandom.current().nextInt(0, 101) <= MOBS_CHECK_SOUND_SOURCE_CHANCE.get()) {
                PacketDistributor.sendToServer(new SoundAlertC2SMessage(event.getNewVolume()));
            }
        }
    }

    public void registerPacket(@NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0.0");
        registrar.playToServer(SoundAlertC2SMessage.TYPE, SoundAlertC2SMessage.STREAM_CODEC, SoundAlertC2SMessage::handleServer);
    }

    private static boolean isInFieldOfView(@NotNull LivingEntity observer, @NotNull LivingEntity target) {
        double x = target.getX() - observer.getX();
        double y = target.getEyeY() - observer.getEyeY();
        double z = target.getZ() - observer.getZ();
        return Math.toDegrees(Math.acos(observer.getViewVector(1.0F).dot(new Vec3(x, y, z).normalize()))) < VIEW_FIELD.get() / 2.0D;
    }

    private static Set<EntityType<?>> getDeafEntities() {
        if (deafEntities == null) deafEntities = DEAF.get().stream().map(ResourceLocation::parse).map(BuiltInRegistries.ENTITY_TYPE::get).collect(Collectors.toSet());
        return deafEntities;
    }

    private static Set<EntityType<?>> getBlindEntities() {
        if (blindEntities == null) blindEntities = BLIND.get().stream().map(ResourceLocation::parse).map(BuiltInRegistries.ENTITY_TYPE::get).collect(Collectors.toSet());
        return blindEntities;
    }

    private record SoundAlertC2SMessage(float volume) implements CustomPacketPayload {
        public static final Type<SoundAlertC2SMessage> TYPE = new Type<>(ResourceLocation.parse(MOD_ID + ":sound_alert"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SoundAlertC2SMessage> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, SoundAlertC2SMessage::volume, SoundAlertC2SMessage::new);

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handleServer(final SoundAlertC2SMessage msg, final IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                Player player = ctx.player();

                ServerLevel level = (ServerLevel) player.level();
                float radius = msg.volume * 16.0F;
                AABB soundRadius = player.getBoundingBox().inflate(radius);

                Predicate<Mob> filter = entity -> entity.isAlive() && entity instanceof Enemy && entity.getTarget() == null && !getDeafEntities().contains(entity.getType());

                level.getEntitiesOfClass(PathfinderMob.class, soundRadius, filter).forEach(entity -> {
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
            }).exceptionally(throwable -> null);
        }
    }
}