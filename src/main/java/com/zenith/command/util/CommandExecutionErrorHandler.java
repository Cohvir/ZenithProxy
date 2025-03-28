package com.zenith.command.util;

import com.zenith.command.brigadier.CommandContext;

@FunctionalInterface
public interface CommandExecutionErrorHandler {
    void handle(CommandContext context);
}
