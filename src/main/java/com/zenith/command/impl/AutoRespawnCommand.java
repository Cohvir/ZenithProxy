package com.zenith.command.impl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.discord.Embed;
import com.zenith.module.impl.AutoRespawn;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Shared.CONFIG;
import static com.zenith.Shared.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class AutoRespawnCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoRespawn")
            .category(CommandCategory.MODULE)
            .description("""
            Automatically respawns the player after dying.
            """)
            .usageLines(
                "on/off",
                "delay <milliseconds>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoRespawn")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.client.extra.autoRespawn.enabled = getToggle(c, "toggle");
                MODULE.get(AutoRespawn.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("AutoRespawn " + toggleStrCaps(CONFIG.client.extra.autoRespawn.enabled));
                return OK;
            }))
            .then(literal("delay").then(argument("delay", integer(0)).executes(c -> {
                CONFIG.client.extra.autoRespawn.delayMillis = IntegerArgumentType.getInteger(c, "delay");
                c.getSource().getEmbed()
                    .title("AutoRespawn Delay Updated!");
                return OK;
            })));
    }

    @Override
    public void postPopulate(final Embed builder) {
        builder
            .addField("AutoRespawn", toggleStr(CONFIG.client.extra.autoRespawn.enabled), false)
            .addField("Delay (ms)", CONFIG.client.extra.autoRespawn.delayMillis, true)
            .primaryColor();
    }
}
