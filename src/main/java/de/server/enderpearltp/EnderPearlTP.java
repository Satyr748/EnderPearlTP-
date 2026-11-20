
package de.server.enderpearltp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
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

    private static final List<PendingTeleport> PENDING_TELEPORTS =
            new ArrayList<>();

    private static final String SPECIAL_ROD_TAG = "EnderPearlTPSpecialRod";

    // 30 Ticks = ungefähr 1,5 Sekunden.
    private static final int TELEPORT_DELAY_TICKS = 30;

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
