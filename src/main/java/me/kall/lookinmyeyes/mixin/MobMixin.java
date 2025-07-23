package me.kall.lookinmyeyes.mixin;

import me.kall.lookinmyeyes.LookInMyEyes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void onSetTarget(LivingEntity target, CallbackInfo ci) {
        LivingEntity observer = (LivingEntity) (Object) this;
        if (observer.level.isClientSide() || target == null) return;
        if (LookInMyEyes.isInFieldOfView(observer, target)) {
            if (LookInMyEyes.getBlindEntities().contains(observer.getType()) || observer.hasEffect(MobEffects.BLINDNESS)) ci.cancel();
        } else {
            ci.cancel();
        }
    }
}
