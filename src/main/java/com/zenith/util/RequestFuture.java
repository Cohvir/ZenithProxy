package com.zenith.util;

import com.zenith.Proxy;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class RequestFuture implements Future<Boolean> {
    // whether the future has completed
    @Getter @Setter
    private volatile boolean completed = false;
    // whether this request was accepted
    @Getter @Setter
    private volatile boolean accepted = false;

    public static final RequestFuture rejected = immediateFuture(false);

    public static RequestFuture immediateFuture(final boolean accepted) {
        final RequestFuture future = new RequestFuture();
        future.complete(accepted);
        return future;
    }

    public synchronized void complete(final boolean accepted) {
        this.accepted = accepted;
        this.completed = true;
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException("RequestFuture cannot be cancelled");
//        if (completed) return false;
//        valid = false;
//        return true;
    }

    @Override
    public boolean isCancelled() {
        return false;
//        return valid;
    }

    @Override
    public boolean isDone() {
        return completed;
    }

    @SneakyThrows
    @Override
    public Boolean get() {
        var client = Proxy.getInstance().getClient();
        if (client != null && client.getClientEventLoop().inEventLoop()) {
            throw new IllegalStateException("Cannot block on RequestFuture in client event loop");
        }
        Wait.waitUntil(() -> completed, 1, 1L, TimeUnit.SECONDS);
        return accepted;
    }

    @SneakyThrows
    @Override
    public Boolean get(final long timeout, @NonNull final TimeUnit unit) {
        var client = Proxy.getInstance().getClient();
        if (client != null && client.getClientEventLoop().inEventLoop()) {
            throw new IllegalStateException("Cannot block on RequestFuture in client event loop");
        }
        Wait.waitUntil(() -> completed, 1, timeout, unit);
        return accepted;
    }

    public boolean getNow() {
        if (!completed) return false;
        return accepted;
    }
}
