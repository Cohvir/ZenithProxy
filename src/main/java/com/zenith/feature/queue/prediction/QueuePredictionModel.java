package com.zenith.feature.queue.prediction;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.proxy.QueuePositionUpdateEvent;
import com.zenith.feature.api.vcapi.VcApi;
import com.zenith.feature.api.vcapi.model.QueueEtaEquationResponse;
import com.zenith.feature.api.vcapi.model.QueueMonthResponse;
import com.zenith.feature.queue.QueueStatus;
import lombok.Getter;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.*;

/**
 * Machine learning model for predicting queue wait times based on historical data.
 * This model analyzes patterns in queue wait times based on time of day, day of week,
 * and other temporal factors to provide more accurate predictions.
 */
public class QueuePredictionModel {
    @Getter
    private static final QueuePredictionModel INSTANCE = new QueuePredictionModel();
    
    // Store correction factors based on day of week and hour of day
    private final Map<DayOfWeek, Map<Integer, Double>> correctionFactors = new ConcurrentHashMap<>();
    
    // Store recent prediction errors for continuous learning
    private final List<PredictionRecord> recentPredictions = Collections.synchronizedList(new ArrayList<>());
    
    // Store historical queue data patterns
    private final Map<DayOfWeek, Map<Integer, QueueHistoricalPattern>> historicalPatterns = new ConcurrentHashMap<>();
    
    // Maximum number of prediction records to keep
    private static final int MAX_PREDICTION_RECORDS = 1000;
    
    // Default correction factor when no data is available
    private static final double DEFAULT_CORRECTION_FACTOR = 1.0;
    
    // Learning rate for updating correction factors
    private static final double LEARNING_RATE = 0.05;
    
    // Minimum interval between historical data API requests (in minutes)
    private static final int MIN_HISTORICAL_DATA_REFRESH_MINUTES = 5;
    
    // Last time historical data was fetched
    private volatile Instant lastHistoricalDataUpdate = Instant.EPOCH;
    
    // Store the current queue equation to avoid circular dependency
    private volatile QueueEtaEquationResponse lastQueueEquation;
    
    // Store ETA data points for analysis
    private final List<EtaDataPoint> etaDataPoints = Collections.synchronizedList(new ArrayList<>());
    
    // Maximum number of ETA data points to keep
    private static final int MAX_ETA_DATA_POINTS = 1000;
    
    // Position rate tracking - tracks how quickly queue positions change
    private final QueuePositionRateTracker positionRateTracker = new QueuePositionRateTracker();
    
    // Queue abandonment parameters - initial values that will be dynamically adjusted
    private double maxAbandonmentRate = 0.55; // Maximum rate of abandonment (for very long queues) - increased from 0.45
    private double minAbandonmentRate = 0.15; // Minimum rate of abandonment (for short queues) - increased from 0.10
    private int longQueueThreshold = 300; // Queue length considered "long" - reduced from 350
    private int shortQueueThreshold = 60; // Queue length considered "short" - reduced from 80
    private static final double POSITION_FACTOR = 0.8; // How much position affects abandonment (higher = more effect) - increased from 0.7
    
    // Tracking variables for queue length statistics
    private final List<Integer> recentRegularQueueLengths = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> recentPriorityQueueLengths = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_QUEUE_LENGTH_HISTORY = 1000; // Maximum number of queue length records to keep
    
    // Bias factor to adjust predictions based on historical accuracy
    // Changed from 1.15 to 0.9 to account for queue abandonment making actual wait times shorter
    private static final double PREDICTION_BIAS_FACTOR = 0.9; // 10% bias toward earlier predictions
    
    /**
     * Calculates the base prediction for queue wait time without using Queue.getQueueWait
     * to avoid circular dependency.
     * 
     * @param queuePos The current position in the queue
     * @return Base prediction in seconds
     */
    private long calculateBasePrediction(final Integer queuePos) {
        // Use the same formula as in Queue.java but without calling back to Queue.getQueueWait
        QueueEtaEquationResponse equation = getQueueEquation();
        return (long) (equation.factor() * (Math.pow(queuePos.doubleValue(), equation.pow())));
    }
    
    /**
     * Gets the current queue equation from Queue class.
     * This method should be called periodically to keep the equation up to date.
     */
    private QueueEtaEquationResponse getQueueEquation() {
        try {
            // Use reflection to access the queueEtaEquation field from Queue class
            java.lang.reflect.Field field = com.zenith.feature.queue.Queue.class.getDeclaredField("queueEtaEquation");
            field.setAccessible(true);
            QueueEtaEquationResponse equation = (QueueEtaEquationResponse) field.get(null);
            if (equation != null) {
                return equation;
            }
        } catch (Exception e) {
            SERVER_LOG.error("Error accessing queue equation, using default", e);
        }
        // Return default values if we can't access the field
        return new QueueEtaEquationResponse(343.0, 0.743);
    }
    
    private QueuePredictionModel() {
        // Initialize correction factors for all days and hours
        for (DayOfWeek day : DayOfWeek.values()) {
            Map<Integer, Double> hourMap = new HashMap<>();
            Map<Integer, QueueHistoricalPattern> patternMap = new HashMap<>();
            for (int hour = 0; hour < 24; hour++) {
                hourMap.put(hour, DEFAULT_CORRECTION_FACTOR);
                patternMap.put(hour, new QueueHistoricalPattern());
            }
            correctionFactors.put(day, hourMap);
            historicalPatterns.put(day, patternMap);
        }
        
        // Get the current queue equation from Queue class
        lastQueueEquation = getQueueEquation();
        
        // Schedule periodic model updates
        EXECUTOR.scheduleAtFixedRate(
            () -> Thread.ofVirtual().name("Queue Prediction Model Update").start(this::updateModel),
            1,
            6,
            TimeUnit.HOURS
        );
        
        // Schedule periodic historical data fetching (every 5 minutes as requested)
        EXECUTOR.scheduleAtFixedRate(
            () -> Thread.ofVirtual().name("Queue Historical Data Update").start(this::fetchHistoricalData),
            1,
            5,
            TimeUnit.MINUTES
        );
        
        // Schedule periodic ETA data collection (every 5 minutes)
        EXECUTOR.scheduleAtFixedRate(
            () -> Thread.ofVirtual().name("Queue ETA Data Collection").start(this::collectCurrentEtaData),
            2,
            5,
            TimeUnit.MINUTES
        );
        
        // Subscribe to queue position update events
        EVENT_BUS.subscribe(this,
            of(QueuePositionUpdateEvent.class, queueEvent -> 
                positionRateTracker.recordPositionUpdate(queueEvent.position())
            )
        );
        
        // Initial fetch of historical data
        Thread.ofVirtual().name("Initial Queue Historical Data Update").start(this::fetchHistoricalData);
    }
    
