package me.kall.lookinmyeyes;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

@Mod(LookInMyEyes.MOD_ID)
public final class LookInMyEyes {
    public static final String MOD_ID = "lookinmyeyes";

    public static final ModConfigSpec CONFIG;
    public static final ModConfigSpec.DoubleValue VIEW_FIELD;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("LookInMyEyes");
        VIEW_FIELD = builder.defineInRange("ViewField(Angle, default 180°)", 180.00, 0.00, 360.00);
        builder.pop();
        CONFIG = builder.build();
    }

    public LookInMyEyes(IEventBus modEventBus, Dist dist, @NotNull ModContainer container) {
        LogManager.getLogger(LookInMyEyes.class).info("Look in my eyes!");
        container.registerConfig(ModConfig.Type.COMMON, CONFIG);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (LivingChangeTargetEvent event) -> {
            if (event.isCanceled()) return;
            LivingEntity target = event.getNewAboutToBeSetTarget();
            LivingEntity observer = event.getEntity();
            Level level = observer.level();
            if (level.isClientSide() || target == null) return;
            if (!isInFieldOfView(observer, target)) event.setCanceled(true);
        });
    }


    public static boolean isInFieldOfView(@NotNull LivingEntity observer, @NotNull LivingEntity target) {
        double x = target.getX() - observer.getX();
        double y = target.getEyeY() - observer.getEyeY();
        double z = target.getZ() - observer.getZ();
        return Math.toDegrees(Math.acos(observer.getViewVector(1.0F).dot(new Vec3(x, y, z).normalize()))) < VIEW_FIELD.get() / 2.0D;
    }
}
