package com.github.slmpc.luminrt;

import com.github.slmpc.luminrt.config.LuminRTConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class LuminRT implements ClientModInitializer {
    public static final String MOD_ID = "luminrt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static LuminRTConfig getConfig() {
        return ConfigHolder.INSTANCE;
    }

    // 延迟加载，确保 Iris 首次预处理光影包时已读取配置，不依赖模组入口的执行顺序。
    private static final class ConfigHolder {
        private static final LuminRTConfig INSTANCE = loadConfig();

        private static LuminRTConfig loadConfig() {
            var config = new LuminRTConfig(FabricLoader.getInstance().getConfigDir().resolve("luminrt.properties"));
            try {
                config.initialize();
            } catch (IOException e) {
                LOGGER.error("Failed to initialize LuminRT configuration", e);
            }
            return config;
        }
    }

    @Override
    public void onInitializeClient() {
        getConfig();
        LOGGER.info("LuminRT initialized");
    }
}
