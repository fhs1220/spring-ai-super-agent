package com.fhs.aiagent.rag;

import java.util.Locale;
import java.util.Objects;

/**
 * 标识用户主动取消或承载线程被中断，避免被普通降级逻辑吞掉。
 */
public class AgentRunCancelledException extends RuntimeException {

    public AgentRunCancelledException(String message) {
        super(message);
    }

    public AgentRunCancelledException(String message, Throwable cause) {
        super(message, cause);
    }

    public static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AgentRunCancelledException("Agent run was cancelled");
        }
    }

    public static boolean isCancellation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AgentRunCancelledException
                    || current instanceof InterruptedException) {
                return true;
            }
            String message = Objects.toString(current.getMessage(), "")
                    .toLowerCase(Locale.ROOT);
            if (message.contains("interrupted") || message.contains("cancelled")
                    || message.contains("canceled")) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
