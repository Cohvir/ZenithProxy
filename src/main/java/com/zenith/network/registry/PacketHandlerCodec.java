package com.zenith.network.registry;

import com.zenith.network.client.ClientSession;
import com.zenith.network.server.ServerSession;
import lombok.*;
import lombok.experimental.Accessors;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;

import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Predicate;

@Data
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PacketHandlerCodec {
    /**
     * For inbound packets, higher priority codecs are invoked first
     * For outbound packets, lower priority codecs are invoked first
     */
    private final int priority;
    @EqualsAndHashCode.Include private final String id;
    private final EnumMap<ProtocolState, PacketHandlerStateCodec<? extends Session>> stateCodecs;
    // Predicate called on each packet to determine if it should be handled by this codec
    private final Predicate<Session> activePredicate;

    public static Builder builder() {
        return new Builder();
    }

    public static Builder<ClientSession> clientBuilder() {
        return new Builder<>();
    }

    public static Builder<ServerSession> serverBuilder() {
        return new Builder<>();
    }

    protected static final PacketHandlerStateCodec defaultStateCodec = PacketHandlerStateCodec.builder().build();

    public <S extends Session> @NonNull PacketHandlerStateCodec<S> getCodec(ProtocolState state) {
        return this.stateCodecs.getOrDefault(state, defaultStateCodec);
    }

    public <P extends Packet, S extends Session> P handleInbound(@NonNull P packet, @NonNull S session) {
        return getCodec(session.getPacketProtocol().getInboundState()).handleInbound(packet, session);
    }

    public <P extends Packet, S extends Session> P handleOutgoing(@NonNull P packet, @NonNull S session) {
        return getCodec(session.getPacketProtocol().getOutboundState()).handleOutgoing(packet, session);
    }

    public <P extends Packet, S extends Session> void handlePostOutgoing(@NonNull P packet, @NonNull S session) {
        getCodec(session.getPacketProtocol().getOutboundState()).handlePostOutgoing(packet, session);
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Builder<S extends Session> {
        private final EnumMap<ProtocolState, PacketHandlerStateCodec<? extends Session>> aStateCodecs = new EnumMap<>(ProtocolState.class);
        /**
         * For inbound packets, higher priority codecs are invoked first
         * For outbound packets, lower priority codecs are invoked first
         */
        private int priority;
        private String id;
        private Predicate<S> activePredicate = session -> true;

        public Builder<S> state(ProtocolState state, PacketHandlerStateCodec<S> codec) {
            this.aStateCodecs.put(state, codec);
            return this;
        }

        public Builder<S> allStates(PacketHandlerStateCodec<S> codec) {
            for (ProtocolState state : ProtocolState.values()) {
                this.aStateCodecs.put(state, codec);
            }
            return this;
        }

        public PacketHandlerCodec build() {
            Objects.requireNonNull(this.id, "id");
            return new PacketHandlerCodec(priority, id, aStateCodecs, (Predicate<Session>) activePredicate);
        }
    }
}
