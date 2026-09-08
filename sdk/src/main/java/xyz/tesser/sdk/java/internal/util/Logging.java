package xyz.tesser.sdk.java.internal.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SDK-internal SLF4J logger factory. Single source for logger acquisition. */
public final class Logging {

    private Logging() {}

    public static Logger logger(String name) {
        return LoggerFactory.getLogger(name);
    }
}
