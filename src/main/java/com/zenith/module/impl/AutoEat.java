package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.module.AutoEatOutOfFoodEvent;
import com.zenith.event.module.ClientBotTick;
import com.zenith.feature.world.ClickTarget;
import com.zenith.feature.world.Input;
import com.zenith.feature.world.InputRequest;
import com.zenith.mc.food.FoodData;
import com.zenith.mc.food.FoodRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;

public class AutoEat extends AbstractInventoryModule {
    private int delay = 0;
    private Instant lastAutoEatOutOfFoodWarning = Instant.EPOCH;
    private boolean isEating = false;
    public static final int MOVEMENT_PRIORITY = 1000;

    public AutoEat() {
        super(HandRestriction.EITHER, 0, MOVEMENT_PRIORITY);
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleClientTick),
            of(ClientBotTick.Starting.class, this::handleBotTickStarting)
        );
    }

    public boolean isEating() {
        return enabledSetting() && isEating;
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.autoEat.enabled;
    }

    public void handleClientTick(final ClientBotTick e) {
        if (CACHE.getPlayerCache().getThePlayer().isAlive()
            && CACHE.getPlayerCache().getGameMode() != GameMode.CREATIVE
            && CACHE.getPlayerCache().getGameMode() != GameMode.SPECTATOR
            && playerHealthBelowThreshold()
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
        final boolean notHoldingFoodAndNotSwapping = getHand() == null && delay == 0;
        final boolean holdingFoodOrSwapping = getHand() != null || delay != 0;
        if (notHoldingFoodAndNotSwapping) {
            if (CONFIG.client.extra.autoEat.warning && Instant.now().minus(Duration.ofHours(7)).isAfter(lastAutoEatOutOfFoodWarning)) {
                CLIENT_LOG.warn("[AutoEat] Out of food");
                EVENT_BUS.postAsync(new AutoEatOutOfFoodEvent());
                lastAutoEatOutOfFoodWarning = Instant.now();
            }
        }
        isEating = holdingFoodOrSwapping;
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
        lastAutoEatOutOfFoodWarning = Instant.EPOCH;
        isEating = false;
    }

    private boolean playerHealthBelowThreshold() {
        return CACHE.getPlayerCache().getThePlayer().getHealth() <= CONFIG.client.extra.autoEat.healthThreshold
            || CACHE.getPlayerCache().getThePlayer().getFood() <= CONFIG.client.extra.autoEat.hungerThreshold;
    }

    @Override
    public boolean itemPredicate(final ItemStack itemStack) {
        FoodData foodData = FoodRegistry.REGISTRY.get(itemStack.getId());
        boolean canEat = CACHE.getPlayerCache().getThePlayer().getFood() < 20;
        return foodData != null && foodData.isSafeFood() && (canEat || foodData.canAlwaysEat());
    }
}
