package com.zenith.feature.items;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.module.ClientBotTick;
import com.zenith.feature.world.InputRequest;
import com.zenith.util.RequestFuture;
import com.zenith.util.Timer;
import com.zenith.util.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;

import java.util.Collections;
import java.util.List;

import static com.zenith.Shared.*;

public class PlayerInventoryManager {
    private static final InventoryActionRequest DEFAULT_ACTION_REQUEST = new InventoryActionRequest(null, Collections.emptyList(), Integer.MIN_VALUE);
    private static final RequestFuture DEFAULT_REQUEST_FUTURE = new RequestFuture();
    private final Timer tickTimer = Timers.tickTimer();
    private final int actionDelayTicks = 5;
    private InventoryActionRequest currentActionRequest = DEFAULT_ACTION_REQUEST;
    private RequestFuture currentRequestFuture = DEFAULT_REQUEST_FUTURE;

    public PlayerInventoryManager() {
        EVENT_BUS.subscribe(
            this,
            // after modules, before player simulation
            EventConsumer.of(ClientBotTick.class, -5000, this::handleTick),
            EventConsumer.of(ClientBotTick.Starting.class, this::handleBotTickStarting)
        );
    }

    private void handleBotTickStarting(ClientBotTick.Starting event) {
        var openContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openContainerId == 0) return;
        Proxy.getInstance().getClient().sendAwait(new ServerboundContainerClosePacket(openContainerId));
    }

    public synchronized boolean isOwner(Object owner) {
        return owner == currentActionRequest.getOwner();
    }

    public synchronized boolean isCompleted() {
        return currentActionRequest.isCompleted();
    }

    public synchronized boolean isExecuting() {
        return currentActionRequest.isExecuting();
    }

    public synchronized RequestFuture invActionReq(final Object owner, final List<ContainerClickAction> actions, int priority) {
        if (priority <= currentActionRequest.getPriority()) return RequestFuture.rejected;
        if (isExecuting()) return RequestFuture.rejected;
        currentRequestFuture.complete(false);
        currentActionRequest = new InventoryActionRequest(owner, actions, priority);
        currentRequestFuture = new RequestFuture();
        return currentRequestFuture;
    }

    // to request that no action is taken
    public synchronized RequestFuture invActionReq(final Object owner, int priority) {
        return invActionReq(owner, Collections.emptyList(), priority);
    }

    public synchronized RequestFuture invActionReq(final Object owner, final ContainerClickAction action, int priority) {
        return invActionReq(owner, List.of(action), priority);
    }

    public synchronized void handleTick(final ClientBotTick event) {
        if (currentActionRequest == DEFAULT_ACTION_REQUEST) return;
        if (CONFIG.debug.ncpStrictInventory) {
            if (CACHE.getPlayerCache().getInventoryCache().getMouseStack() != null) {
                INPUTS.submit(InputRequest.builder()
                                  .priority(Integer.MAX_VALUE)
                                  .build());
            }
        }
        if (tickTimer.tick(actionDelayTicks)) {
            var nextAction = currentActionRequest.nextAction();
            if (nextAction != null) {
                var packet = nextAction.toPacket();
                if (packet != null) {
                    CLIENT_LOG.debug("[Inventory Manager] Executing action: {} requester: {}", nextAction.actionType(), currentActionRequest.getOwner().getClass().getSimpleName());
                    Proxy.getInstance().getClient().sendAwait(packet);
                    if (CONFIG.debug.ncpStrictInventory) {
                        if (packet instanceof ServerboundContainerClickPacket clickPacket && clickPacket.getCarriedItem() == null)
                            Proxy.getInstance().getClient().sendAwait(new ServerboundContainerClosePacket(0));
                        else
                            INPUTS.submit(InputRequest.builder()
                                              .priority(Integer.MAX_VALUE)
                                              .build());
                    }
                    ContainerClickAction nextActionPeek = currentActionRequest.peek();
                    if (packet instanceof ServerboundSetCarriedItemPacket
                        || (nextActionPeek != null && nextActionPeek.isSetCarriedItem())) {
                        // no delay needed for set carried item
                        tickTimer.skip();
                    }
                }
            }
            if (currentActionRequest.isCompleted()) {
                currentRequestFuture.complete(true);
                currentActionRequest = DEFAULT_ACTION_REQUEST;
                currentRequestFuture = DEFAULT_REQUEST_FUTURE;
            }
        }
    }

    public List<ContainerClickAction> swapSlots(int fromSlot, int toSlot) {
        return List.of(
            new ContainerClickAction(fromSlot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK),
            new ContainerClickAction(toSlot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK),
            new ContainerClickAction(fromSlot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        );
    }
}
