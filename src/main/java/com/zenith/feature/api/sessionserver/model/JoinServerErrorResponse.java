package com.zenith.feature.api.sessionserver.model;

import org.jspecify.annotations.Nullable;

public record JoinServerErrorResponse(@Nullable String error, @Nullable String errorMessage, @Nullable String error_description, @Nullable String cause) { }
