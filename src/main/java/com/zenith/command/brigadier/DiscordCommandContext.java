package com.zenith.command.brigadier;

import com.zenith.discord.Embed;
import lombok.Getter;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.ArrayList;
import java.util.List;

public class DiscordCommandContext extends CommandContext {
    @Getter
    private final MessageReceivedEvent messageReceivedEvent;

    public DiscordCommandContext(final String input, Embed embedBuilder, List<String> multiLineOutput, final MessageReceivedEvent messageReceivedEvent) {
        super(input, CommandSource.DISCORD, embedBuilder, multiLineOutput);
        this.messageReceivedEvent = messageReceivedEvent;
    }

    public static DiscordCommandContext create(final String input, final MessageReceivedEvent messageReceivedEvent) {
        return new DiscordCommandContext(input.trim(), new Embed(), new ArrayList<>(), messageReceivedEvent);
    }
}
