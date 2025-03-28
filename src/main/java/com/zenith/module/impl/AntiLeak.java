package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.module.OutboundChatEvent;
import com.zenith.module.Module;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.List;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.CACHE;
import static com.zenith.Shared.CONFIG;

public class AntiLeak extends Module {
    private final Pattern notNumber = Pattern.compile("[^0-9]");

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(OutboundChatEvent.class, this::handleOutgoingChat)
        );
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.antiLeak.enabled;
    }

    public void handleOutgoingChat(final OutboundChatEvent event) {
        final String message = event.getPacket().getMessage();
        final String numbersStr = notNumber.matcher(message).replaceAll(" ");
        final String[] split = numbersStr.split(" ");
        if (split.length == 0) return;
        final DoubleList numbers = new DoubleArrayList();
        for (int i = 0; i < split.length; i++) {
            final String word = split[i];
            try {
                numbers.add(Double.parseDouble(word));
            } catch (final Exception e) {
                // fall through
            }
        }
        // if there's less than 2 numbers, nothing to cancel
        if(numbers.size() < 2) return;
        if (CONFIG.client.extra.antiLeak.rangeCheck) {
            var playerX = Math.abs(CACHE.getPlayerCache().getX());
            var playerZ = Math.abs(CACHE.getPlayerCache().getZ());
            var rangeFactor = CONFIG.client.extra.antiLeak.rangeFactor;
            for (int i = 0; i < numbers.size(); i++) {
                final double number = numbers.getDouble(i);
                var n = Math.abs(number);
                // essentially we're checking if any number is within a window of the player's position
                // e.g. (500, 800) with a rangeFactor of 10 will cancel if a number is 50-5000 or 80-8000
                if ((n > playerX / rangeFactor && n < playerX * rangeFactor)
                    || (n > playerZ / rangeFactor && n < playerZ * rangeFactor)) {
                    event.cancel();
                    break;
                }
            }
        } else event.cancel();
        if (event.isCancelled()) {
            info("Cancelled chat message: " + message);
            inGameAlertActivePlayer("<red>Cancelled Chat. To disable this module, use the command: <italic>!antiLeak off");
        }
    }
}
