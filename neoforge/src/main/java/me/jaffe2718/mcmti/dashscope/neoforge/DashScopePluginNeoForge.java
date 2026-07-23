package me.jaffe2718.mcmti.dashscope.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NeoForge entry point for mcmti-dashscope-plugin.
 * <p>
 * Provider registration is via Java {@link java.util.ServiceLoader} (same as
 * Fabric). This class only logs readiness and satisfies NeoForge's mod-loading
 * contract.
 */
@Mod(value = "mcmti_dashscope", dist = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class DashScopePluginNeoForge {

    private static final Logger LOGGER =
            Logger.getLogger(DashScopePluginNeoForge.class.getName());

    public DashScopePluginNeoForge(@NotNull IEventBus modEventBus) {
        LOGGER.log(Level.INFO,
                "mcmti-dashscope-plugin loaded on NeoForge. "
                        + "Select ASR Provider = PLUGIN, Plugin Provider Id = dashscope "
                        + "in mcmti config to activate paraformer-realtime-v2."
        );
    }
}