    /**
     * Predicts the queue wait time based on the current queue position and historical data.
     * Differentiates between priority and regular queue.
     * 
     * @param queuePos The current position in the queue
     * @return Predicted wait time in seconds
     */
    public long predictQueueWait(final Integer queuePos) {
        // Calculate the base prediction directly to avoid circular dependency with Queue.getQueueWait
        long basePrediction = calculateBasePrediction(queuePos);
        
        // Apply correction factor based on current time
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        int hourOfDay = now.getHour();
        
        // Determine if we're in priority queue by checking current queue status
        boolean isPriorityQueue = isPriorityQueue(queuePos);
        
        // Get correction factor based on historical patterns and feedback
        double correctionFactor = getCorrectionFactor(dayOfWeek, hourOfDay);
        
        // Apply historical pattern adjustment
        double historicalAdjustment = getHistoricalAdjustment(dayOfWeek, hourOfDay, isPriorityQueue, queuePos);
        
        // Calculate queue abandonment factor - people are more likely to leave when they're further back
        // This is a reduction factor - lower means faster queue progress due to abandonment
        double abandonmentFactor = calculateAbandonmentFactor(queuePos, isPriorityQueue);
        
        // Get position-based rate adjustment from the tracker
        double positionRateAdjustment = positionRateTracker.getPositionRateAdjustment(queuePos, isPriorityQueue);
        
        // Combine all factors (weighted average) with adjusted weights
        // Since abandonmentFactor is a reduction factor (lower means faster), we use it directly
        // to reduce the combined factor, as queue abandonment makes actual wait times shorter
        // Added position rate adjustment with significant weight to improve accuracy based on actual position change rates
        double combinedFactor = (correctionFactor * 0.2) + 
                               (historicalAdjustment * 0.2) + 
                               (abandonmentFactor * 0.3) + 
                               (positionRateAdjustment * 0.3);
        
        // Apply correction and return the predicted wait time
        long predictedWait = (long) (basePrediction * combinedFactor);
        
        // Apply bias factor to prefer slightly earlier predictions rather than later ones
        // Changed from 0.9 to 0.85 to account for more accurate position-based rate tracking
        predictedWait = (long) (predictedWait * 0.85);
        
        // Log the prediction for future learning
        logPrediction(queuePos, basePrediction, predictedWait);
        
        SERVER_LOG.debug("Queue prediction for position {}: base={}, correctionFactor={}, historicalAdjustment={}, abandonmentFactor={}, positionRate={}, final={}", 
                         queuePos, basePrediction, correctionFactor, historicalAdjustment, abandonmentFactor, positionRateAdjustment, predictedWait);
        
        return predictedWait;
    }
    
    /**
     * Determines if the given queue position is in the priority queue.
     * Uses a direct approach to avoid circular dependency with Queue class.
     */
    private boolean isPriorityQueue(int queuePos) {
        try {
            // Access the queueStatus field directly using reflection to avoid circular dependency
            java.lang.reflect.Field field = com.zenith.feature.queue.Queue.class.getDeclaredField("queueStatus");
            field.setAccessible(true);
            QueueStatus status = (QueueStatus) field.get(null);
            return queuePos <= status.prio(); // If position is within priority queue range
        } catch (Exception e) {
            SERVER_LOG.error("Error determining priority queue status, assuming regular queue", e);
            return false; // Default to regular queue if we can't determine
        }
    }
    
    /**
     * Calculates the abandonment factor based on queue position.
     * People are more likely to abandon the queue when they're further back,
     * which significantly reduces wait times for those who remain.
     * 
     * @param queuePos The current position in the queue
     * @param isPriorityQueue Whether this is a priority queue position
     * @return A factor to adjust the prediction (lower means faster due to abandonment)
     */
    private double calculateAbandonmentFactor(int queuePos, boolean isPriorityQueue) {
        // Get the total queue length for comparison
        QueueStatus status = getQueueStatus();
        int totalQueueLength = isPriorityQueue ? status.prio() : status.regular();
        
        // Track this queue length for future threshold adjustments
        if (isPriorityQueue) {
            synchronized (recentPriorityQueueLengths) {
                recentPriorityQueueLengths.add(totalQueueLength);
                if (recentPriorityQueueLengths.size() > MAX_QUEUE_LENGTH_HISTORY) {
                    recentPriorityQueueLengths.remove(0);
                }
            }
        } else {
            synchronized (recentRegularQueueLengths) {
                recentRegularQueueLengths.add(totalQueueLength);
                if (recentRegularQueueLengths.size() > MAX_QUEUE_LENGTH_HISTORY) {
                    recentRegularQueueLengths.remove(0);
                }
            }
        }
        
        // If queue length is invalid or very small, assume minimal abandonment
        if (totalQueueLength <= 10 || queuePos <= 10) {
            return 1.0; // No abandonment adjustment for very small queues or front positions
        }
        
        // Calculate relative position in queue (0.0 = front, 1.0 = back)
        double relativePosition = (double) queuePos / totalQueueLength;
        
        // Determine base abandonment rate based on queue length
        double baseAbandonmentRate;
        if (totalQueueLength >= longQueueThreshold) {
            baseAbandonmentRate = maxAbandonmentRate;
        } else if (totalQueueLength <= shortQueueThreshold) {
            baseAbandonmentRate = minAbandonmentRate;
        } else {
            // Linear interpolation between min and max rates
            double queueRatio = (double) (totalQueueLength - shortQueueThreshold) / 
                               (longQueueThreshold - shortQueueThreshold);
            baseAbandonmentRate = minAbandonmentRate + 
                                (queueRatio * (maxAbandonmentRate - minAbandonmentRate));
        }
        
        // Calculate position-specific abandonment rate
        // People at the back are more likely to abandon than those near the front
        double positionAdjustment = Math.pow(relativePosition, POSITION_FACTOR);
        double abandonmentRate = baseAbandonmentRate * positionAdjustment;
        
        // Priority queue players are less likely to abandon (they paid for priority)
        if (isPriorityQueue) {
            abandonmentRate *= 0.6; // 40% less abandonment in priority queue
        }
        
        // Calculate the abandonment factor (how much this affects the prediction)
        // This is a reduction factor - lower means faster queue progress due to abandonment
        double abandonmentFactor = 1.0 - abandonmentRate;
        
        SERVER_LOG.debug("Queue abandonment calculation for position {}/{}: relativePos={}, baseRate={}, adjustedRate={}, factor={}",
                         queuePos, totalQueueLength, relativePosition, baseAbandonmentRate, abandonmentRate, abandonmentFactor);
        
        return abandonmentFactor;
    }
    
