package me.jaffe2718.mcmti.dashscope.forge;

import eu.midnightdust.lib.config.MidnightConfig;
import me.jaffe2718.mcmti.dashscope.DashScopeSpeechRecognizer;
import me.jaffe2718.mcmti.dashscope.config.DashScopeConfig;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge (1.20.1) entry point for mcmti-dashscope-plugin.
 * <p>
 * Registers the DashScope {@link SpeechRecognizer} (mcmti 3.x API) with
 * priority 0. See {@link me.jaffe2718.mcmti.dashscope.fabric.DashScopePluginFabric}
 * for the priority rationale.
 * <p>
 * The mod is client-only, so construction is a no-op on a dedicated server —
 * mirroring the parent mcmti mod's Forge entry point.
 * <p>
 * Config initialisation is deferred rather than done in the constructor or
 * directly in the setup handler: {@code MidnightConfig.init} mutates
 * process-wide static state (entries, auto-commands, config instances) and is
 * not thread-safe, while Forge constructs mods and dispatches setup events in
 * parallel. Both race with mcmti's own config init / MidnightLib's own setup
 * handler and throw {@link java.util.ConcurrentModificationException}, so the
 * work is queued onto the main thread. The config screen is registered
 * explicitly because MidnightLib would otherwise wire it up before our config
 * instance exists.
 */
@Mod(DashScopeConfig.MOD_ID)
public final class DashScopePluginForge {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopePluginForge.class);

    public DashScopePluginForge() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) ->
                                MidnightConfig.getScreen(parent, DashScopeConfig.MOD_ID))
        );

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // Run off the parallel setup thread, once the whole event dispatch has
        // finished, to avoid racing other mods' MidnightConfig usage.
        event.enqueueWork(DashScopePluginForge::registerRecognizer);
    }

    private static void registerRecognizer() {
        DashScopeConfig.init(DashScopeConfig.MOD_ID, DashScopeConfig.class);
        SpeechRecognizer.register(
                0,
                new ResourceLocation(DashScopeConfig.MOD_ID, "dashscope"),
                DashScopeSpeechRecognizer::new
        );
        LOGGER.info("mcmti-dashscope-plugin: DashScope recognizer registered (priority 0)");
    }
}
