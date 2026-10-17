package com.github.slmpc.luminrt.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LuminRTConfig {
    public static final boolean DEFAULT_EXTENSION_ENABLED = false;
    private static final String EXTENSION_ENABLED_KEY = "enableRayTracingExtension";
    private static final String PROFILE_SCENE_KEY = "profileScene";

    private final Path path;
    private volatile boolean extensionEnabled = DEFAULT_EXTENSION_ENABLED;
    private volatile boolean sceneProfilingEnabled;

    public LuminRTConfig(Path path) {
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

    public boolean isSceneProfilingEnabled() {
        return sceneProfilingEnabled;
    }

    public void setSceneProfilingEnabled(boolean enabled) {
        sceneProfilingEnabled = enabled;
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
        sceneProfilingEnabled = Boolean.parseBoolean(properties.getProperty(
                PROFILE_SCENE_KEY, Boolean.toString(false)));
    }

    public void save() throws IOException {
        var properties = new Properties();
        properties.setProperty(EXTENSION_ENABLED_KEY, Boolean.toString(extensionEnabled));
        properties.setProperty(PROFILE_SCENE_KEY, Boolean.toString(sceneProfilingEnabled));
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var output = Files.newOutputStream(path)) {
            properties.store(output, "LuminRT");
        }
    }
}
