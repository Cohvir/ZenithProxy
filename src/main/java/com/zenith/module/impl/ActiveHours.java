package com.zenith.module.impl;

import com.zenith.Proxy;
import com.zenith.event.proxy.ActiveHoursConnectEvent;
import com.zenith.feature.queue.Queue;
import com.zenith.module.Module;
import org.jspecify.annotations.Nullable;

import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.zenith.Shared.*;

public class ActiveHours extends Module {
    public static final String ACTIVE_HOURS_DISCONNECT_PREFIX = "[Active Hours] ";
    private @Nullable ScheduledFuture<?> activeHoursTickFuture;
    private Instant lastActiveHoursConnect = Instant.EPOCH;

    @Override
    public boolean enabledSetting() {
        return CONFIG.client.extra.utility.actions.activeHours.enabled;
    }

    @Override
    public void onEnable() {
        // Perform an immediate check when the module is enabled
        EXECUTOR.execute(this::checkActiveHoursOnStartup);
        // Schedule regular checks every minute
        activeHoursTickFuture = EXECUTOR.scheduleAtFixedRate(this::handleActiveHoursTick, 0L, 1L, TimeUnit.MINUTES);
    }
    
    /**
     * Performs an immediate check when the module is enabled to see if it's time to enter the queue
     * based on the configured active hours and current queue status.
     */
    private void checkActiveHoursOnStartup() {
        try {
            info("Checking active hours on startup...");
            // Force a queue status update to get the latest data
            com.zenith.feature.queue.Queue.updateQueueStatusNow();
            // Then perform the regular active hours check
            handleActiveHoursTick();
        } catch (final Exception e) {
            error("Error in active hours startup check", e);
        }
    }

    @Override
    public void onDisable() {
        if (activeHoursTickFuture != null) {
            activeHoursTickFuture.cancel(false);
        }
    }

