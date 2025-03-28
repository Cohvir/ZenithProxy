package com.zenith.command.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.discord.Embed;
import com.zenith.module.impl.AutoReconnect;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Shared.CONFIG;
import static com.zenith.Shared.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class AutoReconnectCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoReconnect")
            .category(CommandCategory.MODULE)
            .description("""
             Automatically reconnects the bot when it is disconnected.
             """)
            .usageLines(
                "on/off",
                "delay <seconds>",
                "maxAttempts <number>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoReconnect")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.client.extra.autoReconnect.enabled = getToggle(c, "toggle");
                MODULE.get(AutoReconnect.class).syncEnabledFromConfig();
                MODULE.get(AutoReconnect.class).cancelAutoReconnect();
                c.getSource().getEmbed()
                    .title("AutoReconnect " + toggleStrCaps(CONFIG.client.extra.autoReconnect.enabled));
                return OK;
            }))
            .then(literal("delay")
                      .then(argument("delaySec", integer(0, 1000)).executes(c -> {
                          CONFIG.client.extra.autoReconnect.delaySeconds = IntegerArgumentType.getInteger(c, "delaySec");
                          c.getSource().getEmbed()
                              .title("AutoReconnect Delay Updated!");
                          return OK;
                      })))
            .then(literal("maxAttempts")
                      .then(argument("maxAttempts", integer(1)).executes(c -> {
                          CONFIG.client.extra.autoReconnect.maxAttempts = IntegerArgumentType.getInteger(c, "maxAttempts");
                          c.getSource().getEmbed()
                              .title("AutoReconnect Max Attempts Updated!");
                          return OK;
                      })));
    }

    @Override
    public void postPopulate(final Embed builder) {
        builder
            .addField("AutoReconnect", toggleStr(CONFIG.client.extra.autoReconnect.enabled), false)
            .addField("Delay", CONFIG.client.extra.autoReconnect.delaySeconds, true)
            .addField("Max Attempts", CONFIG.client.extra.autoReconnect.maxAttempts, true)
            .primaryColor();
    }
}
