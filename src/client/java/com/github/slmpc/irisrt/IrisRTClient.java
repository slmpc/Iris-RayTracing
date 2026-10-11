package com.github.slmpc.irisrt;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IrisRTClient implements ClientModInitializer {
    public static final String MOD_ID = "irisrt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("IrisRT initialized");
    }
}