    /**
     * Gets the correction factor for a specific day and hour.
     */
    private double getCorrectionFactor(DayOfWeek day, int hour) {
        Map<Integer, Double> hourMap = correctionFactors.get(day);
        if (hourMap != null) {
            Double factor = hourMap.get(hour);
            if (factor != null) {
                return factor;
            }
        }
        return DEFAULT_CORRECTION_FACTOR;
    }
    
    /**
     * Logs a prediction for future model updates.
     */
    private void logPrediction(int queuePos, long basePrediction, long predictedWait) {
        PredictionRecord record = new PredictionRecord(
            queuePos,
            basePrediction,
            predictedWait,
            Instant.now(),
            LocalDateTime.now().getDayOfWeek(),
            LocalDateTime.now().getHour()
        );
        
        synchronized (recentPredictions) {
            recentPredictions.add(record);
            if (recentPredictions.size() > MAX_PREDICTION_RECORDS) {
                recentPredictions.remove(0);
            }
        }
    }
    
    /**
     * Records the actual queue completion time for a prediction.
     * This is used to calculate prediction error and update the model.
     */
    public void recordActualCompletion(int initialQueuePos, Instant predictionTime, Instant completionTime) {
        synchronized (recentPredictions) {
            // Find the prediction record that matches this queue position and time
            for (PredictionRecord record : recentPredictions) {
                if (record.queuePosition == initialQueuePos && 
                    record.predictionTime.isAfter(predictionTime.minus(Duration.ofMinutes(10))) &&
                    record.predictionTime.isBefore(predictionTime.plus(Duration.ofMinutes(10))) &&
                    !record.processed) {
                    
                    // Calculate actual wait time in seconds
                    long actualWaitSeconds = Duration.between(record.predictionTime, completionTime).getSeconds();
                    
                    // Ensure actual wait time is positive to avoid division by zero
                    if (actualWaitSeconds <= 0) {
                        SERVER_LOG.warn("Invalid actual wait time: {} seconds for queue position {}", 
                                      actualWaitSeconds, initialQueuePos);
                        record.processed = true;
                        continue;
                    }
                    
                    // Calculate error ratio (predicted / actual)
                    double errorRatio = (double) record.predictedWaitTime / actualWaitSeconds;
                    
                    // Update the correction factor for this day and hour
                    updateCorrectionFactor(record.dayOfWeek, record.hourOfDay, errorRatio);
                    
                    // Mark as processed
                    record.processed = true;
                    SERVER_LOG.debug("Processed queue completion for position {}: predicted={}, actual={}, ratio={}", 
                                   initialQueuePos, record.predictedWaitTime, actualWaitSeconds, errorRatio);
                    break;
                }
            }
            
            // Clean up processed records
            recentPredictions.removeIf(record -> record.processed);
        }
    }
    
    /**
     * Updates the correction factor for a specific day and hour based on prediction error.
     */
    private void updateCorrectionFactor(DayOfWeek day, int hour, double errorRatio) {
        Map<Integer, Double> hourMap = correctionFactors.get(day);
        if (hourMap != null) {
            double currentFactor = hourMap.getOrDefault(hour, DEFAULT_CORRECTION_FACTOR);
            
            // Calculate new factor (moving average with learning rate)
            // If errorRatio > 1, we predicted too high, so we need to decrease the factor
            // If errorRatio < 1, we predicted too low, so we need to increase the factor
            double newFactor = currentFactor * (1 - LEARNING_RATE) + (1 / errorRatio) * LEARNING_RATE;
            
            // Limit correction factor to reasonable bounds (0.5 to 2.0)
            newFactor = Math.max(0.5, Math.min(2.0, newFactor));
            
            hourMap.put(hour, newFactor);
            SERVER_LOG.debug("Updated queue prediction correction factor for {} at hour {}: {} -> {}", 
                             day, hour, currentFactor, newFactor);
        }
    }
    
    /**
     * Gets an adjustment factor based on historical queue data patterns.
     */
    private double getHistoricalAdjustment(DayOfWeek day, int hour, boolean isPriorityQueue, int queuePos) {
        Map<Integer, QueueHistoricalPattern> hourMap = historicalPatterns.get(day);
        if (hourMap != null) {
            QueueHistoricalPattern pattern = hourMap.get(hour);
            if (pattern != null) {
                return pattern.getAdjustmentFactor(isPriorityQueue, queuePos);
            }
        }
        return 1.0; // Default to no adjustment
    }
    
