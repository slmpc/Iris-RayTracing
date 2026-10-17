package com.github.slmpc.luminrt.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LuminRTConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsDisabledDefaultAndPersistsBothStates() throws IOException {
        var path = directory.resolve("config/luminrt.properties");
        var config = new LuminRTConfig(path);
        config.initialize();
        assertFalse(config.isExtensionEnabled());
        assertTrue(Files.exists(path));

        config.setExtensionEnabled(true);
        config.save();
        var reloaded = new LuminRTConfig(path);
        reloaded.initialize();
        assertTrue(reloaded.isExtensionEnabled());

        reloaded.setExtensionEnabled(false);
        reloaded.save();
        config.load();
        assertFalse(config.isExtensionEnabled());
    }

    @Test
    void missingOrInvalidValueKeepsExtensionDisabled() throws IOException {
        var path = directory.resolve("luminrt.properties");
        var config = new LuminRTConfig(path);
        config.setExtensionEnabled(true);
        Files.writeString(path, "otherSetting=true\n");
        config.load();
        assertFalse(config.isExtensionEnabled());

        Files.writeString(path, "enableRayTracingExtension=invalid\n");
        config.load();
        assertFalse(config.isExtensionEnabled());
    }

    @Test
    void clampsSceneSectionBudgetAndPersistsIt() throws IOException {
        var path = directory.resolve("budget/luminrt.properties");
        var config = new LuminRTConfig(path);
        config.initialize();
        assertEquals(LuminRTConfig.DEFAULT_SCENE_SECTION_BUDGET, config.getSceneSectionBudget());

        config.setSceneSectionBudget(0);
        assertEquals(1, config.getSceneSectionBudget());
        config.setSceneSectionBudget(Integer.MAX_VALUE);
        assertEquals(LuminRTConfig.MAX_SCENE_SECTION_BUDGET, config.getSceneSectionBudget());

        config.setSceneSectionBudget(64);
        config.save();
        var reloaded = new LuminRTConfig(path);
        reloaded.initialize();
        assertEquals(64, reloaded.getSceneSectionBudget());

        // 非法值回退默认预算，不影响其他字段。
        Files.writeString(path, "sceneSectionBudget=invalid\n");
        reloaded.load();
        assertEquals(LuminRTConfig.DEFAULT_SCENE_SECTION_BUDGET, reloaded.getSceneSectionBudget());
    }
}
