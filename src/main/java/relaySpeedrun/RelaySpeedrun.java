package relaySpeedrun;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.arguments.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RelaySpeedrun implements ModInitializer {

    public static final String MOD_ID = "relay-speedrun";

    public static final Logger LOGGER = LogManager.getLogger("Relay Speedrun");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(literal("relay")
                .then(literal("start").executes(context -> handleStart(context.getSource())))
                .then(literal("stop").executes(context -> handleStop(context.getSource())))
                .then(literal("pause").executes(context -> handlePause(context.getSource())))
                .then(literal("resume").executes(context -> handleResume(context.getSource())))
                .then(literal("countdown")
                    .then(literal("get").executes(context -> {
                        context.getSource().sendFeedback(new LiteralText("Counting down from " + Relay.countdown), false);
                        return Relay.countdown;
                    }))
                    .then(literal("set")
                        .then(argument("countdown", IntegerArgumentType.integer(0)).executes(context -> {
                            Relay.countdown = IntegerArgumentType.getInteger(context, "countdown");
                            context.getSource().sendFeedback(new LiteralText("Countdown set to " + Relay.countdown), false);
                            return 1;
                        })))));

            dispatcher.register(literal("setvelocity")
                .then(argument("entity", EntityArgumentType.entity())
                    .then(argument("velocityX", DoubleArgumentType.doubleArg())
                        .then(argument("velocityY", DoubleArgumentType.doubleArg())
                            .then(argument("velocityZ", DoubleArgumentType.doubleArg()).executes(context -> {
                                Entity entity = EntityArgumentType.getEntity(context, "entity");
                                entity.setVelocity(
                                    DoubleArgumentType.getDouble(context, "velocityX"),
                                    DoubleArgumentType.getDouble(context, "velocityY"),
                                    DoubleArgumentType.getDouble(context, "velocityZ"));
                                entity.velocityModified = true;
                                return 1;
                            }))))));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(Relay::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Relay.save());
        ServerTickEvents.END_SERVER_TICK.register(Relay::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sender.sendPacket(new TitleS2CPacket(0, 3, 0));
            Relay.join(handler.player);
        });
    }

    private static int handleStart(ServerCommandSource source) {
        switch (Relay.getCurrentState()) {
            case BEFORE_START:
                if (source.getMinecraftServer().getPlayerManager().getPlayerList().isEmpty()) {
                    source.sendFeedback(new LiteralText("Relay cannot start because there is no players"), false);
                    return 0;
                }
                Relay.start();
                return 1;
            case RUNNING:
            case PAUSING:
            case FORCED_PAUSING:
                source.sendFeedback(new LiteralText("Relay has already started"), false);
                return 0;
            case ENDED:
                source.sendFeedback(new LiteralText("Relay has already ended"), false);
                return 0;
            default:
                return 0;
        }
    }

    private static int handleStop(ServerCommandSource source) {
        switch (Relay.getCurrentState()) {
            case BEFORE_START:
                source.sendFeedback(new LiteralText("Relay has not started yet"), false);
                return 0;
            case RUNNING:
            case PAUSING:
            case FORCED_PAUSING:
                Relay.stop();
                return 1;
            case ENDED:
                source.sendFeedback(new LiteralText("Relay has already ended"), false);
                return 0;
            default:
                return 0;
        }
    }

    private static int handlePause(ServerCommandSource source) {
        switch (Relay.getCurrentState()) {
            case BEFORE_START:
                source.sendFeedback(new LiteralText("Relay has not started yet"), false);
                return 0;
            case RUNNING:
                Relay.pause();
                return 1;
            case PAUSING:
            case FORCED_PAUSING:
                source.sendFeedback(new LiteralText("Relay has already paused"), false);
                return 0;
            case ENDED:
                source.sendFeedback(new LiteralText("Relay has already ended"), false);
                return 0;
            default:
                return 0;
        }
    }

    private static int handleResume(ServerCommandSource source) {
        switch (Relay.getCurrentState()) {
            case BEFORE_START:
                source.sendFeedback(new LiteralText("Relay has not started yet"), false);
                return 0;
            case RUNNING:
                source.sendFeedback(new LiteralText("Relay is already running"), false);
                return 0;
            case PAUSING:
                Relay.resume();
                return 1;
            case FORCED_PAUSING:
                source.sendFeedback(new LiteralText("Relay cannot resume because the current player is not online"), false);
                return 0;
            case ENDED:
                source.sendFeedback(new LiteralText("Relay has already ended"), false);
                return 0;
            default:
                return 0;
        }
    }
}