    /**
     * Fetches historical queue data from the API and updates the model.
     * Respects the minimum interval between API requests.
     */
    private void fetchHistoricalData() {
        // Check if enough time has passed since the last update
        if (lastHistoricalDataUpdate.isAfter(Instant.now().minus(Duration.ofMinutes(MIN_HISTORICAL_DATA_REFRESH_MINUTES)))) {
            return;
        }
        
        try {
            // Fetch monthly queue data from API
            Optional<QueueMonthResponse> response = VcApi.INSTANCE.getQueueMonth();
            if (response.isPresent()) {
                processHistoricalData(response.get());
                lastHistoricalDataUpdate = Instant.now();
                SERVER_LOG.debug("Updated queue prediction model with historical data");
            }
        } catch (Exception e) {
            SERVER_LOG.error("Error fetching historical queue data", e);
        }
    }
    
    /**
     * Processes historical queue data and updates the model's patterns.
     */
    private void processHistoricalData(QueueMonthResponse monthData) {
        // Group data points by day of week and hour
        Map<DayOfWeek, Map<Integer, List<QueueMonthResponse.QueueDataPoint>>> groupedData = 
            monthData.queueData().stream()
                .collect(Collectors.groupingBy(
                    point -> point.time().atZoneSameInstant(ZoneId.systemDefault()).getDayOfWeek(),
                    Collectors.groupingBy(
                        point -> point.time().atZoneSameInstant(ZoneId.systemDefault()).getHour()
                    )
                ));
        
        // Process each day and hour
        for (Map.Entry<DayOfWeek, Map<Integer, List<QueueMonthResponse.QueueDataPoint>>> dayEntry : groupedData.entrySet()) {
            DayOfWeek day = dayEntry.getKey();
            Map<Integer, List<QueueMonthResponse.QueueDataPoint>> hourData = dayEntry.getValue();
            
            for (Map.Entry<Integer, List<QueueMonthResponse.QueueDataPoint>> hourEntry : hourData.entrySet()) {
                int hour = hourEntry.getKey();
                List<QueueMonthResponse.QueueDataPoint> dataPoints = hourEntry.getValue();
                
                // Update the historical pattern for this day and hour
                updateHistoricalPattern(day, hour, dataPoints);
            }
        }
    }
    
    /**
     * Updates the historical pattern for a specific day and hour based on data points.
     */
    private void updateHistoricalPattern(DayOfWeek day, int hour, List<QueueMonthResponse.QueueDataPoint> dataPoints) {
        if (dataPoints.isEmpty()) return;
        
        // Calculate average queue lengths and rates of change
        double avgPrioQueue = dataPoints.stream().mapToInt(QueueMonthResponse.QueueDataPoint::prio).average().orElse(0);
        double avgRegularQueue = dataPoints.stream().mapToInt(QueueMonthResponse.QueueDataPoint::regular).average().orElse(0);
        
        // Calculate rate of change by comparing consecutive data points
        double prioRateOfChange = calculateRateOfChange(dataPoints, QueueMonthResponse.QueueDataPoint::prio);
        double regularRateOfChange = calculateRateOfChange(dataPoints, QueueMonthResponse.QueueDataPoint::regular);
        
        // Update the pattern
        Map<Integer, QueueHistoricalPattern> hourMap = historicalPatterns.get(day);
        if (hourMap != null) {
            QueueHistoricalPattern pattern = hourMap.get(hour);
            if (pattern != null) {
                pattern.update(avgPrioQueue, avgRegularQueue, prioRateOfChange, regularRateOfChange);
            }
        }
    }
    
    /**
     * Calculates the average rate of change for a specific queue metric.
     */
    private double calculateRateOfChange(List<QueueMonthResponse.QueueDataPoint> dataPoints, 
                                        java.util.function.ToIntFunction<QueueMonthResponse.QueueDataPoint> extractor) {
        if (dataPoints.size() < 2) return 0.0;
        
        double sumRates = 0.0;
        int count = 0;
        
        // Sort data points by time
        List<QueueMonthResponse.QueueDataPoint> sortedPoints = new ArrayList<>(dataPoints);
        sortedPoints.sort(Comparator.comparing(QueueMonthResponse.QueueDataPoint::time));
        
        // Calculate rates between consecutive points
        for (int i = 1; i < sortedPoints.size(); i++) {
            QueueMonthResponse.QueueDataPoint current = sortedPoints.get(i);
            QueueMonthResponse.QueueDataPoint previous = sortedPoints.get(i-1);
            
            long secondsBetween = Duration.between(previous.time(), current.time()).getSeconds();
            if (secondsBetween > 0) {
                int valueDiff = extractor.applyAsInt(current) - extractor.applyAsInt(previous);
                double ratePerSecond = (double) valueDiff / secondsBetween;
                sumRates += ratePerSecond;
                count++;
            }
        }
        
        return count > 0 ? sumRates / count : 0.0;
    }
    
    /**
     * Updates the model based on recent predictions and actual outcomes.
     * This is called periodically to refine the model.
     */
    private void updateModel() {
        try {
            // Clean up old prediction records
            synchronized (recentPredictions) {
                // Remove predictions older than 24 hours
                recentPredictions.removeIf(record -> 
                    record.predictionTime.isBefore(Instant.now().minus(Duration.ofHours(24))));
            }
            
            // Clean up old ETA data points
            synchronized (etaDataPoints) {
                // Remove ETA data points older than 7 days
                etaDataPoints.removeIf(point -> 
                    point.timestamp.isBefore(Instant.now().minus(Duration.ofDays(7))));
                
                // Limit the number of data points
                if (etaDataPoints.size() > MAX_ETA_DATA_POINTS) {
                    etaDataPoints.subList(0, etaDataPoints.size() - MAX_ETA_DATA_POINTS).clear();
                }
                
                // Analyze ETA patterns based on time of day and day of week
                analyzeEtaPatterns();
            }
            
            // Update the queue equation to ensure we're using the latest values
            lastQueueEquation = getQueueEquation();
            
            // Dynamically adjust queue thresholds and abandonment rates based on historical data
            updateQueueThresholds();
            
            SERVER_LOG.debug("Queue prediction model updated with {} recent records and {} ETA data points", 
                             recentPredictions.size(), etaDataPoints.size());
        } catch (Exception e) {
            SERVER_LOG.error("Error updating queue prediction model", e);
        }
    }
    
