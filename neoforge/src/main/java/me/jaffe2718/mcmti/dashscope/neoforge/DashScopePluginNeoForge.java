package me.jaffe2718.mcmti.dashscope.neoforge;

import me.jaffe2718.mcmti.dashscope.DashScopeSpeechRecognizer;
import me.jaffe2718.mcmti.dashscope.config.DashScopeConfig;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge entry point for mcmti-dashscope-plugin.
 * <p>
 * Registers the DashScope {@link SpeechRecognizer} (mcmti 3.x API) with
 * priority 0. See {@link me.jaffe2718.mcmti.dashscope.fabric.DashScopePluginFabric}
 * for the priority rationale.
 */
@Mod(value = DashScopeConfig.MOD_ID, dist = Dist.CLIENT)
public final class DashScopePluginNeoForge {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopePluginNeoForge.class);

    public DashScopePluginNeoForge() {
        DashScopeConfig.init(DashScopeConfig.MOD_ID, DashScopeConfig.class);
        SpeechRecognizer.register(
                0,
                ResourceLocation.fromNamespaceAndPath(DashScopeConfig.MOD_ID, "dashscope"),
                DashScopeSpeechRecognizer::new
        );
        LOGGER.info("mcmti-dashscope-plugin: DashScope recognizer registered (priority 0)");
    }
}
