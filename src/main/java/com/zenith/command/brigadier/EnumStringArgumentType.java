package com.zenith.command.brigadier;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;

@Getter
public class EnumStringArgumentType implements ArgumentType<String> {
    private final String[] values;

    public EnumStringArgumentType(final String[] values) {
        this.values = values;
    }

    @Override
    public String parse(final StringReader reader) throws CommandSyntaxException {
        final String value = reader.readUnquotedString();
        for (final String val : values) {
            if (val.equalsIgnoreCase(value)) {
                return val;
            }
        }
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.literalIncorrect().createWithContext(reader, value);
    }

    @Override
    public CompletableFuture<Suggestions> listSuggestions(final CommandContext context, final SuggestionsBuilder builder) {
        for (final String val : values) {
            if (val.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(val);
            }
        }
        return builder.buildFuture();
    }
}