    /**
     * Collects current queue ETA data for analysis.
     * This method is called every 5 minutes to record current queue status and ETA.
     */
    private void collectCurrentEtaData() {
        try {
            // Get current queue status
            QueueStatus status = getQueueStatus();
            if (status.prio() <= 0 && status.regular() <= 0) {
                return; // No queue data available
            }
            
            // Calculate ETAs for current queue positions
            long prioEta = status.prio() > 0 ? calculateBasePrediction(status.prio()) : 0;
            long regularEta = status.regular() > 0 ? calculateBasePrediction(status.regular()) : 0;
            
            // Record the data point
            EtaDataPoint dataPoint = new EtaDataPoint(
                Instant.now(),
                status.prio(),
                status.regular(),
                prioEta,
                regularEta,
                LocalDateTime.now().getDayOfWeek(),
                LocalDateTime.now().getHour(),
                LocalDateTime.now().getMinute()
            );
            
            synchronized (etaDataPoints) {
                etaDataPoints.add(dataPoint);
            }
            
            SERVER_LOG.debug("Collected queue ETA data point: prio={}, regular={}, prioEta={}, regularEta={}",
                             status.prio(), status.regular(), prioEta, regularEta);
        } catch (Exception e) {
            SERVER_LOG.error("Error collecting queue ETA data", e);
        }
    }
    
    /**
     * Analyzes patterns in ETA data based on time of day and day of week.
     * This helps improve prediction accuracy by identifying temporal patterns.
     * Enhanced to better detect weekly cycles and time-of-day variations.
     */
    private void analyzeEtaPatterns() {
        try {
            synchronized (etaDataPoints) {
                if (etaDataPoints.size() < 10) {
                    return; // Not enough data for meaningful analysis
                }
                
                // Log the amount of data we're analyzing
                SERVER_LOG.debug("Analyzing {} ETA data points spanning {} days", 
                    etaDataPoints.size(),
                    etaDataPoints.stream()
                        .map(p -> p.timestamp.atZone(ZoneId.systemDefault()).toLocalDate())
                        .distinct()
                        .count());
                
                // Group data by day of week and hour
                Map<DayOfWeek, Map<Integer, List<EtaDataPoint>>> groupedByDayAndHour = etaDataPoints.stream()
                    .collect(Collectors.groupingBy(
                        EtaDataPoint::dayOfWeek,
                        Collectors.groupingBy(EtaDataPoint::hourOfDay)
                    ));
                
                // Analyze each day and hour group
                for (Map.Entry<DayOfWeek, Map<Integer, List<EtaDataPoint>>> dayEntry : groupedByDayAndHour.entrySet()) {
                    DayOfWeek day = dayEntry.getKey();
                    Map<Integer, List<EtaDataPoint>> hourData = dayEntry.getValue();
                    
                    for (Map.Entry<Integer, List<EtaDataPoint>> hourEntry : hourData.entrySet()) {
                        int hour = hourEntry.getKey();
                        List<EtaDataPoint> points = hourEntry.getValue();
                        
                        if (points.size() < 3) continue; // Need at least 3 points for analysis
                        
                        // Calculate average ETAs for this day and hour
                        double avgPrioEta = points.stream()
                            .filter(p -> p.prioQueueLength > 0)
                            .mapToLong(EtaDataPoint::prioEta)
                            .average()
                            .orElse(0);
                        
                        double avgRegularEta = points.stream()
                            .filter(p -> p.regularQueueLength > 0)
                            .mapToLong(EtaDataPoint::regularEta)
                            .average()
                            .orElse(0);
                        
                        // Calculate average queue lengths
                        double avgPrioQueue = points.stream()
                            .mapToInt(EtaDataPoint::prioQueueLength)
                            .average()
                            .orElse(0);
                        
                        double avgRegularQueue = points.stream()
                            .mapToInt(EtaDataPoint::regularQueueLength)
                            .average()
                            .orElse(0);
                        
                        // Update historical pattern with this analysis
                        QueueHistoricalPattern pattern = historicalPatterns.get(day).get(hour);
                        if (pattern != null) {
                            // Calculate rate of change safely (ETA per position)
                            double prioRateOfChange = avgPrioQueue > 0 ? avgPrioEta / avgPrioQueue : 0;
                            double regularRateOfChange = avgRegularQueue > 0 ? avgRegularEta / avgRegularQueue : 0;
                            
                            pattern.update(avgPrioQueue, avgRegularQueue, prioRateOfChange, regularRateOfChange);
                        }
                    }
                }
                
                SERVER_LOG.debug("Analyzed ETA patterns across {} days and {} hours", 
                                 groupedByDayAndHour.size(),
                                 groupedByDayAndHour.values().stream()
                                     .mapToInt(Map::size)
                                     .sum());
            }
        } catch (Exception e) {
            SERVER_LOG.error("Error analyzing ETA patterns", e);
        }
    }
    
