package com.github.slmpc.luminrt.smoke;

import com.github.slmpc.luminrt.LuminRT;
import com.github.slmpc.luminrt.config.LuminRTConfig;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.minecraft.resources.Identifier;

import java.io.IOException;

final class RayTracingSettingsSmokeTest {
    private static final String SOURCE = """
            #version 450
            #ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
            const int extensionState = 1;
            #else
            const int extensionState = 0;
            #endif
            void main() {}
            """;

    static void run() throws IOException {
        var option = ConfigManager.CONFIG.getOption(Identifier.fromNamespaceAndPath(LuminRT.MOD_ID, "ray_tracing_extension"));
        require(option instanceof BooleanOption, "Sodium must register the LuminRT extension option");
        var toggle = (BooleanOption) option;
        boolean original = LuminRT.getConfig().isExtensionEnabled();
        try {
            verifyState(toggle, !original);
            verifyState(toggle, original);
            LuminRT.LOGGER.info("LUMINRT_SETTINGS_SMOKE_PASSED");
        } finally {
            toggle.modifyValue(original);
            ConfigManager.CONFIG.applyAllOptions();
        }
    }

    private static void verifyState(BooleanOption toggle, boolean enabled) throws IOException {
        toggle.modifyValue(enabled);
        ConfigManager.CONFIG.applyAllOptions();
        require(LuminRT.getConfig().isExtensionEnabled() == enabled, "Sodium must apply the extension state");
        var saved = new LuminRTConfig(FabricLoader.getInstance().getConfigDir().resolve("luminrt.properties"));
        saved.load();
        require(saved.isExtensionEnabled() == enabled, "Sodium must persist the extension state");

        var defines = StandardMacros.createStandardEnvironmentDefines();
        long count = defines.stream().filter(define -> define.key().equals("ENABLED_LUMIN_RAY_TRACING_EXTENSION")).count();
        require(count == (enabled ? 1 : 0), "The runtime Mixin must follow the extension state");
        String preprocessed = JcppProcessor.glslPreprocessSource(SOURCE, defines);
        require(preprocessed.contains("extensionState = " + (enabled ? 1 : 0)), "GLSL must select the correct extension branch");
        require(!preprocessed.contains("extensionState = " + (enabled ? 0 : 1)), "GLSL must remove the inactive extension branch");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
