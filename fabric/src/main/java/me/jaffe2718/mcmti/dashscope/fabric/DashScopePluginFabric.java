package me.jaffe2718.mcmti.dashscope.fabric;

import me.jaffe2718.mcmti.dashscope.DashScopeSpeechRecognizer;
import me.jaffe2718.mcmti.dashscope.config.DashScopeConfig;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric client entry point for mcmti-dashscope-plugin.
 * <p>
 * Registers the DashScope {@link SpeechRecognizer} (mcmti 3.x API) with
 * priority 0 so that once the plugin config has an API key it becomes the
 * active recognizer. Priority 0 (higher than the built-in recognizers' 100)
 * means an explicitly-configured DashScope key wins; without a key the
 * recognizer is disabled and selection falls through to the built-ins.
 */
public final class DashScopePluginFabric implements ClientModInitializer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopePluginFabric.class);

    @Override
    public void onInitializeClient() {
        DashScopeConfig.init(DashScopeConfig.MOD_ID, DashScopeConfig.class);
        SpeechRecognizer.register(
                0,
                new ResourceLocation(DashScopeConfig.MOD_ID, "dashscope"),
                DashScopeSpeechRecognizer::new
        );
        LOGGER.info("mcmti-dashscope-plugin: DashScope recognizer registered (priority 0)");
    }
}
