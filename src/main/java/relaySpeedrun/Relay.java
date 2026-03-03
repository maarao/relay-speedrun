package relaySpeedrun;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.packet.s2c.play.HeldItemChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import relaySpeedrun.mixin.HungerManagerMixin;
import relaySpeedrun.mixin.ServerPlayNetworkHandlerMixin;
import relaySpeedrun.mixin.ServerPlayerEntityCreditsMixin;
import relaySpeedrun.mixin.ServerPlayerEntityMixin;
import relaySpeedrun.mixin.TntEntityMixin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static relaySpeedrun.RelaySpeedrun.LOGGER;

public class Relay {

    private static final Gson GSON = new Gson();
    private static final Path RELAY = Paths.get("relay.json");

    public static int countdown = 1200;

    private static State state = State.BEFORE_START;
    private static List<ServerPlayerEntity> players;
    private static final Set<UUID> knownPlayers = new HashSet<UUID>();
    private static ServerPlayerEntity current;
    private static UUID currentPlayerUuid;
    private static Text currentPlayerName;
    private static int timer;
    private static int rta;
    private static int igt;

    private static Scoreboard scoreboard;
    private static ScoreboardObjective timerObjective;
    private static ScoreboardPlayerScore rtaScore;
    private static ScoreboardPlayerScore igtScore;
    private static Team spectator;

    public static State getCurrentState() {
        return state;
    }

    public static void init(MinecraftServer server) {
        PlayerManager playerManager = server.getPlayerManager();
        players = playerManager.getPlayerList();
        knownPlayers.clear();
        for (ServerPlayerEntity player : players) {
            knownPlayers.add(player.getUuid());
        }

        scoreboard = server.getScoreboard();
        timerObjective = scoreboard.getObjective("timer");
        if (timerObjective == null) {
            timerObjective = scoreboard.addObjective(
                "timer",
                ScoreboardCriterion.DUMMY,
                new LiteralText("Timer"),
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        rtaScore = scoreboard.getPlayerScore("RTA", timerObjective);
        igtScore = scoreboard.getPlayerScore("IGT", timerObjective);
        rtaScore.setScore(0);
        igtScore.setScore(0);
        scoreboard.setObjectiveSlot(1, timerObjective);

        spectator = scoreboard.getTeam("spectator");
        if (spectator == null) {
            spectator = scoreboard.addTeam("spectator");
        }
        spectator.setColor(Formatting.WHITE);

        if (!new File(RELAY.toString()).exists()) {
            return;
        }

        JsonObject json;
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(RELAY))) {
            json = GSON.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            LOGGER.error("error", e);
            throw new RuntimeException(e);
        }

        state = State.parse(json.get("state").getAsString());
        if (state == null) {
            LOGGER.error("Unknown state: {}", json.get("state").getAsString());
            return;
        }
        if (state == State.ENDED) {
            return;
        }

        countdown = json.get("countdown").getAsInt();
        timer = json.get("timer").getAsInt();
        rta = json.get("rta").getAsInt();
        igt = json.get("igt").getAsInt();
        rtaScore.setScore(rta);
        igtScore.setScore(igt);

        if (state.isTicking() && json.has("currentPlayer")) {
            JsonObject currentPlayer = json.getAsJsonObject("currentPlayer");
            if (currentPlayer.has("uuid")) {
                currentPlayerUuid = UUID.fromString(currentPlayer.get("uuid").getAsString());
                current = playerManager.getPlayer(currentPlayerUuid);
            }
            if (currentPlayer.has("name")) {
                currentPlayerName = new LiteralText(currentPlayer.get("name").getAsString());
            }
            if (current == null) {
                state = State.FORCED_PAUSING;
            } else {
                state = State.PAUSING;
            }
        }
    }