    /**
     * Dynamically adjusts queue thresholds and abandonment rates based on historical data.
     * This ensures the model adapts to changing queue patterns over time.
     */
    private void updateQueueThresholds() {
        try {
            // Calculate statistics for regular queue
            List<Integer> regularLengths;
            synchronized (recentRegularQueueLengths) {
                regularLengths = new ArrayList<>(recentRegularQueueLengths);
            }
            
            // Calculate statistics for priority queue
            List<Integer> priorityLengths;
            synchronized (recentPriorityQueueLengths) {
                priorityLengths = new ArrayList<>(recentPriorityQueueLengths);
            }
            
            // Only update if we have enough data points
            if (regularLengths.size() < 10 && priorityLengths.size() < 10) {
                return;
            }
            
            // Combine both queue types for overall statistics
            List<Integer> allQueueLengths = new ArrayList<>();
            allQueueLengths.addAll(regularLengths);
            allQueueLengths.addAll(priorityLengths);
            
            // Remove zeros and sort
            allQueueLengths.removeIf(length -> length <= 0);
            Collections.sort(allQueueLengths);
            
            if (allQueueLengths.isEmpty()) {
                return; // No valid data
            }
            
            // Calculate percentiles for thresholds
            int p90 = calculatePercentile(allQueueLengths, 90);
            int p25 = calculatePercentile(allQueueLengths, 25);
            
            // Update thresholds with some smoothing to avoid rapid changes
            int newLongThreshold = Math.max(200, p90); // Ensure minimum reasonable value
            int newShortThreshold = Math.max(50, p25); // Ensure minimum reasonable value
            
            // Apply smoothing (70% old value, 30% new value)
            longQueueThreshold = (int) (0.7 * longQueueThreshold + 0.3 * newLongThreshold);
            shortQueueThreshold = (int) (0.7 * shortQueueThreshold + 0.3 * newShortThreshold);
            
            // Ensure short threshold is always less than long threshold
            if (shortQueueThreshold >= longQueueThreshold) {
                shortQueueThreshold = (int) (longQueueThreshold * 0.5);
            }
            
            // Adjust abandonment rates based on queue density and patterns
            // Higher max abandonment for very long queues, lower min abandonment for short queues
            if (p90 > 600) {
                // Very long queues observed, increase max abandonment rate
                maxAbandonmentRate = Math.min(0.45, maxAbandonmentRate + 0.01);
            } else if (p90 < 300) {
                // Shorter queues observed, decrease max abandonment rate
                maxAbandonmentRate = Math.max(0.25, maxAbandonmentRate - 0.01);
            }
            
            if (p25 < 50) {
                // Very short queues observed, decrease min abandonment rate
                minAbandonmentRate = Math.max(0.02, minAbandonmentRate - 0.005);
            } else if (p25 > 150) {
                // Longer short queues observed, increase min abandonment rate
                minAbandonmentRate = Math.min(0.15, minAbandonmentRate + 0.005);
            }
            
            SERVER_LOG.debug("Updated queue thresholds: long={}, short={}, maxAbandon={}, minAbandon={}",
                             longQueueThreshold, shortQueueThreshold, maxAbandonmentRate, minAbandonmentRate);
            
        } catch (Exception e) {
            SERVER_LOG.error("Error updating queue thresholds", e);
        }
    }
    
    /**
     * Helper method to calculate percentile values from a sorted list.
     */
    private int calculatePercentile(List<Integer> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
    }
    
    /**
     * Gets the current queue status directly to avoid circular dependency.
     */
    private QueueStatus getQueueStatus() {
        try {
            // Access the queueStatus field directly using reflection
            java.lang.reflect.Field field = com.zenith.feature.queue.Queue.class.getDeclaredField("queueStatus");
            field.setAccessible(true);
            return (QueueStatus) field.get(null);
        } catch (Exception e) {
            SERVER_LOG.error("Error accessing queue status", e);
            return new QueueStatus(0, 0, 0);
        }
    }
    
    /**
     * Inner class to store and analyze historical queue patterns for a specific day and hour.
     */
    private static class QueueHistoricalPattern {
        // Average queue lengths
        private double avgPrioQueueLength = 0.0;
        private double avgRegularQueueLength = 0.0;
        
        // Average rates of change (positions per second)
        private double avgPrioRateOfChange = 0.0;
        private double avgRegularRateOfChange = 0.0;
        
        // Number of data points used for this pattern
        private int dataPointCount = 0;
        
        // Learning rate for updating the pattern
        private static final double PATTERN_LEARNING_RATE = 0.2;
        
        /**
         * Updates the pattern with new data points.
         */
        public void update(double prioQueueLength, double regularQueueLength, 
                          double prioRateOfChange, double regularRateOfChange) {
            if (dataPointCount == 0) {
                // First update, just set the values
                avgPrioQueueLength = prioQueueLength;
                avgRegularQueueLength = regularQueueLength;
                avgPrioRateOfChange = prioRateOfChange;
                avgRegularRateOfChange = regularRateOfChange;
            } else {
                // Update with exponential moving average
                avgPrioQueueLength = (1 - PATTERN_LEARNING_RATE) * avgPrioQueueLength + PATTERN_LEARNING_RATE * prioQueueLength;
                avgRegularQueueLength = (1 - PATTERN_LEARNING_RATE) * avgRegularQueueLength + PATTERN_LEARNING_RATE * regularQueueLength;
                avgPrioRateOfChange = (1 - PATTERN_LEARNING_RATE) * avgPrioRateOfChange + PATTERN_LEARNING_RATE * prioRateOfChange;
                avgRegularRateOfChange = (1 - PATTERN_LEARNING_RATE) * avgRegularRateOfChange + PATTERN_LEARNING_RATE * regularRateOfChange;
            }
            
            dataPointCount++;
        }
        
