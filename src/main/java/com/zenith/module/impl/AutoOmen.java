package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.module.ClientBotTick;
import com.zenith.feature.world.ClickTarget;
import com.zenith.feature.world.Input;
import com.zenith.feature.world.InputRequest;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;

public class AutoOmen extends AbstractInventoryModule {
    private int delay = 0;
    private boolean isEating = false;
    public static final int MOVEMENT_PRIORITY = 600;

    public AutoOmen() {
        super(HandRestriction.EITHER, 0, MOVEMENT_PRIORITY);
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.autoOmen.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleClientTick),
            of(ClientBotTick.Starting.class, this::handleBotTickStarting)
        );
    }

    public void handleClientTick(final ClientBotTick e) {
        if (CACHE.getPlayerCache().getThePlayer().isAlive()
            && !CACHE.getPlayerCache().getThePlayer().getPotionEffectMap().containsKey(Effect.BAD_OMEN)
            && CACHE.getPlayerCache().getGameMode() != GameMode.CREATIVE
            && CACHE.getPlayerCache().getGameMode() != GameMode.SPECTATOR
            && Proxy.getInstance().getOnlineTimeSeconds() > 1) {
            if (delay > 0) {
                delay--;
                if (isEating) {
                    INPUTS.submit(InputRequest.builder()
                                      .priority(MOVEMENT_PRIORITY)
                                      .build());
                    INVENTORY.invActionReq(this, MOVEMENT_PRIORITY);
                }
                return;
            }
            if (switchToFood()) {
                startEating();
            }
        } else {
            isEating = false;
        }
    }

    public boolean switchToFood() {
        delay = doInventoryActions();
        final boolean shouldStartEating = getHand() != null && delay == 0;
        isEating = getHand() != null || delay != 0;
        return shouldStartEating;
    }

    public void startEating() {
        var hand = getHand();
        if (hand == null) return;
        INPUTS.submit(InputRequest.builder()
                          .input(Input.builder()
                                     .rightClick(true)
                                     .clickTarget(ClickTarget.None.INSTANCE)
                                     .build())
                          .priority(MOVEMENT_PRIORITY)
                          .build())
            .addInputExecutedListener(future -> {
                isEating = true;
                delay = 50;
            });
    }

    public void handleBotTickStarting(final ClientBotTick.Starting event) {
        delay = 0;
        isEating = false;
    }

    @Override
    public boolean itemPredicate(final ItemStack itemStack) {
        ItemData itemData = ItemRegistry.REGISTRY.get(itemStack.getId());
        return itemData != null && itemData == ItemRegistry.OMINOUS_BOTTLE;
    }
}
