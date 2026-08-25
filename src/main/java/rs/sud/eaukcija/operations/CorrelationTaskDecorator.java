package rs.sud.eaukcija.operations;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/** Propagates only the bounded correlation ID into the managed worker. */
public final class CorrelationTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return () -> {
            String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId == null) {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            } else {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.remove(CorrelationIdFilter.MDC_KEY);
                } else {
                    MDC.put(CorrelationIdFilter.MDC_KEY, previous);
                }
            }
        };
    }
}
