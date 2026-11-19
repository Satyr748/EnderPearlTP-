
package de.server.enderpearltp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EnderPearlTP implements ModInitializer {

    private static final Map<UUID, Set<UUID>> SELECTIONS =
            new ConcurrentHashMap<>();

    private static final Map<UUID, String> SELECTED_VEXBOT_TEAMS =
            new ConcurrentHashMap<>();

    private static final Set<UUID> ARMED_RODS =
            ConcurrentHashMap.newKeySet();

    private static final List<PendingTeleport> PENDING_TELEPORTS =
            new ArrayList<>();

    private static final int TELEPORT_DELAY_TICKS = 60;

    private static boolean isAllowed(ServerCommandSource source) {
        return source.getPlayer() != null
                && source.getPermissions()
                instanceof LeveledPermissionPredicate permissions
                && permissions.getLevel().isAtLeast(PermissionLevel.GAMEMASTERS);
    }

    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {

                    dispatcher.register(
                            CommandManager.literal("enderpearl")
                                    .requires(EnderPearlTP::isAllowed)
                                    .executes(context -> {
                                        openSelectionChat(
                                                context.getSource().getPlayerOrThrow());
                                        return Command.SINGLE_SUCCESS;
                                    })
                                    .then(CommandManager.literal("team")
                                            .then(CommandManager.argument(
                                                            "team",
                                                            StringArgumentType.word())
                                                    .executes(context -> {

                                                        ServerPlayerEntity player =
                                                                context.getSource().getPlayerOrThrow();

                                                        String team =
                                                                StringArgumentType.getString(context, "team");

                                                        SELECTED_VEXBOT_TEAMS.put(
                                                                player.getUuid(), team);

                                                        return Command.SINGLE_SUCCESS;
                                                    })))
                    );

                    dispatcher.register(
                            CommandManager.literal("eptoggle")
                                    .requires(EnderPearlTP::isAllowed)
                                    .then(CommandManager.argument(
                                                    "player",
                                                    net.minecraft.command.argument.EntityArgumentType.player())
                                            .executes(context -> {

                                                ServerPlayerEntity owner =
                                                        context.getSource().getPlayerOrThrow();

                                                ServerPlayerEntity target =
                                                        net.minecraft.command.argument.EntityArgumentType
                                                                .getPlayer(context, "player");

                                                if (owner.equals(target)) {
                                                    return 0;
                                                }

                                                Set<UUID> selected =
                                                        SELECTIONS.computeIfAbsent(
                                                                owner.getUuid(),
                                                                key -> ConcurrentHashMap.newKeySet());

                                                if (!selected.add(target.getUuid())) {
                                                    selected.remove(target.getUuid());
                                                }

                                                return Command.SINGLE_SUCCESS;
                                            }))
                    );

                    dispatcher.register(
                            CommandManager.literal("epconfirm")
                                    .requires(EnderPearlTP::isAllowed)
                                    .executes(context -> {
                                        giveRod(context.getSource().getPlayerOrThrow());
                                        return Command.SINGLE_SUCCESS;
                                    })
                    );
                });

        UseItemCallback.EVENT.register((player, world, hand) -> {

            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }

            if (world.isClient()) {
                return ActionResult.PASS;
            }

            ItemStack stack = serverPlayer.getStackInHand(hand);

            if (!stack.isOf(Items.FISHING_ROD)
                    || !ARMED_RODS.contains(serverPlayer.getUuid())) {
                return ActionResult.PASS;
            }

            // Angel entfernen, auch im Creative-Modus.
            serverPlayer.setStackInHand(hand, ItemStack.EMPTY);
            ARMED_RODS.remove(serverPlayer.getUuid());

            scheduleTeleport(serverPlayer);

            return ActionResult.SUCCESS;
        });

        // Wartet die 3 Sekunden ab und führt danach den Teleport aus.
        ServerTickEvents.END_SERVER_TICK.register(
                EnderPearlTP::tickPendingTeleports
        );
    }

    private static ServerWorld getServerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    private static MinecraftServer getServer(ServerPlayerEntity player) {
        return getServerWorld(player).getServer();
    }

    private static void openSelectionChat(ServerPlayerEntity player) {

        Set<UUID> selected =
                SELECTIONS.computeIfAbsent(
                        player.getUuid(),
                        key -> ConcurrentHashMap.newKeySet());

        // Die anklickbare Spielerliste bleibt erhalten.
        for (ServerPlayerEntity target :
                getServer(player).getPlayerManager().getPlayerList()) {

            if (target.getUuid().equals(player.getUuid())) {
                continue;
            }

            boolean isSelected = selected.contains(target.getUuid());

            String prefix = isSelected ? "§a[✓] " : "§c[ ] ";

            Text clickable = Text.literal(
                            prefix + target.getName().getString() + " §7(Klicken)")
                    .styled(style -> style.withClickEvent(
                            new ClickEvent.RunCommand(
                                    "/eptoggle " + target.getName().getString()
                            )));

            player.sendMessage(clickable, false);
        }
    }

    private static void giveRod(ServerPlayerEntity player) {

        Set<UUID> selected =
                SELECTIONS.getOrDefault(player.getUuid(), Set.of());

        String team = SELECTED_VEXBOT_TEAMS.get(player.getUuid());

        if (selected.isEmpty() && (team == null || team.isBlank())) {
            return;
        }

        ItemStack rod = new ItemStack(Items.FISHING_ROD);

        if (!player.getInventory().insertStack(rod)) {
            player.dropItem(rod, false);
        }

        ARMED_RODS.add(player.getUuid());
    }

    private static void scheduleTeleport(ServerPlayerEntity owner) {

        Set<UUID> selected =
                SELECTIONS.getOrDefault(owner.getUuid(), Set.of());

        String team = SELECTED_VEXBOT_TEAMS.get(owner.getUuid());

        if (selected.isEmpty() && (team == null || team.isBlank())) {
            return;
        }

        ServerWorld destinationWorld = getServerWorld(owner);

        double x = owner.getX();
        double y = owner.getY();
        double z = owner.getZ();

        float yaw = owner.getYaw();
        float pitch = owner.getPitch();

        List<ItemEntity> fallingPearls = new ArrayList<>();

        // Sichtbare Enderperlen von oben herunterfallen lassen.
        for (int i = 0; i < 12; i++) {

            double angle = (Math.PI * 2.0 * i) / 12.0;

            double pearlX = x + Math.cos(angle) * 2.0;
            double pearlZ = z + Math.sin(angle) * 2.0;

            ItemEntity pearl = new ItemEntity(
                    destinationWorld,
                    pearlX,
                    y + 8.0,
                    pearlZ,
                    new ItemStack(Items.ENDER_PEARL)
            );

            pearl.setNoGravity(true);
            pearl.setPickupDelay(32767);
            pearl.setVelocity(0.0, -0.13, 0.0);

            destinationWorld.spawnEntity(pearl);
            fallingPearls.add(pearl);
        }

        PENDING_TELEPORTS.add(
                new PendingTeleport(
                        owner.getUuid(),
                        destinationWorld,
                        x, y, z,
                        yaw, pitch,
                        new HashSet<>(selected),
                        team,
                        fallingPearls
                )
        );
    }

    private static void tickPendingTeleports(MinecraftServer server) {

        Iterator<PendingTeleport> iterator =
                PENDING_TELEPORTS.iterator();

        while (iterator.hasNext()) {

            PendingTeleport pending = iterator.next();

            pending.ticksRemaining--;

            if (pending.ticksRemaining <= 0) {

                // Die sichtbaren Enderperlen entfernen.
                for (ItemEntity pearl : pending.fallingPearls) {
                    pearl.discard();
                }

                executeTeleport(server, pending);

                iterator.remove();
            }
        }
    }

    private static void executeTeleport(
            MinecraftServer server,
            PendingTeleport pending
    ) {

        ServerWorld destinationWorld = pending.destinationWorld;

        double x = pending.x;
        double y = pending.y;
        double z = pending.z;

        float yaw = pending.yaw;
        float pitch = pending.pitch;

        // Portal-Partikelkreis erscheint erst beim Teleport.
        for (int i = 0; i < 24; i++) {

            double angle = (Math.PI * 2.0 * i) / 24.0;

            double px = x + Math.cos(angle) * 2.0;
            double pz = z + Math.sin(angle) * 2.0;

            destinationWorld.spawnParticles(
                    ParticleTypes.PORTAL,
                    px, y + 1.0, pz,
                    8,
                    0.12, 0.35, 0.12,
                    0.02
            );
        }

        // Ausgewählte Online-Spieler teleportieren.
        for (UUID id : pending.selectedPlayers) {

            ServerPlayerEntity target =
                    server.getPlayerManager().getPlayer(id);

            if (target != null) {

                target.teleport(
                        destinationWorld,
                        x, y, z,
                        Set.of(),
                        yaw,
                        pitch,
                        true
                );
            }
        }

        // VexBot-Team teleportieren.
        if (pending.team != null && !pending.team.isBlank()) {

            ServerPlayerEntity owner =
                    server.getPlayerManager().getPlayer(pending.ownerId);

            if (owner != null) {

                String command = "vexbot team " + pending.team + " tp "
                        + String.format(Locale.ROOT, "%.3f %.3f %.3f", x, y, z);

                try {
                    server.getCommandManager().parseAndExecute(
                            owner.getCommandSource(),
                            command
                    );
                } catch (Exception ignored) {
                    // Keine Chat-Fehlermeldung ausgeben.
                }
            }
        }

        SELECTIONS.remove(pending.ownerId);
        SELECTED_VEXBOT_TEAMS.remove(pending.ownerId);
        ARMED_RODS.remove(pending.ownerId);
    }

    private static class PendingTeleport {

        private final UUID ownerId;
        private final ServerWorld destinationWorld;

        private final double x;
        private final double y;
        private final double z;

        private final float yaw;
        private final float pitch;

        private final Set<UUID> selectedPlayers;
        private final String team;
        private final List<ItemEntity> fallingPearls;

        private int ticksRemaining = TELEPORT_DELAY_TICKS;

        private PendingTeleport(
                UUID ownerId,
                ServerWorld destinationWorld,
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                Set<UUID> selectedPlayers,
                String team,
                List<ItemEntity> fallingPearls
        ) {
            this.ownerId = ownerId;
            this.destinationWorld = destinationWorld;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.selectedPlayers = selectedPlayers;
            this.team = team;
            this.fallingPearls = fallingPearls;
        }
    }
}
