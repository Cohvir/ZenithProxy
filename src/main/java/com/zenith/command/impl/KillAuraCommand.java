package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.discord.Embed;
import com.zenith.module.impl.KillAura;
import com.zenith.util.Config;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Shared.CONFIG;
import static com.zenith.Shared.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

public class KillAuraCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("killAura")
            .category(CommandCategory.MODULE)
            .description("""
             Attacks entities near the player.
             
             Custom targets list: https://link.2b2t.vc/1
             
             Aggressive mobs are mobs that are actively targeting and attacking the player.
             """)
            .usageLines(
                "on/off",
                "attackDelay <ticks>",
                "targetPlayers on/off",
                "targetHostileMobs on/off",
                "targetHostileMobs onlyAggressive on/off",
                "targetNeutralMobs on/off",
                "targetNeutralMobs onlyAggressive on/off",
                "targetArmorStands on/off",
                "targetCustom on/off",
                "targetCustom add/del <entityType>",
                "weaponSwitch on/off",
                "priority <none/nearest>"
            )
            .aliases("ka")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("killAura")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.client.extra.killAura.enabled = getToggle(c, "toggle");
                MODULE.get(KillAura.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                             .title("Kill Aura " + toggleStrCaps(CONFIG.client.extra.killAura.enabled));
                return OK;
            }))
            .then(literal("attackDelay")
                      .then(argument("ticks", integer(0, 1000)).executes(c -> {
                          CONFIG.client.extra.killAura.attackDelayTicks = c.getArgument("ticks", Integer.class);
                          c.getSource().getEmbed()
                                       .title("Attack Delay Ticks Set!");
                          return OK;
                      })))
            .then(literal("targetPlayers")
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.targetPlayers = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Target Players " + toggleStrCaps(CONFIG.client.extra.killAura.targetPlayers));
                            return OK;
                      })))
            .then(literal("targetHostileMobs")
                      .then(literal("onlyAggressive").then(argument("toggle", toggle()).executes(c -> {
                          CONFIG.client.extra.killAura.onlyHostileAggressive = getToggle(c, "toggle");
                          c.getSource().getEmbed()
                              .title("Target Hostile Mobs Only Aggressive " + toggleStrCaps(CONFIG.client.extra.killAura.onlyHostileAggressive));
                          return OK;
                      })))
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.targetHostileMobs = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Target Mobs " + toggleStrCaps(CONFIG.client.extra.killAura.targetHostileMobs));
                            return OK;
                      })))
            .then(literal("targetNeutralMobs")
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.targetNeutralMobs = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Target Neutral Mobs " + toggleStrCaps(CONFIG.client.extra.killAura.targetNeutralMobs));
                            return OK;
                      }))
                      .then(literal("onlyAggressive")
                                .then(argument("toggle", toggle()).executes(c -> {
                                    CONFIG.client.extra.killAura.onlyNeutralAggressive = getToggle(c, "toggle");
                                    c.getSource().getEmbed()
                                                 .title("Target Neutral Mobs Only Aggressive " + toggleStrCaps(CONFIG.client.extra.killAura.onlyNeutralAggressive));
                                    return OK;
                                }))))
            .then(literal("targetArmorStands")
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.targetArmorStands = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Target Armor Stands " + toggleStrCaps(CONFIG.client.extra.killAura.targetArmorStands));
                            return OK;
                      })))
            .then(literal("weaponSwitch")
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.switchWeapon = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Weapon Switching " + toggleStrCaps(CONFIG.client.extra.killAura.switchWeapon));
                            return OK;
                      })))
            .then(literal("targetCustom")
                      .then(argument("toggle", toggle()).executes(c -> {
                            CONFIG.client.extra.killAura.targetCustom = getToggle(c, "toggle");
                            c.getSource().getEmbed()
                                         .title("Target Custom " + toggleStrCaps(CONFIG.client.extra.killAura.targetCustom));
                            return OK;
                      }))
                      .then(literal("add")
                                .then(argument("entityType", enumStrings(EntityType.values())).executes(c -> {
                                    var entityType = c.getArgument("entityType", String.class);
                                    var foundType = entityType.toUpperCase();
                                    try {
                                        var type = Enum.valueOf(EntityType.class, foundType);
                                        if (!CONFIG.client.extra.killAura.customTargets.contains(type))
                                            CONFIG.client.extra.killAura.customTargets.add(type);
                                        c.getSource().getEmbed()
                                                     .title("Added " + type.name());
                                    } catch (Exception e) {
                                        c.getSource().getEmbed()
                                                     .title("Invalid Entity Type")
                                                     .errorColor();
                                    }
                                    return OK;
                                })))
                      .then(literal("del")
                                .then(argument("entityType", enumStrings(EntityType.values())).executes(c -> {
                                    var entityType = c.getArgument("entityType", String.class);
                                    var foundType = entityType.toUpperCase();
                                    try {
                                        var type = Enum.valueOf(EntityType.class, foundType);
                                        CONFIG.client.extra.killAura.customTargets.remove(type);
                                        c.getSource().getEmbed()
                                            .title("Removed " + type.name());
                                    } catch (Exception e) {
                                        c.getSource().getEmbed()
                                            .title("Invalid Entity Type")
                                            .errorColor();
                                    }
                                    return OK;
                                }))))
            .then(literal("priority")
                      .then(literal("none").executes(c -> {
                          CONFIG.client.extra.killAura.priority = Config.Client.Extra.KillAura.Priority.NONE;
                          c.getSource().getEmbed()
                              .title("Priority Set");
                          return OK;
                      }))
                      .then(literal("nearest").executes(c -> {
                          CONFIG.client.extra.killAura.priority = Config.Client.Extra.KillAura.Priority.NEAREST;
                          c.getSource().getEmbed()
                              .title("Priority Set");
                          return OK;
                      })));
    }

    @Override
    public void postPopulate(Embed builder) {
        builder
            .addField("KillAura", toggleStr(CONFIG.client.extra.killAura.enabled), false)
            .addField("Target Players", toggleStr(CONFIG.client.extra.killAura.targetPlayers), false)
            .addField("Target Hostile Mobs", toggleStr(CONFIG.client.extra.killAura.targetHostileMobs), false)
            .addField("Target Neutral Mobs", toggleStr(CONFIG.client.extra.killAura.targetNeutralMobs), false)
            .addField("Target Custom", toggleStr(CONFIG.client.extra.killAura.targetCustom), false)
            .addField("Only Aggressive Neutral Mobs", toggleStr(CONFIG.client.extra.killAura.onlyNeutralAggressive), false)
            .addField("Only Aggressive Hostile Mobs", toggleStr(CONFIG.client.extra.killAura.onlyHostileAggressive), false)
            .addField("Target Armor Stands", toggleStr(CONFIG.client.extra.killAura.targetArmorStands), false)
            .addField("Weapon Switching", toggleStr(CONFIG.client.extra.killAura.switchWeapon), false)
            .addField("Attack Delay Ticks", CONFIG.client.extra.killAura.attackDelayTicks, false)
            .addField("Priority", CONFIG.client.extra.killAura.priority.name().toLowerCase(), false)
            .primaryColor();
        if (CONFIG.client.extra.killAura.targetCustom) {
            builder.description("**Custom Targets**\n" + CONFIG.client.extra.killAura.customTargets.stream().map(Enum::name).collect(
                Collectors.joining(", ", "[", "]")));
        }
    }
}
