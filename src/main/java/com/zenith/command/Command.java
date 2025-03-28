package com.zenith.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.zenith.command.brigadier.*;
import com.zenith.command.util.CommandErrorHandler;
import com.zenith.discord.Embed;
import com.zenith.network.server.ServerSession;
import com.zenith.util.MentionUtil;
import net.dv8tion.jda.api.entities.ISnowflake;
import org.geysermc.mcprotocollib.auth.GameProfile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.zenith.Shared.*;

public abstract class Command {
    public static <T> ZRequiredArgumentBuilder<CommandContext, T> argument(String name, ArgumentType<T> type) {
        return ZRequiredArgumentBuilder.argument(name, type);
    }

    // command return codes
    public static final int OK = 1;
    public static final int ERROR = -1;

    public static boolean validateAccountOwner(final CommandContext context) {
        try {
            final boolean allowed = switch (context.getSource()) {
                case DISCORD -> validateAccountOwnerDiscord(context);
                case TERMINAL -> true;
                case IN_GAME_PLAYER,SPECTATOR -> validatePlayerIsAccountOwner(context);
            };
            if (!allowed) {
                context.getEmbed()
                    .title("Not Authorized!")
                    .errorColor();
            }
            return allowed;
        } catch (final Throwable e) {
            DEFAULT_LOG.error("Error validating command account owner authorization", e);
            return false;
        }
    }

    private static boolean validatePlayerIsAccountOwner(final CommandContext context) {
        final ServerSession currentPlayer = context.getInGamePlayerInfo().session();
        if (currentPlayer == null) return false;
        final GameProfile playerProfile = currentPlayer.getProfileCache().getProfile();
        if (playerProfile == null) return false;
        final UUID playerUUID = playerProfile.getId();
        if (playerUUID == null) return false;
        boolean allowed;
        if (CONFIG.inGameCommands.allowWhitelistedToUseAccountOwnerCommands) {
            allowed = PLAYER_LISTS.getWhitelist().contains(playerUUID);
        } else {
            final GameProfile proxyProfile = CACHE.getProfileCache().getProfile();
            if (proxyProfile == null) return false;
            final UUID proxyUUID = proxyProfile.getId();
            if (proxyUUID == null) return false;
            allowed = playerUUID.equals(proxyUUID); // we have to be logged in with the owning MC account
        }
        if (!allowed) {
            context.getEmbed()
                .addField("Error",
                    "Player: " + playerProfile.getName()
                        + " is not authorized to execute this command! "
                        + (CONFIG.inGameCommands.allowWhitelistedToUseAccountOwnerCommands ? "You must be whitelisted!" : "You must be logged in with the proxy's MC account!"),
                    false);
        }
        return allowed;
    }

    private static boolean validateAccountOwnerDiscord(final CommandContext context) {
        final DiscordCommandContext discordCommandContext = (DiscordCommandContext) context;
        var event = discordCommandContext.getMessageReceivedEvent();
        final boolean hasAccountOwnerRole = Optional.ofNullable(event.getMember())
            .orElseThrow(() -> new RuntimeException("Message does not have a valid member"))
            .getRoles()
            .stream()
            .map(ISnowflake::getId)
            .anyMatch(roleId -> roleId.equals(CONFIG.discord.accountOwnerRoleId));
        if (!hasAccountOwnerRole) {
            String accountOwnerRoleMention = "";
            try {
                accountOwnerRoleMention = MentionUtil.forRole(CONFIG.discord.accountOwnerRoleId);
            } catch (final Exception e) {
                // fall through
            }
            context.getEmbed()
                .addField("Error",
                          "User: " + Optional.ofNullable(event.getMember()).map(m -> m.getUser().getName()).orElse("Unknown")
                              + " is not authorized to execute this command! "
                              + "You must have the account owner role: " + accountOwnerRoleMention, false);
        }
        return hasAccountOwnerRole;
    }

