package com.github.slmpc.irisrt.config;

import com.github.slmpc.irisrt.IrisRT;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class IrisRTSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var config = IrisRT.getConfig();
        builder.registerOwnModOptions()
                .setName(Component.translatable("options.irisrt.title").getString())
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("options.irisrt.settings"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(
                                                Identifier.fromNamespaceAndPath(IrisRT.MOD_ID, "ray_tracing_extension"))
                                        .setName(Component.translatable("options.irisrt.extension"))
                                        .setTooltip(Component.translatable("options.irisrt.extension.tooltip"))
                                        .setDefaultValue(IrisRTConfig.DEFAULT_EXTENSION_ENABLED)
                                        .setBinding(config::setExtensionEnabled, config::isExtensionEnabled)
                                        .setStorageHandler(IrisRTSodiumConfig::saveConfig)
                                        .setApplyHook(state -> reloadShaders()))));
    }

    private static void saveConfig() {
        try {
            IrisRT.getConfig().save();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save IrisRT configuration", e);
        }
    }

    private static void reloadShaders() {
        // Sodium 先保存所有配置，再执行应用回调，使重新加载的光影包使用已提交的开关状态。
        try {
            Iris.reload();
        } catch (IOException e) {
            IrisRT.LOGGER.error("Failed to reload shaders after applying IrisRT settings", e);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.translatable("iris.shaders.reloaded.failure", e.getMessage()));
            }
        }
    }
}
