package com.zenith.network.server.handler.player.incoming;

import com.zenith.network.registry.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.jspecify.annotations.NonNull;

import static com.zenith.Shared.*;

public class ChatHandler implements PacketHandler<ServerboundChatPacket, ServerSession> {
    @Override
    public ServerboundChatPacket apply(@NonNull ServerboundChatPacket packet, @NonNull ServerSession session) {
        if (CONFIG.inGameCommands.enable) {
            final String message = packet.getMessage();
            if (IN_GAME_COMMAND.isCommandPrefixed(message)) {
                EXECUTOR.execute(() -> IN_GAME_COMMAND.handleInGameCommand(message.substring(CONFIG.inGameCommands.prefix.length()), session, true));
                return null;
            }
        }
        return packet;
    }
}
