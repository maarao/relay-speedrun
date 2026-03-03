package relaySpeedrun.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerEntity.class)
public interface ServerPlayerEntityCreditsMixin {

    @Accessor("seenCredits")
    boolean relay_speedrun$hasSeenCredits();
}
