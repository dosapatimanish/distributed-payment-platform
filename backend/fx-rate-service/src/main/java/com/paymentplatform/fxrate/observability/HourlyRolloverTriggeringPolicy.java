package com.paymentplatform.fxrate.observability;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;

import ch.qos.logback.core.rolling.TriggeringPolicyBase;

/**
 * Triggers a log rollover the first time an event is logged in a new time bucket - one clock
 * hour by default. Paired with a {@code FixedWindowRollingPolicy} in logback-spring.xml this
 * gives integer-suffixed hourly files that are never overwritten: the live file is always
 * {@code <app>.log}; on the hour it is renamed to {@code <app>.log.1}, the old {@code .1} to
 * {@code .2}, and so on up to the window's max index (older ones past that are discarded).
 *
 * <p>{@code intervalSeconds} (default 3600) can be lowered from the XML to roll faster in a
 * demo. Buckets are aligned to the JVM's local-time offset so the default rolls on the local
 * clock hour, not on the UTC hour.
 */
public class HourlyRolloverTriggeringPolicy<E> extends TriggeringPolicyBase<E> {

    private long intervalSeconds = 3600L;
    private volatile long currentBucket = bucketNow();

    @Override
    public boolean isTriggeringEvent(File activeFile, E event) {
        long bucket = bucketNow();
        if (bucket == currentBucket) {
            return false;
        }
        currentBucket = bucket;
        // nothing written this bucket yet (e.g. first event right after startup) - no point
        // rolling an empty file
        return activeFile != null && activeFile.length() > 0;
    }

    private long bucketNow() {
        long epochSecond = System.currentTimeMillis() / 1000L;
        long offset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
        return (epochSecond + offset) / intervalSeconds;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        if (intervalSeconds > 0L) {
            this.intervalSeconds = intervalSeconds;
            this.currentBucket = bucketNow();
        }
    }
}
