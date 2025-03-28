package com.zenith.feature.world;

import com.zenith.event.module.ClientBotTick;
import com.zenith.module.impl.PlayerSimulation;
import org.jspecify.annotations.NonNull;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Shared.EVENT_BUS;
import static com.zenith.Shared.MODULE;

public class InputManager {
    private static final InputRequest DEFAULT_MOVEMENT_INPUT_REQUEST = InputRequest.builder()
        .priority(Integer.MIN_VALUE)
        .build();
    private static final InputRequestFuture DEFAULT_REQUEST_FUTURE = new InputRequestFuture();
    private @NonNull InputRequest currentMovementInputRequest = DEFAULT_MOVEMENT_INPUT_REQUEST;
    private @NonNull InputRequestFuture currentMovementInputRequestFuture = DEFAULT_REQUEST_FUTURE;

    public InputManager() {
        EVENT_BUS.subscribe(
            this,
            // should be next to last in the tick handlers
            // right before player simulation
            // but after all modules that send movement inputs
            of(ClientBotTick.class, -10000, this::handleTick)
        );
    }

    /**
     * Interface to request movement on the next tick
     */

    public synchronized InputRequestFuture submit(final InputRequest movementInputRequest) {
        if (movementInputRequest.priority() < currentMovementInputRequest.priority()) return InputRequestFuture.rejected;
        currentMovementInputRequestFuture.complete(false);
        currentMovementInputRequest = movementInputRequest;
        currentMovementInputRequestFuture = new InputRequestFuture();
        return currentMovementInputRequestFuture;
    }

    private synchronized void handleTick(final ClientBotTick event) {
        if (currentMovementInputRequest == DEFAULT_MOVEMENT_INPUT_REQUEST) return;
        MODULE.get(PlayerSimulation.class).requestMovement(currentMovementInputRequest, currentMovementInputRequestFuture);
        currentMovementInputRequest = DEFAULT_MOVEMENT_INPUT_REQUEST;
        currentMovementInputRequestFuture.complete(true);
        currentMovementInputRequestFuture = DEFAULT_REQUEST_FUTURE;
    }
}
