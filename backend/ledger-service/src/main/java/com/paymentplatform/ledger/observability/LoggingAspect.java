package com.paymentplatform.ledger.observability;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Traces every call into this service's own components - web controllers, services,
 * repositories, clients, schedulers and the like. For each invocation it logs method entry with
 * a compacted argument list, method exit with a summary of the return value and the elapsed
 * time in milliseconds, and any exception that propagates out. Entry and exit are logged at
 * DEBUG, exceptions at WARN, both under the target class's own logger name so per-package log
 * levels keep working. This keeps the business code itself free of repetitive log statements in
 * every method.
 *
 * <p>The {@code domain} package (entities and DTOs) and this {@code observability} package are
 * excluded, so accessors and the tracing plumbing itself do not drown out everything else.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final int MAX_VALUE_LEN = 200;

    @Pointcut("execution(public * com.paymentplatform.ledger..*(..))"
            + " && !within(com.paymentplatform.ledger.observability..*)"
            + " && !within(com.paymentplatform.ledger.domain..*)"
            + " && !execution(* com.paymentplatform.ledger..*Application.*(..))")
    void applicationComponent() {
    }

    @Around("applicationComponent()")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        Logger log = LoggerFactory.getLogger(pjp.getSignature().getDeclaringType());
        String method = pjp.getSignature().getName();

        if (log.isDebugEnabled()) {
            log.debug("enter {}({})", method, compactArgs(pjp.getArgs()));
        }

        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            if (log.isDebugEnabled()) {
                log.debug("exit  {} [{} ms] returned {}", method, elapsedMs(startNanos), summarize(result));
            }
            return result;
        } catch (Throwable ex) {
            log.warn("error {} [{} ms] threw {}: {}", method, elapsedMs(startNanos),
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String compactArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args).map(LoggingAspect::summarize).collect(Collectors.joining(", "));
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException ex) {
            return value.getClass().getSimpleName() + "(<unprintable>)";
        }
        if (text.length() > MAX_VALUE_LEN) {
            return text.substring(0, MAX_VALUE_LEN) + "...(" + text.length() + " chars)";
        }
        return text;
    }
}
