package com.zenith.network.server.handler.shared.incoming;

import com.zenith.network.registry.PacketHandler;
import com.zenith.network.server.ServerSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.jspecify.annotations.NonNull;

public class PongHandler implements PacketHandler<ServerboundPongPacket, ServerSession> {
    @Override
    public ServerboundPongPacket apply(@NonNull ServerboundPongPacket packet, @NonNull ServerSession session) {
        if (packet.getId() == session.getLastPingId()) {
            session.setPing(System.currentTimeMillis() - session.getLastPingTime());
        }
        return packet;
    }
}
