package de.server.enderpearltp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
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

import java.util.HashSet;
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

                                                        player.sendMessage(
                                                                Text.literal("§aVexBot-Team ausgewählt: §e" + team),
                                                                false);

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
                                                    owner.sendMessage(
                                                            Text.literal("§cDu kannst dich nicht selbst auswählen."),
                                                            false);
                                                    return 0;
                                                }

                                                Set<UUID> selected =
                                                        SELECTIONS.computeIfAbsent(
                                                                owner.getUuid(),
                                                                key -> ConcurrentHashMap.newKeySet());

                                                if (!selected.add(target.getUuid())) {
                                                    selected.remove(target.getUuid());
                                                }

                                                owner.sendMessage(
                                                        Text.literal(
                                                                (selected.contains(target.getUuid())
                                                                        ? "§aAusgewählt: "
                                                                        : "§cAbgewählt: ")
                                                                        + target.getName().getString()),
                                                        false);

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

            serverPlayer.setStackInHand(hand, ItemStack.EMPTY);
            ARMED_RODS.remove(serverPlayer.getUuid());

            teleportSelected(serverPlayer);

            return ActionResult.SUCCESS;
        });
    }

    private static ServerWorld getServerWorld(ServerPlayerEntity player) {
        return (ServerWorld) player.getEntityWorld();
    }

    private static MinecraftServer getServer(ServerPlayerEntity player) {
        return getServerWorld(player).getServer();
    }

    private static void openSelectionChat(ServerPlayerEntity player) {

        Set<UUID> selected =
                SELECTIONS.computeIfAbsent(
                        player.getUuid(),
                        key -> ConcurrentHashMap.newKeySet());

        player.sendMessage(
                Text.literal("§6§lEnderPearlTP §7- Spieler auswählen"),
                false);

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

        player.sendMessage(
                Text.literal("§eVexBot-Team auswählen: §f/enderpearl team <Teamname>"),
                false);

        player.sendMessage(
                Text.literal("§aFertig? Benutze /epconfirm."),
                false);
    }

    private static void giveRod(ServerPlayerEntity player) {

        Set<UUID> selected =
                SELECTIONS.getOrDefault(player.getUuid(), Set.of());

        String team = SELECTED_VEXBOT_TEAMS.get(player.getUuid());

        if (selected.isEmpty() && (team == null || team.isBlank())) {
            player.sendMessage(
                    Text.literal("§cWähle zuerst Spieler oder ein VexBot-Team aus."),
                    false);
            return;
        }

        ItemStack rod = new ItemStack(Items.FISHING_ROD);

        if (!player.getInventory().insertStack(rod)) {
            player.dropItem(rod, false);
        }

        ARMED_RODS.add(player.getUuid());

        player.sendMessage(
                Text.literal("§aFishing Rod erhalten! Rechtsklick zum Teleportieren."),
                false);
    }

    private static void teleportSelected(ServerPlayerEntity owner) {

        Set<UUID> selected =
                SELECTIONS.getOrDefault(owner.getUuid(), Set.of());

        String team = SELECTED_VEXBOT_TEAMS.get(owner.getUuid());

        if (selected.isEmpty() && (team == null || team.isBlank())) {
            owner.sendMessage(
                    Text.literal("§cKeine Spieler oder kein VexBot-Team ausgewählt."),
                    false);
            return;
        }

        ServerWorld destinationWorld = getServerWorld(owner);

        double x = owner.getX();
        double y = owner.getY();
        double z = owner.getZ();

        float yaw = owner.getYaw();
        float pitch = owner.getPitch();

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

        int moved = 0;
        MinecraftServer server = getServer(owner);

        for (UUID id : new HashSet<>(selected)) {

            ServerPlayerEntity target =
                    server.getPlayerManager().getPlayer(id);

            if (target != null && target != owner) {

                target.teleport(
                        destinationWorld,
                        x, y, z,
                        Set.of(),
                        yaw,
                        pitch,
                        true
                );

                target.sendMessage(
                        Text.literal("§dDu wurdest zu "
                                + owner.getName().getString()
                                + " teleportiert."),
                        false
                );

                moved++;
            }
        }

        if (team != null && !team.isBlank()) {

            String command = "vexbot team " + team + " tp "
                    + String.format(Locale.ROOT, "%.3f %.3f %.3f", x, y, z);

            try {
                server.getCommandManager().executeWithPrefix(
                        owner.getCommandSource(),
                        command);

                owner.sendMessage(
                        Text.literal("§aVexBot-Team-Befehl für §e" + team
                                + " §awurde ausgeführt."),
                        false);

            } catch (Exception exception) {
                owner.sendMessage(
                        Text.literal("§cFehler beim VexBot-Team-Teleport: "
                                + exception.getMessage()),
                        false);
            }
        }

        owner.sendMessage(
                Text.literal("§a" + moved + " ausgewählte Spieler teleportiert."),
                false
        );

        ARMED_RODS.remove(owner.getUuid());
        SELECTIONS.remove(owner.getUuid());
        SELECTED_VEXBOT_TEAMS.remove(owner.getUuid());
    }
}
