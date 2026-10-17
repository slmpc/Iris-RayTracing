package com.github.slmpc.luminrt.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LuminRTConfig {
    public static final boolean DEFAULT_EXTENSION_ENABLED = false;
    public static final int DEFAULT_SCENE_SECTION_BUDGET = 32;
    public static final int MAX_SCENE_SECTION_BUDGET = 512;
    private static final String EXTENSION_ENABLED_KEY = "enableRayTracingExtension";
    private static final String PROFILE_SCENE_KEY = "profileScene";
    private static final String SCENE_BUDGET_KEY = "sceneSectionBudget";

    private final Path path;
    private volatile boolean extensionEnabled = DEFAULT_EXTENSION_ENABLED;
    private volatile boolean sceneProfilingEnabled;
    private volatile int sceneSectionBudget = DEFAULT_SCENE_SECTION_BUDGET;

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

    /** 每帧最多处理的 section 几何更新数；与 plan 的每帧构建预算对应。 */
    public int getSceneSectionBudget() {
        return sceneSectionBudget;
    }

    public void setSceneSectionBudget(int sections) {
        sceneSectionBudget = Math.min(MAX_SCENE_SECTION_BUDGET, Math.max(1, sections));
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
        sceneSectionBudget = parseBudget(properties.getProperty(SCENE_BUDGET_KEY));
    }

    public void save() throws IOException {
        var properties = new Properties();
        properties.setProperty(EXTENSION_ENABLED_KEY, Boolean.toString(extensionEnabled));
        properties.setProperty(PROFILE_SCENE_KEY, Boolean.toString(sceneProfilingEnabled));
        properties.setProperty(SCENE_BUDGET_KEY, Integer.toString(sceneSectionBudget));
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var output = Files.newOutputStream(path)) {
            properties.store(output, "LuminRT");
        }
    }

    private static int parseBudget(String value) {
        if (value == null) return DEFAULT_SCENE_SECTION_BUDGET;
        try {
            return Math.min(MAX_SCENE_SECTION_BUDGET, Math.max(1, Integer.parseInt(value.trim())));
        } catch (NumberFormatException invalid) {
            return DEFAULT_SCENE_SECTION_BUDGET;
        }
    }
}
