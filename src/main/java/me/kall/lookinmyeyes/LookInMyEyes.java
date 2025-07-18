package me.kall.lookinmyeyes;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

@Mod(LookInMyEyes.MOD_ID)
public final class LookInMyEyes {
    public static final String MOD_ID = "lookinmyeyes";

    public static final ForgeConfigSpec CONFIG;
    public static final ForgeConfigSpec.DoubleValue VIEW_FIELD;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("LookInMyEyes");
        VIEW_FIELD = builder.defineInRange("ViewField(Angle, default 180°)", 180.00, 0.00, 360.00);
        builder.pop();
        CONFIG = builder.build();
    }

    public LookInMyEyes(@NotNull FMLJavaModLoadingContext context) {
        LogManager.getLogger(LookInMyEyes.class).info("Look in my eyes!");
        context.registerConfig(ModConfig.Type.COMMON, CONFIG);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (LivingChangeTargetEvent event) -> {
            if (event.isCanceled()) return;
            LivingEntity target = event.getNewTarget();
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
