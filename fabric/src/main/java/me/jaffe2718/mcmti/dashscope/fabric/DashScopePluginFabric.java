package me.jaffe2718.mcmti.dashscope.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fabric client entry point for mcmti-dashscope-plugin.
 * <p>
 * The actual ASR provider registration is handled by Java's
 * {@link java.util.ServiceLoader} mechanism via
 * {@code META-INF/services/me.jaffe2718.mcmti.asr.SpeechAsrClientPlugin},
 * which mcmti discovers automatically. This entry point exists only to:
 * <ul>
 *   <li>Confirm the plugin loaded successfully</li>
 *   <li>Satisfy Fabric's mod loading (a mod without any entrypoint is valid
 *       but harder to debug)</li>
 * </ul>
 */
public final class DashScopePluginFabric implements ClientModInitializer {

    private static final Logger LOGGER =
            Logger.getLogger(DashScopePluginFabric.class.getName());

    @Override
    public void onInitializeClient() {
        LOGGER.log(Level.INFO,
                "mcmti-dashscope-plugin loaded on Fabric. "
                        + "Select ASR Provider = PLUGIN, Plugin Provider Id = dashscope "
                        + "in mcmti config to activate paraformer-realtime-v2."
        );
    }
}
