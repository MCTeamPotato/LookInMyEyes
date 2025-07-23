package me.kall.lookinmyeyes;

import com.google.common.base.Predicates;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Mod(LookInMyEyes.MOD_ID)
public final class LookInMyEyes {
    public static final String MOD_ID = "lookinmyeyes";
    private static final Logger LOGGER = LogManager.getLogger(LookInMyEyes.class);

    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.parse(MOD_ID + ":main"), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
    private static int packetId = 0;

    private static final ForgeConfigSpec CONFIG;
    private static final ForgeConfigSpec.DoubleValue VIEW_FIELD;
    private static final ForgeConfigSpec.IntValue MOBS_CHECK_SOUND_SOURCE_CHANCE;
    private static final ForgeConfigSpec.BooleanValue MOBS_CHECK_SOUND_SOURCE, SNEAKING_NO_SOUND;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DEAF, BLIND;

    private static Set<EntityType<?>> deafEntities, blindEntities;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
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

    public LookInMyEyes(@NotNull FMLJavaModLoadingContext context) {
        LOGGER.info("Look in my eyes!");
        context.registerConfig(ModConfig.Type.COMMON, CONFIG);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onTargetChange);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onSoundPlay);

        CHANNEL.registerMessage(packetId++, SoundAlertPacket.class, SoundAlertPacket::encode, SoundAlertPacket::new, this::handleSoundAlert);
    }

    public void onTargetChange(@NotNull LivingChangeTargetEvent event) {
        if (event.isCanceled()) return;
        LivingEntity target = event.getNewTarget();
        LivingEntity observer = event.getEntity();
        if (observer.level().isClientSide() || target == null) return;
        if (observer.getPersistentData().getBoolean(MOD_ID)) {
            observer.getPersistentData().remove(MOD_ID);
             return;
        }
        if (isInFieldOfView(observer, target)) {
            if (getBlindEntities().contains(observer.getType()) || observer.hasEffect(MobEffects.BLINDNESS) || observer.hasEffect(MobEffects.DARKNESS)) event.setCanceled(true);
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

            if (ThreadLocalRandom.current().nextInt(0, 101) <= MOBS_CHECK_SOUND_SOURCE_CHANCE.get() && player instanceof LocalPlayer) {
                CHANNEL.sendToServer(new SoundAlertPacket(event.getNewVolume()));
            }
        }
    }

    private static boolean isInFieldOfView(@NotNull LivingEntity observer, @NotNull LivingEntity target) {
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
            Predicate<Mob> filter = entity -> entity.isAlive() && entity instanceof Enemy && entity.getTarget() == null && !getDeafEntities().contains(entity.getType());

            level.getEntitiesOfClass(PathfinderMob.class, soundRadius, filter).forEach(entity -> {
                entity.getNavigation().stop();
                entity.getPersistentData().putBoolean(MOD_ID, true);
                entity.setTarget(player);
            });
        });
        ctx.get().setPacketHandled(true);
    }

    private static Set<EntityType<?>> getDeafEntities() {
        if (deafEntities == null) deafEntities = DEAF.get().stream().map(ResourceLocation::parse).map(ForgeRegistries.ENTITY_TYPES::getValue).collect(Collectors.toSet());
        return deafEntities;
    }

    private static Set<EntityType<?>> getBlindEntities() {
        if (blindEntities == null) blindEntities = BLIND.get().stream().map(ResourceLocation::parse).map(ForgeRegistries.ENTITY_TYPES::getValue).collect(Collectors.toSet());
        return blindEntities;
    }

    private static class SoundAlertPacket {
        private final float volume;

        SoundAlertPacket(float volume) {
            this.volume = volume;
        }

        SoundAlertPacket(@NotNull FriendlyByteBuf buf) {
            this.volume = buf.readFloat();
        }

        void encode(@NotNull FriendlyByteBuf buf) {
            buf.writeFloat(volume);
        }
    }
}