    public static boolean validateCommandSource(final CommandContext context, final List<CommandSource> allowedSources) {
        var allowed = allowedSources.contains(context.getSource());
        if (!allowed)
            context.getEmbed()
                .addField("Error",
                          "Command source: " + context.getSource().getName()
                              + " is not authorized to execute this command!", false);
        return allowed;
    }

    public static boolean validateCommandSource(final CommandContext context, final CommandSource allowedSource) {
        var allowed = allowedSource.equals(context.getSource());
        if (!allowed)
            context.getEmbed()
                .addField("Error",
                          "Command source: " + context.getSource().getName()
                              + " is not authorized to execute this command!", false);
        return allowed;
    }

    public static CaseInsensitiveLiteralArgumentBuilder<CommandContext> literal(String literal) {
        return CaseInsensitiveLiteralArgumentBuilder.literal(literal);
    }

    public static CaseInsensitiveLiteralArgumentBuilder<CommandContext> literal(String literal, CommandErrorHandler errorHandler) {
        return literal(literal).withErrorHandler(errorHandler);
    }

    public static CaseInsensitiveLiteralArgumentBuilder<CommandContext> requires(String literal, Predicate<CommandContext> requirement) {
        return literal(literal).requires(requirement);
    }

    public static EnumStringArgumentType enumStrings(String... strings) {
        return new EnumStringArgumentType(strings);
    }

    public static EnumStringArgumentType enumStrings(Enum<?>[] enumValues) {
        String[] names = new String[enumValues.length];
        for (int i = 0; i < enumValues.length; i++) {
            names[i] = enumValues[i].name().toLowerCase();
        }
        return enumStrings(names);
    }

    public static String toggleStr(boolean state) {
        return state ? "on" : "off";
    }

    public static String toggleStrCaps(boolean state) {
        return state ? "On!" : "Off!";
    }

    /**
     * Required. Registers {@link CommandUsage}
     */
    public abstract CommandUsage commandUsage();

    /**
     * Required. Register a {@link #command}
     */
    public abstract LiteralArgumentBuilder<CommandContext> register();

    /**
     * Override to populate the embed builder after every execution, including both success and error cases.
     * Don't include sensitive info, there is no permission validation.
     */
    public void postPopulate(final Embed builder) {}

    public CaseInsensitiveLiteralArgumentBuilder<CommandContext> command(String literal) {
        return literal(literal)
            .withErrorHandler(this::defaultErrorHandler)
            .withSuccessHandler(this::defaultSuccessHandler)
            .withExecutionErrorHandler(this::defaultExecutionErrorHandler);
    }

    /**
     * Workaround for no-arg redirect nodes
     * see https://github.com/Mojang/brigadier/issues/46
     * 4 years and no official fix T.T
     */
    public LiteralArgumentBuilder<CommandContext> redirect(String literal, final CommandNode<CommandContext> destination) {
        final LiteralArgumentBuilder<CommandContext> builder = command(literal)
                .requires(destination.getRequirement())
                .forward(destination.getRedirect(), destination.getRedirectModifier(), destination.isFork())
                .executes(destination.getCommand());
        for (final CommandNode<CommandContext> child : destination.getChildren()) {
            builder.then(child);
        }
        return builder;
    }

    public void defaultSuccessHandler(CommandContext context) {
        postPopulate(context.getEmbed());
    }

    public void defaultErrorHandler(Map<CommandNode<CommandContext>, CommandSyntaxException> exceptions, CommandContext context) {
        exceptions.values().stream()
            .findFirst()
            .ifPresent(exception -> context.getEmbed()
                .addField("Error", exception.getMessage(), false));
        postPopulate(context.getEmbed());
        if (!context.getEmbed().isTitlePresent()) {
            context.getEmbed()
                .title("Invalid command usage");
        }
        context.getEmbed()
                .addField("Usage", commandUsage().serialize(context.getSource()), false)
                .errorColor();
    }

    public void defaultExecutionErrorHandler(CommandContext commandContext) {
        postPopulate(commandContext.getEmbed());
        commandContext.getEmbed()
            .errorColor();
    }
}
