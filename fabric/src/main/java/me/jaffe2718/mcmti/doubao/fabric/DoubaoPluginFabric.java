package me.jaffe2718.mcmti.doubao.fabric;

import me.jaffe2718.mcmti.doubao.DoubaoSpeechRecognizer;
import me.jaffe2718.mcmti.doubao.config.DoubaoConfig;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric client entry point for mcmti-doubao-plugin.
 * <p>
 * Registers the Doubao {@link SpeechRecognizer} (mcmti 3.x API) with
 * priority 0 so that once the plugin config has an API key it becomes the
 * active recognizer. Priority 0 (higher than the built-in recognizers' 100)
 * means an explicitly-configured Doubao key wins; without a key the
 * recognizer is disabled and selection falls through to the built-ins.
 */
public final class DoubaoPluginFabric implements ClientModInitializer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DoubaoPluginFabric.class);

    @Override
    public void onInitializeClient() {
        DoubaoConfig.init(DoubaoConfig.MOD_ID, DoubaoConfig.class);
        SpeechRecognizer.register(
                0,
                new ResourceLocation(DoubaoConfig.MOD_ID, "doubao"),
                DoubaoSpeechRecognizer::new
        );
        LOGGER.info("mcmti-doubao-plugin: Doubao recognizer registered (priority 0)");
    }
}
