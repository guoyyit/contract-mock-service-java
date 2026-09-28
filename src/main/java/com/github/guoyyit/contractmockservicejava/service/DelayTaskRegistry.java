package com.github.guoyyit.contractmockservicejava.service;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * Tracks scheduled scenario-delay tasks for live requests.
 *
 * <p>Delays never occupy a request thread; they are scheduled on the shared scheduler.
 * When the client cancels (or times out) while waiting, {@link #cancel} cancels the
 * scheduled future so the scheduler thread is released and the delivery callback never
 * runs. The scenario state was already consumed before scheduling, so it is neither
 * repeated on a retry of the same call nor lost from the sequence. Concurrent
 * cancel/completion races collapse through one atomic {@code remove} call.
 */
@Service
public class DelayTaskRegistry {

    private final ThreadPoolTaskScheduler scheduler;
    private final Map<ScheduledFuture<?>, Consumer<ScheduledFuture<?>>> pending =
            new ConcurrentHashMap<>();

    public DelayTaskRegistry(ThreadPoolTaskScheduler mockScheduler) {
        this.scheduler = mockScheduler;
    }

    public ScheduledFuture<?> schedule(long delayMs, Runnable task) {
        return scheduler.getScheduledExecutor().schedule(task,
                Math.max(0L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Remember a scheduled task together with its cancellation hook. */
    public void register(ScheduledFuture<?> future, Consumer<ScheduledFuture<?>> onCancel) {
        pending.put(future, onCancel);
    }

    /** The task fired normally; its cancellation hook is discarded without invoking it. */
    public void complete(ScheduledFuture<?> future) {
        pending.remove(future);
    }

    /** Cancel the delayed delivery once and exactly once; releases the scheduled slot. */
    public void cancel(ScheduledFuture<?> future) {
        Consumer<ScheduledFuture<?>> onCancel = pending.remove(future);
        if (onCancel != null) {
            future.cancel(false);
            onCancel.accept(future);
        }
    }
}
