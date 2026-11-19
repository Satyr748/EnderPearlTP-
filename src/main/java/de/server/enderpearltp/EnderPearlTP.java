package de.server.enderpearltp;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EnderPearlTP implements ModInitializer {
    private static final Map<UUID, Set<UUID>> SELECTIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> ARMED_RODS = ConcurrentHashMap.newKeySet();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var command = CommandManager.literal("enderpearl")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    openSelectionChat(player);
                    return Command.SINGLE_SUCCESS;
                });

            dispatcher.register(command);
            dispatcher.register(CommandManager.literal("enderpearltp")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    giveRod(player);
                    return Command.SINGLE_SUCCESS;
                }));
            dispatcher.register(CommandManager.literal("eptoggle")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("player",
                    net.minecraft.command.argument.EntityArgumentType.player())
                    .executes(ctx -> {
                        ServerPlayerEntity owner = ctx.getSource().getPlayerOrThrow();
                        ServerPlayerEntity target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
                        Set<UUID> selected = SELECTIONS.computeIfAbsent(owner.getUuid(), k -> ConcurrentHashMap.newKeySet());
                        if (!selected.add(target.getUuid())) selected.remove(target.getUuid());
                        owner.sendMessage(Text.literal((selected.contains(target.getUuid()) ? "§aAusgewählt: " : "§cAbgewählt: ") + target.getName().getString()), false);
                        return Command.SINGLE_SUCCESS;
                    })));
            dispatcher.register(CommandManager.literal("epconfirm")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    giveRod(ctx.getSource().getPlayerOrThrow());
                    return Command.SINGLE_SUCCESS;
                }));
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(Items.FISHING_ROD) && ARMED_RODS.contains(player.getUuid())) {
                if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                    // Consume even in Creative: explicitly clear the used hand.
                    serverPlayer.setStackInHand(hand, ItemStack.EMPTY);
                    ARMED_RODS.remove(serverPlayer.getUuid());
                    teleportSelected(serverPlayer);
                }
                return TypedActionResult.success(ItemStack.EMPTY, world.isClient);
            }
            return TypedActionResult.pass(stack);
        });
    }

    private static void openSelectionChat(ServerPlayerEntity player) {
        Set<UUID> selected = SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
        player.sendMessage(Text.literal("§6§lEnderPearlTP §7– Spieler auswählen"), false);
        player.sendMessage(Text.literal("§7Klicke einen der folgenden Chat-Befehle oder tippe ihn ein:"), false);
        for (ServerPlayerEntity target : player.getServer().getPlayerManager().getPlayerList()) {
            if (!target.getUuid().equals(player.getUuid())) {
                boolean isSelected = selected.contains(target.getUuid());
                player.sendMessage(Text.literal((isSelected ? "§a[✓] " : "§c[ ] ")
                    + target.getName().getString() + " §8→ §e/eptoggle " + target.getName().getString()), false);
            }
        }
        player.sendMessage(Text.literal("§aWenn fertig: §e/epconfirm §7– du bekommst deine Fishing Rod."), false);
        player.sendMessage(Text.literal("§8Alternative: /enderpearltp gibt direkt eine Rod aus."), false);
    }

    private static void giveRod(ServerPlayerEntity player) {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        if (!player.getInventory().insertStack(rod)) player.dropItem(rod, false);
        ARMED_RODS.add(player.getUuid());
        player.sendMessage(Text.literal("§aNormale Fishing Rod erhalten! Rechtsklick aktiviert den Teleport."), false);
    }

    private static void teleportSelected(ServerPlayerEntity owner) {
        Set<UUID> selected = SELECTIONS.getOrDefault(owner.getUuid(), Set.of());
        if (selected.isEmpty()) {
            owner.sendMessage(Text.literal("§cDu hast keine Spieler ausgewählt."), false);
            return;
        }

        var server = owner.getServer();
        var destinationWorld = owner.getServerWorld();
        double x = owner.getX(), y = owner.getY(), z = owner.getZ();
        float yaw = owner.getYaw(), pitch = owner.getPitch();

        // Ender-like particles in a ring around the caller's current position.
        for (int i = 0; i < 24; i++) {
            double angle = (Math.PI * 2.0 * i) / 24.0;
            double px = x + Math.cos(angle) * 2.0;
            double pz = z + Math.sin(angle) * 2.0;
            destinationWorld.spawnParticles(ParticleTypes.PORTAL, px, y + 1.0, pz, 8, 0.12, 0.35, 0.12, 0.02);
        }

        int moved = 0;
        for (UUID id : new HashSet<>(selected)) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(id);
            if (target != null && target != owner) {
                target.teleport(destinationWorld, x, y, z, Set.of(), yaw, pitch, true);
                target.sendMessage(Text.literal("§dDu wurdest zu " + owner.getName().getString() + " teleportiert."), false);
                moved++;
            }
        }
        owner.sendMessage(Text.literal("§a" + moved + " Spieler teleportiert."), false);
        ARMED_RODS.remove(owner.getUuid());
        SELECTIONS.remove(owner.getUuid());
    }
}
