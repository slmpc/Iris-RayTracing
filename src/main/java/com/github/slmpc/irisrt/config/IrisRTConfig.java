package com.github.slmpc.irisrt.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class IrisRTConfig {
    public static final boolean DEFAULT_EXTENSION_ENABLED = false;
    private static final String EXTENSION_ENABLED_KEY = "enableRayTracingExtension";

    private final Path path;
    private volatile boolean extensionEnabled = DEFAULT_EXTENSION_ENABLED;

    public IrisRTConfig(Path path) {
        this.path = path;
    }

    public void initialize() throws IOException {
        load();
        if (!Files.exists(path)) save();
    }

    public boolean isExtensionEnabled() {
        return extensionEnabled;
    }

    public void setExtensionEnabled(boolean enabled) {
        extensionEnabled = enabled;
    }

    public void load() throws IOException {
        var properties = new Properties();
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        extensionEnabled = Boolean.parseBoolean(properties.getProperty(
                EXTENSION_ENABLED_KEY, Boolean.toString(DEFAULT_EXTENSION_ENABLED)));
    }

    public void save() throws IOException {
        var properties = new Properties();
        properties.setProperty(EXTENSION_ENABLED_KEY, Boolean.toString(extensionEnabled));
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var output = Files.newOutputStream(path)) {
            properties.store(output, "IrisRT");
        }
    }
}