    public static void save() {
        if (state == State.RUNNING) {
            pause();
        }

        JsonObject json = new JsonObject();
        json.addProperty("state", state.toString());
        json.addProperty("countdown", countdown);
        json.addProperty("timer", timer);
        json.addProperty("rta", rta);
        json.addProperty("igt", igt);

        JsonObject currentPlayer = new JsonObject();
        if (currentPlayerUuid != null && currentPlayerName != null) {
            currentPlayer.addProperty("uuid", currentPlayerUuid.toString());
            currentPlayer.addProperty("name", currentPlayerName.getString());
        }
        json.add("currentPlayer", currentPlayer);

        try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(RELAY))) {
            writer.setIndent("  ");
            GSON.toJson(json, writer);
        } catch (IOException e) {
            LOGGER.error("error", e);
        }
    }

    public static void join(ServerPlayerEntity player) {
        switch (state) {
            case FORCED_PAUSING:
                if (player.getUuid().equals(currentPlayerUuid)) {
                    current = player;
                    state = State.PAUSING;
                    break;
                }
            case RUNNING:
            case PAUSING:
                if (player != current) {
                    makeSpectator(player);
                }
                break;
            default:
                break;
        }
    }

    public static void start() {
        // Seed known players so first tick does not misclassify current participants as fresh joins.
        knownPlayers.clear();
        for (ServerPlayerEntity player : players) {
            knownPlayers.add(player.getUuid());
        }

        for (ServerPlayerEntity player : players) {
            makeSpectator(player);
        }
        if (!players.isEmpty()) {
            takeOver(players.get(0));
            state = State.RUNNING;
            LOGGER.info("Relay started");
        }
    }

    public static void pause() {
        state = State.PAUSING;
        LOGGER.info("Relay paused");
    }

    public static void resume() {
        state = State.RUNNING;
        LOGGER.info("Relay resumed");
    }

    public static void stop() {
        for (ServerPlayerEntity player : players) {
            if (player == current) {
                continue;
            }
            player.setGameMode(GameMode.SURVIVAL);
            clearEffects(player);
            removeScoreHolderFromTeam(player, spectator);
        }

        if (current != null) {
            for (ServerPlayerEntity spectatorPlayer : players) {
                if (spectatorPlayer != current) {
                    spectatorPlayer.teleport(current.getServerWorld(), current.getX(), current.getY(), current.getZ(), current.yaw, current.pitch);
                }
            }
        }

        state = State.BEFORE_START;
        current = null;
        currentPlayerUuid = null;
        currentPlayerName = null;
        LOGGER.info("Relay stopped");
    }

    public static void tick(MinecraftServer server) {
        if (!state.isTicking()) {
            return;
        }

        Set<UUID> onlinePlayerUuids = new HashSet<UUID>();
        for (ServerPlayerEntity player : players) {
            onlinePlayerUuids.add(player.getUuid());
        }
        knownPlayers.retainAll(onlinePlayerUuids);

        for (ServerPlayerEntity player : players) {
            if (knownPlayers.add(player.getUuid())) {
                join(player);
            }
        }

        rta++;
        rtaScore.setScore(rta);

        if (current != null) {
            for (ServerPlayerEntity player : players) {
                if (current.getUuid().equals(player.getUuid()) && current != player) {
                    current = player;
                    break;
                }
            }
        }

        for (ServerPlayerEntity spectatorPlayer : players) {
            if (spectatorPlayer != current) {
                spectatorPlayer.teleport(spectatorPlayer.getServerWorld(), 0.0D, 320.0D, 0.0D, 0.0F, 0.0F);
            }
        }

        if ((current == null || !players.contains(current)) && state != State.FORCED_PAUSING) {
            current = null;
            state = State.FORCED_PAUSING;
            for (ServerPlayerEntity player : players) {
                player.sendMessage(new LiteralText("Current player left, relay paused"), false);
            }
            LOGGER.info("Current player left, relay paused");
        }

        String currentPlayerCountdown = getCurrentPlayerCountdown();

        if (state.isPaused()) {
            Text msg = new LiteralText("Game Paused").formatted(Formatting.RED);
            for (ServerPlayerEntity player : players) {
                if (player == current) {
                    sendActionBar(player, msg);
                } else {
                    sendSubtitle(player, new LiteralText("Current player: ")
                        .append(currentPlayerName)
                        .append(" " + currentPlayerCountdown));
                    sendTitle(player, msg);
                }
            }
            return;
        }

        if (current == null || players.isEmpty()) {
            return;
        }

        ServerPlayerEntity next = getNextPlayer();
        for (ServerPlayerEntity player : players) {
            if (player == current) {
                sendActionBar(player, new LiteralText(currentPlayerCountdown));
                continue;
            }

            sendSubtitle(player, new LiteralText("Current player: ")
                .append(currentPlayerName)
                .append(" " + currentPlayerCountdown));
            sendTitle(player, new LiteralText(getTotalCountdown(player)));
            LiteralText nextMsg = new LiteralText("Next player: " + next.getEntityName());
            if (player == next) {
                nextMsg.setStyle(nextMsg.getStyle().withColor(Formatting.GREEN));
            }
            sendActionBar(player, nextMsg);
        }

        if (((ServerPlayerEntityCreditsMixin) current).relay_speedrun$hasSeenCredits()) {
            stop();
            state = State.ENDED;

            PlayerManager playerManager = server.getPlayerManager();
            playerManager.sendToAll(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, new LiteralText("RTA " + getCountdown(rta)).formatted(Formatting.LIGHT_PURPLE)));
            playerManager.sendToAll(new TitleS2CPacket(TitleS2CPacket.Action.TITLE, new LiteralText("IGT " + getCountdown(igt)).formatted(Formatting.AQUA)));
            return;
        }

        if (timer % 20 == 0) {
            LOGGER.debug("Timer: {}", currentPlayerCountdown);
        }

        switch (timer) {
            case 100:
            case 80:
            case 60:
            case 40:
            case 20:
                next.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    SoundEvents.BLOCK_NOTE_BLOCK_HARP,
                    SoundCategory.MASTER,
                    0.0D,
                    320.0D,
                    0.0D,
                    10.0F,
                    1.0F
                ));
                break;
            case 0:
                next.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    SoundEvents.BLOCK_NOTE_BLOCK_HARP,
                    SoundCategory.MASTER,
                    0.0D,
                    320.0D,
                    0.0D,
                    10.0F,
                    2.0F
                ));
                takeOver(next);
                break;
            default:
                break;
        }

        timer--;
        igt++;
        igtScore.setScore(igt);
    }

    private static String getCurrentPlayerCountdown() {
        return getCountdown(timer);
    }

    private static String getTotalCountdown(ServerPlayerEntity player) {
        int queuePos = players.indexOf(player) - players.indexOf(current) - 1;
        if (queuePos < 0) {
            queuePos += players.size();
        }
        return getCountdown(timer + queuePos * countdown);
    }

    private static String getCountdown(int ticks) {
        StringBuilder sb = new StringBuilder();

        int tenms = ticks * 5;
        int hundredms = tenms / 10;
        int sec = hundredms / 10;
        int tensec = sec / 10;
        int min = tensec / 6;
        int tenmin = min / 10;
        int hour = tenmin / 6;

        tenms %= 10;
        hundredms %= 10;
        sec %= 10;
        tensec %= 6;
        min %= 10;
        tenmin %= 6;

        if (hour > 0) {
            sb.append(hour).append(':');
            if (tenmin == 0) {
                sb.append(0);
            }
        }
        if (tenmin > 0 && min == 0) {
            sb.append(0).append(':');
        }
        if (min > 0) {
            sb.append(min).append(':');
            if (tensec == 0) {
                sb.append(0);
            }
        }
        if (tensec > 0) {
            sb.append(tensec);
        }
        sb.append(sec).append('.').append(hundredms).append(tenms);

        return sb.toString();
    }

    private static ServerPlayerEntity getNextPlayer() {
        return players.get((players.indexOf(current) + 1) % players.size());
    }

    private static void makeSpectator(ServerPlayerEntity player) {
        player.setGameMode(GameMode.SPECTATOR);
        clearEffects(player);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, Integer.MAX_VALUE, 255, false, false));
        addScoreHolderToTeam(player, spectator);
    }

    private static void takeOver(ServerPlayerEntity player) {
        timer = countdown;
        currentPlayerUuid = player.getUuid();
        currentPlayerName = player.getDisplayName();

        player.setGameMode(GameMode.SURVIVAL);
        if (current == null) {
            clearEffects(player);
            removeScoreHolderFromTeam(player, spectator);
            current = player;
            return;
        }

        LOGGER.info("{} takes over RTA: {} IGT: {}", player.getEntityName(), getCountdown(rta), getCountdown(igt));

        if (current == player) {
            return;
        }

        replaceTeam(player);
        inheritFallDistance(player);
        inheritPortalCooldown(player);
        inheritHP(player);
        inheritHunger(player);
        inheritAir(player);
        inheritXp(player);
        inheritStatusEffects(player);
        inheritSelectedHotbarSlot(player);
        inheritInventory(player);
        inheritEnderChestInventory(player);
        inheritSpawnpoint(player);
        replaceShoulders(player);
        iterateEntities(player);
        if (!replaceRiding(player)) {
            tp(player);
        }
        makeSpectator(current);

        current = player;
    }

    private static void replaceTeam(ServerPlayerEntity player) {
        removeScoreHolderFromTeam(player, spectator);
        addScoreHolderToTeam(current, spectator);
    }

    private static void inheritFallDistance(ServerPlayerEntity player) {
        player.fallDistance = current.fallDistance;
    }

    private static void inheritPortalCooldown(ServerPlayerEntity player) {
        player.netherPortalCooldown = current.netherPortalCooldown;
    }

    private static void inheritHP(ServerPlayerEntity player) {
        player.setHealth(current.getHealth());
    }

    private static void inheritHunger(ServerPlayerEntity player) {
        HungerManager playerhm = player.getHungerManager();
        HungerManager currenthm = current.getHungerManager();
        playerhm.setFoodLevel(currenthm.getFoodLevel());
        ((HungerManagerMixin) playerhm).setFoodSaturationLevel(((HungerManagerMixin) currenthm).getFoodSaturationLevel());
        ((HungerManagerMixin) playerhm).setExhaustion(((HungerManagerMixin) currenthm).getExhaustion());
    }

    private static void inheritAir(ServerPlayerEntity player) {
        player.setAir(current.getAir());
    }

    private static void inheritXp(ServerPlayerEntity player) {
        player.experienceLevel = current.experienceLevel;
        player.experienceProgress = current.experienceProgress;
        player.totalExperience = current.totalExperience;
    }

    private static void inheritStatusEffects(ServerPlayerEntity player) {
        clearEffects(player);
        for (StatusEffectInstance effect : current.getStatusEffects()) {
            player.addStatusEffect(effect);
        }
    }

    private static void inheritSelectedHotbarSlot(ServerPlayerEntity player) {
        player.inventory.selectedSlot = current.inventory.selectedSlot;
        player.networkHandler.sendPacket(new HeldItemChangeS2CPacket(player.inventory.selectedSlot));
    }

    private static void inheritInventory(ServerPlayerEntity player) {
        ItemStack cursor = current.inventory.getCursorStack();
        if (!cursor.isEmpty()) {
            current.dropItem(cursor, false);
            current.inventory.setCursorStack(ItemStack.EMPTY);
        }

        int size = Math.min(player.inventory.size(), current.inventory.size());
        for (int i = 0; i < size; i++) {
            ItemStack stack = current.inventory.getStack(i);
            player.inventory.setStack(i, stack.copy());
            current.inventory.setStack(i, ItemStack.EMPTY);
        }
    }

    private static void inheritEnderChestInventory(ServerPlayerEntity player) {
        EnderChestInventory playereci = player.getEnderChestInventory();
        EnderChestInventory currenteci = current.getEnderChestInventory();

        for (int i = 0; i < playereci.size(); i++) {
            playereci.setStack(i, currenteci.getStack(i));
            currenteci.setStack(i, ItemStack.EMPTY);
        }
    }

    private static void inheritSpawnpoint(ServerPlayerEntity player) {
        player.setSpawnPoint(
            current.getSpawnPointDimension(),
            current.getSpawnPointPosition(),
            current.isSpawnPointSet(),
            false
        );
    }

    private static void replaceShoulders(ServerPlayerEntity player) {
        ServerPlayerEntityMixin currentmx = (ServerPlayerEntityMixin) current;
        ServerPlayerEntityMixin playermx = (ServerPlayerEntityMixin) player;
        CompoundTag leftShoulderNbt = current.getShoulderEntityLeft();
        CompoundTag rightShoulderNbt = current.getShoulderEntityRight();

        if (!leftShoulderNbt.isEmpty()) {
            playermx.relay_speedrun$setLeftShoulderNbt(leftShoulderNbt);
            currentmx.relay_speedrun$setLeftShoulderNbt(new CompoundTag());
        }
        if (!rightShoulderNbt.isEmpty()) {
            playermx.relay_speedrun$setRightShoulderNbt(rightShoulderNbt);
            currentmx.relay_speedrun$setRightShoulderNbt(new CompoundTag());
        }
    }

    private static void iterateEntities(ServerPlayerEntity player) {
        UUID currentUuid = current.getUuid();
        for (Entity entity : current.getServerWorld().iterateEntities()) {
            if (entity instanceof MobEntity) {
                MobEntity mob = (MobEntity) entity;
                if (mob.getTarget() == current) {
                    mob.setTarget(player);
                }
            } else if (entity instanceof TameableEntity) {
                TameableEntity tameable = (TameableEntity) entity;
                if (tameable.getOwner() == current) {
                    tameable.setOwner(player);
                }
            } else if (entity instanceof ProjectileEntity) {
                ProjectileEntity projectile = (ProjectileEntity) entity;
                if (projectile.getOwner() == current) {
                    projectile.setOwner(player);
                }
            } else if (entity instanceof TntEntity) {
                TntEntity tnt = (TntEntity) entity;
                if (tnt.getCausingEntity() == current) {
                    ((TntEntityMixin) tnt).setCausingEntity(player);
                }
            } else if (entity instanceof ItemEntity) {
                ItemEntity item = (ItemEntity) entity;
                if (currentUuid.equals(item.getThrower())) {
                    item.setThrower(player.getUuid());
                }
                if (currentUuid.equals(item.getOwner())) {
                    item.setOwner(player.getUuid());
                }
            }
        }
    }

    private static boolean replaceRiding(ServerPlayerEntity player) {
        Entity vehicle = current.getVehicle();
        if (vehicle != null) {
            current.stopRiding();
            player.startRiding(vehicle);
            return true;
        }
        return false;
    }

    private static void tp(ServerPlayerEntity player) {
        ServerPlayNetworkHandlerMixin handler = (ServerPlayNetworkHandlerMixin) current.networkHandler;
        player.teleport(current.getServerWorld(), current.getX(), current.getY(), current.getZ(), current.yaw, current.pitch);
        player.setVelocity(new Vec3d(
            current.getX() - handler.getLastTickX(),
            current.getY() - handler.getLastTickY(),
            current.getZ() - handler.getLastTickZ()
        ));
        player.velocityModified = true;
    }

    private static void sendTitle(ServerPlayerEntity player, Text text) {
        player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.TITLE, text));
    }

    private static void sendSubtitle(ServerPlayerEntity player, Text text) {
        player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.SUBTITLE, text));
    }

    private static void sendActionBar(ServerPlayerEntity player, Text text) {
        player.networkHandler.sendPacket(new TitleS2CPacket(TitleS2CPacket.Action.ACTIONBAR, text));
    }

    private static void clearEffects(ServerPlayerEntity player) {
        List<StatusEffectInstance> active = new ArrayList<StatusEffectInstance>(player.getStatusEffects());
        for (StatusEffectInstance effect : active) {
            player.removeStatusEffect(effect.getEffectType());
        }
    }

    private static void addScoreHolderToTeam(ServerPlayerEntity scoreHolder, Team team) {
        scoreboard.addPlayerToTeam(scoreHolder.getEntityName(), team);
    }

    private static void removeScoreHolderFromTeam(ServerPlayerEntity scoreHolder, Team team) {
        if (scoreboard.getPlayerTeam(scoreHolder.getEntityName()) == team) {
            scoreboard.removePlayerFromTeam(scoreHolder.getEntityName(), team);
        }
    }

    public enum State {
        BEFORE_START,
        RUNNING,
        PAUSING,
        FORCED_PAUSING,
        ENDED;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isTicking() {
            return this != BEFORE_START && this != ENDED;
        }

        public boolean isPaused() {
            return this == PAUSING || this == FORCED_PAUSING;
        }

        public static State parse(String str) {
            for (State state : values()) {
                if (state.toString().equals(str)) {
                    return state;
                }
            }
            return null;
        }
    }
}