        /**
         * Gets an adjustment factor based on historical patterns for a specific queue position.
         * Enhanced to better account for day-of-week and time-of-day patterns.
         */
        public double getAdjustmentFactor(boolean isPriorityQueue, int queuePos) {
            if (dataPointCount == 0) {
                return 1.0; // No data yet, no adjustment
            }
            
            // Get the appropriate average queue length and rate of change based on queue type
            double avgQueueLength = isPriorityQueue ? avgPrioQueueLength : avgRegularQueueLength;
            double avgRateOfChange = isPriorityQueue ? avgPrioRateOfChange : avgRegularRateOfChange;
            
            // Calculate adjustment factor based on current position relative to average
            double positionRatio = queuePos / Math.max(1.0, avgQueueLength);
            
            // Adjust based on rate of change
            // If rate is negative (queue decreasing), predictions should be faster
            // If rate is positive (queue increasing), predictions should be slower
            double rateAdjustment = avgRateOfChange * 0.15; // Increased from 0.1 to give more weight to rate of change
            
            // Get current day and hour to apply time-specific adjustments
            LocalDateTime now = LocalDateTime.now();
            DayOfWeek currentDay = now.getDayOfWeek();
            int currentHour = now.getHour();
            
            // Apply day-of-week adjustment factors
            // Weekend days (Friday evening through Sunday) tend to have higher queue activity but also higher abandonment
            double dayAdjustment = 1.0;
            if (currentDay == DayOfWeek.FRIDAY && currentHour >= 16) {
                dayAdjustment = 0.95; // Friday evening - faster due to higher abandonment
            } else if (currentDay == DayOfWeek.SATURDAY) {
                dayAdjustment = 0.9; // Saturday all day - fastest due to highest abandonment
            } else if (currentDay == DayOfWeek.SUNDAY) {
                dayAdjustment = 0.95; // Sunday all day - faster due to higher abandonment
            } else if (currentDay == DayOfWeek.MONDAY && currentHour < 12) {
                dayAdjustment = 0.98; // Monday morning - slightly faster due to moderate abandonment
            }
            
            // Apply time-of-day adjustment factors
            // Peak hours tend to have higher queue activity but also higher abandonment rates
            double hourAdjustment = 1.0;
            if (currentHour >= 15 && currentHour <= 22) { // 3 PM to 10 PM - peak hours
                // During peak hours, more people join but also more people abandon
                hourAdjustment = 0.95 - ((currentHour - 15) * 0.005); // Gradually decreases toward evening peak due to higher abandonment
            } else if (currentHour >= 23 || currentHour <= 5) { // 11 PM to 5 AM - off-peak hours
                hourAdjustment = 0.9; // Faster queue movement during off-peak due to higher abandonment rate
            }
            
            // Combine factors
            // If position is higher than average, slightly decrease wait time due to higher abandonment
            // If position is lower than average, slightly increase wait time due to lower abandonment
            double adjustmentFactor = (1.0 - ((positionRatio - 1.0) * 0.15) - rateAdjustment) * dayAdjustment * hourAdjustment;
            
            // Limit adjustment to reasonable bounds, but with wider range to account for temporal patterns
            // Lower minimum bound to allow for more significant reductions in wait time predictions
            return Math.max(0.5, Math.min(1.2, adjustmentFactor));
        }
    }
    
    /**
     * Record class to store prediction information for model training.
     */
    private static class PredictionRecord {
        final int queuePosition;
        final long baseWaitTime;
        final long predictedWaitTime;
        final Instant predictionTime;
        final DayOfWeek dayOfWeek;
        final int hourOfDay;
        boolean processed = false;
        
        PredictionRecord(int queuePosition, long baseWaitTime, long predictedWaitTime, 
                         Instant predictionTime, DayOfWeek dayOfWeek, int hourOfDay) {
            this.queuePosition = queuePosition;
            this.baseWaitTime = baseWaitTime;
            this.predictedWaitTime = predictedWaitTime;
            this.predictionTime = predictionTime;
            this.dayOfWeek = dayOfWeek;
            this.hourOfDay = hourOfDay;
        }
    }
    
    /**
     * Class to store ETA data points collected at regular intervals.
     * These are used to analyze patterns in queue wait times based on time of day and day of week.
     */
    private static class EtaDataPoint {
        final Instant timestamp;
        final int prioQueueLength;
        final int regularQueueLength;
        final long prioEta;
        final long regularEta;
        final DayOfWeek dayOfWeek;
        final int hourOfDay;
        final int minuteOfHour;
        
        EtaDataPoint(Instant timestamp, int prioQueueLength, int regularQueueLength,
                     long prioEta, long regularEta, DayOfWeek dayOfWeek, int hourOfDay, int minuteOfHour) {
            this.timestamp = timestamp;
            this.prioQueueLength = prioQueueLength;
            this.regularQueueLength = regularQueueLength;
            this.prioEta = prioEta;
            this.regularEta = regularEta;
            this.dayOfWeek = dayOfWeek;
            this.hourOfDay = hourOfDay;
            this.minuteOfHour = minuteOfHour;
        }
        
        // Accessor methods for use in stream operations
        public DayOfWeek dayOfWeek() {
            return dayOfWeek;
        }
        
        public int hourOfDay() {
            return hourOfDay;
        }
        
        public int prioQueueLength() {
            return prioQueueLength;
        }
        
        public int regularQueueLength() {
            return regularQueueLength;
        }
        
        public long prioEta() {
            return prioEta;
        }
        
        public long regularEta() {
            return regularEta;
        }
    }
    
    /**
     * Class to track queue position change rates over time.
     * This helps provide more accurate predictions based on actual observed position movement.
     */
    private static class QueuePositionRateTracker {
        // Store position updates with timestamps
        private final List<PositionUpdate> positionUpdates = Collections.synchronizedList(new ArrayList<>());
        
        // Maximum number of position updates to keep
        private static final int MAX_POSITION_UPDATES = 1000;
        
        // Queue segments for more granular rate tracking (position ranges)
        private static final int[] QUEUE_SEGMENTS = {50, 100, 200, 300, 500, 1000};
        
        // Store average rates for each queue segment (positions per minute)
        private final Map<Integer, Double> segmentRates = new ConcurrentHashMap<>();
        
        // Store separate rates for priority queue
        private final Map<Integer, Double> prioSegmentRates = new ConcurrentHashMap<>();
        
        // Learning rate for updating segment rates
        private static final double RATE_LEARNING_RATE = 0.2;
        
        /**
         * Records a queue position update and calculates rate changes.
         * 
         * @param position The current queue position
         */
        public void recordPositionUpdate(int position) {
            Instant now = Instant.now();
            
            synchronized (positionUpdates) {
                // Add the new position update
                positionUpdates.add(new PositionUpdate(position, now));
                
                // Keep only the most recent updates
                if (positionUpdates.size() > MAX_POSITION_UPDATES) {
                    positionUpdates.remove(0);
                }
                
                // Need at least 2 updates to calculate rates
                if (positionUpdates.size() < 2) {
                    return;
                }
                
                // Sort updates by timestamp
                positionUpdates.sort(Comparator.comparing(PositionUpdate::timestamp));
                
                // Calculate rates for different segments
                calculateSegmentRates();
            }
        }
        
