package relaySpeedrun.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerEntity.class)
public interface ServerPlayerEntityMixin {
    
    @Invoker("setShoulderEntityLeft")
    void relay_speedrun$setLeftShoulderNbt(CompoundTag leftShoulderNbt);
    
    @Invoker("setShoulderEntityRight")
    void relay_speedrun$setRightShoulderNbt(CompoundTag rightShoulderNbt);
    
}
