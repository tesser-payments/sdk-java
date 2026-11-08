package xyz.tesser.sdk.java.internal.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class SdkVersion {

    private static final String SDK_VERSION_RESOURCE = "sdk-version.properties";

    public static final String VALUE = loadSdkVersion();

    private SdkVersion() {}

    private static String loadSdkVersion() {
        Properties properties = readSdkVersionProperties();
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "SDK version resource " + SDK_VERSION_RESOURCE + " has no version");
        }
        return version;
    }

    private static Properties readSdkVersionProperties() {
        try (InputStream stream = SdkVersion.class.getResourceAsStream(SDK_VERSION_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing SDK version resource " + SDK_VERSION_RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read SDK version resource " + SDK_VERSION_RESOURCE, e);
        }
    }
}
