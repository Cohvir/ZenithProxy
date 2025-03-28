package com.zenith.feature.api.vcapi.model;

import java.time.OffsetDateTime;
import java.util.List;

public record QueueMonthResponse(List<QueueDataPoint> queueData) {
    public record QueueDataPoint(OffsetDateTime time, int prio, int regular) {}
}