package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.Command;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.command.brigadier.CommandContext;
import com.zenith.feature.queue.Queue;
import com.zenith.feature.queue.QueueStatus;
import com.zenith.util.math.MathHelper;

import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.zenith.Shared.CONFIG;

import java.time.Duration;

public class QueueStatusCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("queueStatus")
            .category(CommandCategory.INFO)
            .description("Gets the current 2b2t queue length and wait ETA")
            .usageLines(
                "",
                "refresh",
                "predictionModel on/off"
            )
            .aliases(
                "queue",
                "q"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("queueStatus").executes(c -> {
            final boolean inQueue = Proxy.getInstance().isInQueue();
            final QueueStatus queueStatus = Queue.getQueueStatus();
            boolean predictionModelEnabled = CONFIG.server.useQueuePredictionModel;
            
            // Get queue position and calculate ETAs
            final int queuePosition = inQueue ? Proxy.getInstance().getQueuePosition() : queueStatus.regular();
            final String queueEta = Queue.getQueueEta(queuePosition);
            
            // Get prediction model details if enabled
            String predictionDetails = "";
            if (predictionModelEnabled) {
                try {
                    // Get the prediction model instance using reflection
                    Class<?> modelClass = Class.forName("com.zenith.feature.queue.prediction.QueuePredictionModel");
                    Object modelInstance = modelClass.getMethod("getINSTANCE").invoke(null);
                    
                    // Get base prediction using reflection
                    java.lang.reflect.Method calculateBaseMethod = modelClass.getDeclaredMethod("calculateBasePrediction", Integer.class);
                    calculateBaseMethod.setAccessible(true);
                    long basePrediction = (long) calculateBaseMethod.invoke(modelInstance, queuePosition);
                    
                    // Get correction factor using reflection
                    java.time.DayOfWeek dayOfWeek = java.time.LocalDateTime.now().getDayOfWeek();
                    int hourOfDay = java.time.LocalDateTime.now().getHour();
                    java.lang.reflect.Method getCorrectionFactorMethod = modelClass.getDeclaredMethod("getCorrectionFactor", java.time.DayOfWeek.class, int.class);
                    getCorrectionFactorMethod.setAccessible(true);
                    double correctionFactor = (double) getCorrectionFactorMethod.invoke(modelInstance, dayOfWeek, hourOfDay);
                    
                    // Get historical adjustment using reflection
                    boolean isPriorityQueue = inQueue ? Proxy.getInstance().isPrio() : false;
                    java.lang.reflect.Method getHistoricalAdjustmentMethod = modelClass.getDeclaredMethod("getHistoricalAdjustment", java.time.DayOfWeek.class, int.class, boolean.class, int.class);
                    getHistoricalAdjustmentMethod.setAccessible(true);
                    double historicalAdjustment = (double) getHistoricalAdjustmentMethod.invoke(modelInstance, dayOfWeek, hourOfDay, isPriorityQueue, queuePosition);
                    
                    // Calculate combined factor and final prediction
                    double combinedFactor = (correctionFactor * 0.6) + (historicalAdjustment * 0.4);
                    long predictedWait = (long) (basePrediction * combinedFactor);
                    
                    // Format the prediction details
                    predictionDetails = String.format("**Prediction Model Details:**\n" +
                            "Base prediction: %s\n" +
                            "Day/Hour correction: %.2f\n" +
                            "Historical adjustment: %.2f\n" +
                            "Combined factor: %.2f\n" +
                            "Standard calculation would predict: %s",
                            Queue.getEtaStringFromSeconds(basePrediction),
                            correctionFactor,
                            historicalAdjustment,
                            combinedFactor,
                            Queue.getEtaStringFromSeconds(basePrediction));
                } catch (Exception e) {
                    predictionDetails = "**Prediction Model Error:** " + e.getMessage();
                }
            }
            
            // Build the embed
            c.getSource().getEmbed()
                .title("2b2t Queue Status" + (predictionModelEnabled ? " (Prediction Model)" : ""))
                .addField("Regular", queueStatus.regular() + (inQueue ? "" : " [ETA: " + Queue.getQueueEta(queueStatus.regular()) + 
                    " (" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.LocalDateTime.now().plusSeconds(Queue.getQueueWait(queueStatus.regular()))) + ")]"), false)
                .addField("Priority", queueStatus.prio(), false);
                
            if (predictionModelEnabled) {
                c.getSource().getEmbed().description(predictionDetails);
            }
            
            c.getSource().getEmbed().primaryColor();
            
            if (inQueue) {
                final Duration currentWaitDuration = Duration.ofSeconds(Proxy.getInstance().getOnlineTimeSeconds());
                c.getSource().getEmbed()
                    .addField("Position", queuePosition + " [ETA: " + Queue.getQueueEta(queuePosition) + 
                        " (" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.LocalDateTime.now().plusSeconds(Queue.getQueueWait(queuePosition))) + ")]", false)
                    .addField("Current Wait Duration", MathHelper.formatDuration(currentWaitDuration), false);
            }})
            .then(literal("refresh").executes(c -> {
                try {
                    Queue.updateQueueStatusNow();
                    Queue.updateQueueEtaEquation();
                } catch (final Throwable e) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Failed to refresh queue status\n" + e.getMessage())
                        .errorColor();
                    return;
                }
                c.getSource().getEmbed()
                    .title("Success")
                    .description("Queue status refreshed")
                    .successColor();
            }))
            .then(literal("predictionModel").then(argument("toggle", toggle()).executes(c -> {
                try {
                    boolean enabled = getToggle(c, "toggle");
                    CONFIG.server.useQueuePredictionModel = enabled;
                    // Update the static field in Queue class using reflection
                    java.lang.reflect.Field field = Queue.class.getDeclaredField("usePredictionModel");
                    field.setAccessible(true);
                    field.set(null, enabled);
                    
                    c.getSource().getEmbed()
                        .title("Queue Prediction Model " + toggleStrCaps(enabled))
                        .description("Queue wait time calculations will now use " + 
                                    (enabled ? "the machine learning prediction model" : "standard calculations"))
                        .successColor();
                } catch (final Throwable e) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("Failed to update queue prediction model setting\n" + e.getMessage())
                        .errorColor();
                }
                return OK;
            })));
    }
}
