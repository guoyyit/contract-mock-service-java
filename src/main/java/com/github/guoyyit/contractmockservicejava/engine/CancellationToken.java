package com.github.guoyyit.contractmockservicejava.engine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token with a distinct timeout signal. A scheduled
 * delay task registers a cleanup action so that either cancelling or timing
 * out the request releases the pending timer.
 *
 * <p>States: active -> cancelled (client gone) or timed out. Timeout is a
 * terminal state distinct from cancellation so the caller can answer 504
 * rather than 499.
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private volatile Runnable onCancel;

    public boolean isCancelled() {
        return cancelled.get() || timedOut.get();
    }

    public boolean isTimedOut() {
        return timedOut.get();
    }

    public void onCancel(Runnable action) {
        boolean runNow = false;
        synchronized (this) {
            if (isCancelled()) {
                runNow = true;
            } else {
                this.onCancel = action;
            }
        }
        if (runNow && action != null) {
            action.run();
        }
    }

    public boolean cancel() {
        return transition(this.cancelled, null);
    }

    /** Marks the token as timed out and runs the registered cleanup action. */
    public boolean timeout() {
        return transition(this.timedOut, new TimeoutException("request timed out"));
    }

    private boolean transition(AtomicBoolean flag, Throwable ignored) {
        Runnable action;
        synchronized (this) {
            if (isCancelled()) {
                return false;
            }
            flag.set(true);
            action = onCancel;
            onCancel = null;
        }
        if (action != null) {
            action.run();
        }
        return true;
    }

    /**
     * Binds a token to the lifecycle of a future. An ordinary completion does
     * nothing; an exceptional completion is treated as a cancellation. The
     * caller should prefer {@link #timeout()} when the failure is specifically
     * a request timeout.
     */
    public static CancellationToken forFuture(CompletableFuture<?> future) {
        CancellationToken token = new CancellationToken();
        future.whenComplete((r, ex) -> {
            if (ex != null) {
                token.cancel();
            }
        });
        return token;
    }
}
