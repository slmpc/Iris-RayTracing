package com.github.slmpc.irisrt;

import com.github.slmpc.irisrt.config.IrisRTConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class IrisRT implements ClientModInitializer {
    public static final String MOD_ID = "irisrt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static IrisRTConfig getConfig() {
        return ConfigHolder.INSTANCE;
    }

    // 延迟加载，确保 Iris 首次预处理光影包时已读取配置，不依赖模组入口的执行顺序。
    private static final class ConfigHolder {
        private static final IrisRTConfig INSTANCE = loadConfig();

        private static IrisRTConfig loadConfig() {
            var config = new IrisRTConfig(FabricLoader.getInstance().getConfigDir().resolve("irisrt.properties"));
            try {
                config.initialize();
            } catch (IOException e) {
                LOGGER.error("Failed to initialize IrisRT configuration", e);
            }
            return config;
        }
    }

    @Override
    public void onInitializeClient() {
        getConfig();
        LOGGER.info("IrisRT initialized");
    }
}
