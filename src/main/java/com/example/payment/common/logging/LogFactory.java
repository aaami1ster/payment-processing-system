package com.example.payment.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point for obtaining SLF4J loggers.
 * Use this instead of calling {@link LoggerFactory} directly or using {@code System.out}.
 */
public final class LogFactory {

    private LogFactory() {}

    public static Logger getLogger(Class<?> type) {
        return LoggerFactory.getLogger(type);
    }
}