    private void handleActiveHoursTick() {
        try {
            var activeHoursConfig = CONFIG.client.extra.utility.actions.activeHours;
            var proxy = Proxy.getInstance();
            if (proxy.isOn2b2t() && (proxy.isPrio() && proxy.isConnected())) return;
            if (proxy.hasActivePlayer() && !activeHoursConfig.forceReconnect) return;
            if (lastActiveHoursConnect.isAfter(Instant.now().minus(Duration.ofHours(1)))) return;
            
            // Always get the latest queue status data before making decisions
            if (proxy.isOn2b2t()) {
                // Force a queue status update to get the latest prediction model data
                Queue.updateQueueStatusNow();
            }

            var queueLength = proxy.isOn2b2t()
                ? proxy.isPrio()
                ? Queue.getQueueStatus().prio()
                : Queue.getQueueStatus().regular()
                : 0;
                
            // Get queue wait time, using prediction model if enabled
            var queueWaitSeconds = 0L;
            if (activeHoursConfig.queueEtaCalc) {
                // Only calculate wait time if queue length is valid (greater than 0)
                if (queueLength > 0) {
                    queueWaitSeconds = Queue.getQueueWait(queueLength);
                }
                boolean predictionModelEnabled = CONFIG.server.useQueuePredictionModel;
                info("ActiveHours scheduling using {} (queue length: {}, estimated wait: {})", 
                    predictionModelEnabled ? "prediction model" : "standard calculation",
                    queueLength, 
                    Queue.getEtaStringFromSeconds(queueWaitSeconds));
            }
            var nowPlusQueueWait = LocalDateTime.now(ZoneId.of(activeHoursConfig.timeZoneId))
                .plusSeconds(queueWaitSeconds)
                .atZone(ZoneId.of(activeHoursConfig.timeZoneId))
                .toInstant();
            Map<Instant, ActiveTime> activeTimesMap = new HashMap<>();
            for (ActiveTime time : activeHoursConfig.activeTimes) {
                var activeHourToday = ZonedDateTime.of(
                    LocalDate.now(ZoneId.of(activeHoursConfig.timeZoneId)),
                    LocalTime.of(time.hour(), time.minute()),
                    ZoneId.of(activeHoursConfig.timeZoneId));
                var activeHourTomorrow = activeHourToday.plusDays(1L);
                activeTimesMap.put(activeHourToday.toInstant(), time);
                activeTimesMap.put(activeHourTomorrow.toInstant(), time);
            }
            // Improved time range calculation with more granular adjustments based on queue wait time
            // This helps account for prediction inaccuracies by providing wider connection windows
            Duration timeRange;
            if (queueWaitSeconds > 43200) { // > 12 hours
                timeRange = Duration.ofMinutes(30); // Very long queue, use 30 minute window
            } else if (queueWaitSeconds > 28800) { // > 8 hours
                timeRange = Duration.ofMinutes(20); // Long queue, use 20 minute window
            } else if (queueWaitSeconds > 14400) { // > 4 hours
                timeRange = Duration.ofMinutes(15); // Medium queue, use 15 minute window
            } else {
                timeRange = Duration.ofMinutes(10); // Short queue, use 10 minute window (increased from 5)
            }
            for (var activeTimeEntry : activeTimesMap.entrySet()) {
                Instant activeTimeInstant = activeTimeEntry.getKey();
                ActiveTime activeTime = activeTimeEntry.getValue();
                // Enhanced connection timing logic with asymmetric time range
                // Use a wider range before the target time and narrower range after
                // This accounts for the tendency of predictions to underestimate wait times
                if (nowPlusQueueWait.isAfter(activeTimeInstant.minus(timeRange.multipliedBy(2)))
                    && nowPlusQueueWait.isBefore(activeTimeInstant.plus(timeRange))) {
                    info("Connect triggered for registered time: {}", activeTime);
                    EVENT_BUS.postAsync(new ActiveHoursConnectEvent(proxy.isConnected() && proxy.isOn2b2t()));
                    this.lastActiveHoursConnect = Instant.now();
                    
                    // Remove one-time schedules when they trigger a connection
                    if (activeTime.oneTime()) {
                        info("Removing one-time schedule: {}", activeTime);
                        // Rimuovi dalla lista di configurazione
                        activeHoursConfig.activeTimes.removeIf(time -> 
                            time.hour() == activeTime.hour() && 
                            time.minute() == activeTime.minute() && 
                            time.oneTime() == activeTime.oneTime());
                        
                        // Rimuovi anche dalla mappa temporanea per evitare attivazioni multiple
                        // durante la stessa esecuzione
                        activeTimesMap.entrySet().removeIf(entry -> {
                            ActiveTime entryTime = entry.getValue();
                            return entryTime.hour() == activeTime.hour() && 
                                   entryTime.minute() == activeTime.minute() && 
                                   entryTime.oneTime() == activeTime.oneTime();
                        });
                    }
                    
                    if (proxy.isConnected()) {
                        proxy.disconnect(ACTIVE_HOURS_DISCONNECT_PREFIX + "Registered Time: " + activeTime);
                        if (proxy.isOn2b2t()) {
                            info("Waiting 1 minute to avoid reconnect queue skip");
                            MODULE.get(AutoReconnect.class).scheduleAutoReconnect(60);
                            return;
                        }
                    }
                    proxy.connectAndCatchExceptions();
                    return;
                }
            }
        } catch (final Exception e) {
            error("Error in active hours tick", e);
        }
    }

    public record ActiveTime(int hour, int minute, boolean oneTime) {
        
        public ActiveTime(int hour, int minute) {
            this(hour, minute, false);
        }

        public static ActiveTime fromString(final String arg) {
            return fromString(arg, false);
        }
        
        public static ActiveTime fromString(final String arg, boolean oneTime) {
            final String[] split = arg.split(":");
            final int hour = Integer.parseInt(split[0]);
            final int minute = Integer.parseInt(split[1]);
            return new ActiveTime(hour, minute, oneTime);
        }

        @Override
        public String toString() {
            return (hour() < 10 ? "0" + hour() : hour()) + ":" + (minute() < 10 ? "0" + minute() : minute());
        }
    }

    public static boolean isActiveHoursDisconnect(final String message) {
        return message.startsWith(ACTIVE_HOURS_DISCONNECT_PREFIX);
    }
}
