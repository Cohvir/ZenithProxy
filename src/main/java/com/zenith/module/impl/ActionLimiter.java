package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.proxy.PlayerLoginEvent;
import com.zenith.event.proxy.ServerConnectionRemovedEvent;
import com.zenith.feature.actionlimiter.handlers.inbound.*;
import com.zenith.feature.actionlimiter.handlers.outbound.ALCMoveVehicleHandler;
import com.zenith.feature.actionlimiter.handlers.outbound.ALLoginHandler;
import com.zenith.feature.actionlimiter.handlers.outbound.ALPlayerPositionHandler;
import com.zenith.module.Module;
import com.zenith.network.registry.PacketHandlerCodec;
import com.zenith.network.registry.PacketHandlerStateCodec;
import com.zenith.network.server.ServerSession;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundEditBookPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.CACHE;
import static com.zenith.Shared.CONFIG;

public class ActionLimiter extends Module {
    private final ReferenceSet<ServerSession> limitedConnections = new ReferenceOpenHashSet<>();

    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("action-limiter")
            .setPriority(1000)
            .setActivePredicate(this::shouldLimit)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .registerInbound(ServerboundChatCommandPacket.class, new ALChatCommandHandler())
                .registerInbound(ServerboundChatCommandSignedPacket.class, new ALSignedChatCommandHandler())
                .registerInbound(ServerboundChatPacket.class, new ALChatHandler())
                .registerInbound(ServerboundClientCommandPacket.class, new ALClientCommandHandler())
                .registerInbound(ServerboundContainerClickPacket.class, new ALContainerClickHandler())
                .registerInbound(ServerboundInteractPacket.class, new ALInteractHandler())
                .registerInbound(ServerboundMovePlayerPosPacket.class, new ALMovePlayerPosHandler())
                .registerInbound(ServerboundMovePlayerPosRotPacket.class, new ALMovePlayerPosRotHandler())
                .registerInbound(ServerboundMoveVehiclePacket.class, new ALMoveVehicleHandler())
                .registerInbound(ServerboundPlayerActionPacket.class, new ALPlayerActionHandler())
                .registerInbound(ServerboundUseItemOnPacket.class, new ALUseItemOnHandler())
                .registerInbound(ServerboundUseItemPacket.class, new ALUseItemHandler())
                .registerInbound(ServerboundEditBookPacket.class, new ALEditBookHandler())
                .registerOutbound(ClientboundMoveVehiclePacket.class, new ALCMoveVehicleHandler())
                .registerOutbound(ClientboundLoginPacket.class, new ALLoginHandler())
                .registerOutbound(ClientboundPlayerPositionPacket.class, new ALPlayerPositionHandler())
                .build())
            .build();
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(PlayerLoginEvent.class, this::onPlayerLoginEvent),
            of(ServerConnectionRemovedEvent.class, this::onServerConnectionRemoved)
        );
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.actionLimiter.enabled;
    }

    public void onPlayerLoginEvent(final PlayerLoginEvent event) {
        ServerSession serverConnection = event.serverConnection();
        var profile = serverConnection.getProfileCache().getProfile();
        var proxyProfile = CACHE.getProfileCache().getProfile();
        if (profile != null && proxyProfile != null && profile.getId().equals(proxyProfile.getId()))
            return;
        limitedConnections.add(serverConnection);
    }

    public void onServerConnectionRemoved(final ServerConnectionRemovedEvent event) {
        limitedConnections.remove(event.serverConnection());
    }

    public boolean shouldLimit(final ServerSession serverConnection) {
        return limitedConnections.contains(serverConnection);
    }
}
