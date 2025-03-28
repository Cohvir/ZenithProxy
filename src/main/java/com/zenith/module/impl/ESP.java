package com.zenith.module.impl;

import com.zenith.feature.esp.GlowingEntityMetadataPacketHandler;
import com.zenith.module.Module;
import com.zenith.network.registry.PacketHandlerCodec;
import com.zenith.network.registry.PacketHandlerStateCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;

import static com.zenith.Shared.CONFIG;

public class ESP extends Module {
    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("esp")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .registerOutbound(ClientboundSetEntityDataPacket.class, new GlowingEntityMetadataPacketHandler())
                .build())
            .build();
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.server.extra.esp.enable;
    }
}