        /**
         * Calculates position change rates for different queue segments.
         */
        private void calculateSegmentRates() {
            // Group position updates by segments
            Map<Integer, List<PositionUpdate>> segmentUpdates = new HashMap<>();
            Map<Integer, List<PositionUpdate>> prioSegmentUpdates = new HashMap<>();
            
            // Initialize segment maps
            for (int segment : QUEUE_SEGMENTS) {
                segmentUpdates.put(segment, new ArrayList<>());
                prioSegmentUpdates.put(segment, new ArrayList<>());
            }
            
            // Assign updates to segments
            for (PositionUpdate update : positionUpdates) {
                boolean isPrio = update.position <= 100; // Rough estimate for priority queue
                
                for (int segment : QUEUE_SEGMENTS) {
                    if (update.position <= segment) {
                        if (isPrio) {
                            prioSegmentUpdates.get(segment).add(update);
                        } else {
                            segmentUpdates.get(segment).add(update);
                        }
                        break; // Only add to the first matching segment
                    }
                }
            }
            
            // Calculate rates for each segment
            for (int segment : QUEUE_SEGMENTS) {
                updateSegmentRate(segment, segmentUpdates.get(segment), segmentRates);
                updateSegmentRate(segment, prioSegmentUpdates.get(segment), prioSegmentRates);
            }
        }
        
        /**
         * Updates the rate for a specific queue segment.
         */
        private void updateSegmentRate(int segment, List<PositionUpdate> updates, Map<Integer, Double> rateMap) {
            if (updates.size() < 2) {
                return; // Need at least 2 updates to calculate rate
            }
            
            // Calculate average rate (positions per minute)
            double totalRates = 0.0;
            int rateCount = 0;
            
            for (int i = 1; i < updates.size(); i++) {
                PositionUpdate current = updates.get(i);
                PositionUpdate previous = updates.get(i-1);
                
                // Skip if positions are the same or if current is higher (unusual case)
                if (current.position >= previous.position) {
                    continue;
                }
                
                // Calculate time difference in minutes
                double minutesBetween = Duration.between(previous.timestamp, current.timestamp).toMillis() / 60000.0;
                if (minutesBetween < 0.1) {
                    continue; // Avoid division by very small numbers
                }
                
                // Calculate positions moved per minute
                double positionsPerMinute = (previous.position - current.position) / minutesBetween;
                totalRates += positionsPerMinute;
                rateCount++;
            }
            
            if (rateCount > 0) {
                double newRate = totalRates / rateCount;
                
                // Update rate with exponential moving average
                double currentRate = rateMap.getOrDefault(segment, newRate);
                double updatedRate = (currentRate * (1 - RATE_LEARNING_RATE)) + (newRate * RATE_LEARNING_RATE);
                
                rateMap.put(segment, updatedRate);
                SERVER_LOG.debug("Updated queue position rate for segment {}: {} positions/minute", segment, updatedRate);
            }
        }
        
        /**
         * Gets a position-based rate adjustment factor for queue predictions.
         * 
         * @param position The queue position to get adjustment for
         * @param isPriorityQueue Whether this is a priority queue position
         * @return An adjustment factor for the prediction (lower means faster queue movement)
         */
        public double getPositionRateAdjustment(int position, boolean isPriorityQueue) {
            // Find the appropriate segment for this position
            int segment = QUEUE_SEGMENTS[QUEUE_SEGMENTS.length - 1]; // Default to largest segment
            for (int s : QUEUE_SEGMENTS) {
                if (position <= s) {
                    segment = s;
                    break;
                }
            }
            
            // Get the rate for this segment
            Map<Integer, Double> rateMap = isPriorityQueue ? prioSegmentRates : segmentRates;
            double rate = rateMap.getOrDefault(segment, 1.0); // Default to 1 position per minute
            
            // Calculate standard rate based on queue equation
            // This is a rough estimate of expected positions per minute based on the standard calculation
            double standardRate = calculateStandardRate(position);
            
            // If we have no data yet, return neutral adjustment
            if (rate <= 0) {
                return 1.0;
            }
            
            // Calculate ratio of actual rate to standard rate
            // If actual rate is faster than standard, we should predict shorter wait times
            double rateRatio = rate / Math.max(0.1, standardRate);
            
            // Convert rate ratio to adjustment factor
            // Higher actual rate means lower adjustment factor (faster queue movement)
            double adjustment = 1.0 / Math.max(0.5, Math.min(2.0, rateRatio));
            
            SERVER_LOG.debug("Position rate adjustment for pos {}: actual={}, standard={}, adjustment={}",
                             position, rate, standardRate, adjustment);
            
            return adjustment;
        }
        
        /**
         * Calculates the standard expected rate of position change based on the queue equation.
         */
        private double calculateStandardRate(int position) {
            // This is a simplified calculation that assumes positions change at a rate
            // proportional to their ETA difference
            if (position <= 1) {
                return 1.0; // Default for very small positions
            }
            
            try {
                // Get queue equation
                QueueEtaEquationResponse equation = null;
                try {
                    java.lang.reflect.Field field = com.zenith.feature.queue.Queue.class.getDeclaredField("queueEtaEquation");
                    field.setAccessible(true);
                    equation = (QueueEtaEquationResponse) field.get(null);
                } catch (Exception e) {
                    // Use default values if we can't access the field
                    equation = new QueueEtaEquationResponse(343.0, 0.743);
                }
                
                // Calculate ETAs for current position and position-1
                double etaCurrent = equation.factor() * Math.pow(position, equation.pow());
                double etaNext = equation.factor() * Math.pow(position - 1, equation.pow());
                
                // Calculate seconds per position
                double secondsPerPosition = etaCurrent - etaNext;
                
                // Convert to positions per minute
                return 60.0 / Math.max(1.0, secondsPerPosition);
            } catch (Exception e) {
                SERVER_LOG.error("Error calculating standard rate", e);
                return 1.0; // Default fallback
            }
        }
        
        /**
         * Record class to store position updates with timestamps.
         */
        private static class PositionUpdate {
            final int position;
            final Instant timestamp;
            
            PositionUpdate(int position, Instant timestamp) {
                this.position = position;
                this.timestamp = timestamp;
            }
            
            public Instant timestamp() {
                return timestamp;
            }
        }
    }
}