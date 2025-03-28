package com.zenith.event.module;

import org.jspecify.annotations.Nullable;

import java.io.File;

public record ReplayStoppedEvent(@Nullable File replayFile) { }
