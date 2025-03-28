package com.zenith.command.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.discord.Embed;

import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Shared.PLAYER_LISTS;
import static com.zenith.command.util.CommandOutputHelper.playerListToString;
import static com.zenith.discord.DiscordBot.escape;

public class FriendCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("friend")
            .category(CommandCategory.MANAGE)
            .description("""
            Manage the friend list.
            Friends change behavior for various modules like VisualRange, KillAura, and AutoDisconnect
            """)
            .usageLines(
                "add/del <player>",
                "list",
                "clear"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("friend")
            .then(literal("add").then(argument("player", string()).executes(c -> {
                final String player = StringArgumentType.getString(c, "player");
                PLAYER_LISTS.getFriendsList().add(player)
                    .ifPresentOrElse(e ->
                                         c.getSource().getEmbed()
                                             .title("Friend added"),
                                     () -> c.getSource().getEmbed()
                                         .title("Failed to add user: " + escape(player) + " to friends. Unable to lookup profile."));
                return OK;
            })))
            .then(literal("del").then(argument("player", string()).executes(c -> {
                final String player = StringArgumentType.getString(c, "player");
                PLAYER_LISTS.getFriendsList().remove(player);
                c.getSource().getEmbed()
                    .title("Friend deleted");
                return OK;
            })))
            .then(literal("list").executes(c -> {
                c.getSource().getEmbed()
                    .title("Friend list");
                return OK;
            }))
            .then(literal("clear").executes(c -> {
                PLAYER_LISTS.getFriendsList().clear();
                c.getSource().getEmbed()
                    .title("Friend list cleared!");
                return OK;
            }));
    }

    @Override
    public void postPopulate(final Embed builder) {
        builder
            .description("**Friend List**\n" + playerListToString(PLAYER_LISTS.getFriendsList()))
            .primaryColor();
    }
}
