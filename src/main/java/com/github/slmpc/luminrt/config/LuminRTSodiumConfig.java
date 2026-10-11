package com.github.slmpc.luminrt.config;

import com.github.slmpc.luminrt.LuminRT;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class LuminRTSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var config = LuminRT.getConfig();
        builder.registerOwnModOptions()
                .setName(Component.translatable("options.luminrt.title").getString())
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("options.luminrt.settings"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(
                                                Identifier.fromNamespaceAndPath(LuminRT.MOD_ID, "ray_tracing_extension"))
                                        .setName(Component.translatable("options.luminrt.extension"))
                                        .setTooltip(Component.translatable("options.luminrt.extension.tooltip"))
                                        .setDefaultValue(LuminRTConfig.DEFAULT_EXTENSION_ENABLED)
                                        .setBinding(config::setExtensionEnabled, config::isExtensionEnabled)
                                        .setStorageHandler(LuminRTSodiumConfig::saveConfig)
                                        .setApplyHook(state -> reloadShaders()))));
    }

    private static void saveConfig() {
        try {
            LuminRT.getConfig().save();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save LuminRT configuration", e);
        }
    }

    private static void reloadShaders() {
        // Sodium 先保存所有配置，再执行应用回调，使重新加载的光影包使用已提交的开关状态。
        try {
            Iris.reload();
            // 开关变化不一定改变 Iris 的顶点格式；主动重编译已加载区段以补齐地形快照。
            var minecraft = Minecraft.getInstance();
            if (LuminRT.getConfig().isExtensionEnabled() && minecraft.level != null)
                minecraft.levelExtractor.allChanged();
        } catch (IOException e) {
            LuminRT.LOGGER.error("Failed to reload shaders after applying LuminRT settings", e);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.translatable("iris.shaders.reloaded.failure", e.getMessage()));
            }
        }
    }
}
