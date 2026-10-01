package me.jaffe2718.mcmti.dashscope.config;

import eu.midnightdust.lib.config.MidnightConfig;

/**
 * MidnightLib config for the DashScope plugin.
 * <p>
 * Registered from the platform entry points via
 * {@link MidnightConfig#init(String, Class)}. The plugin is considered
 * configured once {@link #apiKey} is non-blank, which is what drives
 * {@link me.jaffe2718.mcmti.dashscope.DashScopeSpeechRecognizer#enabled()}.
 * <p>
 * The matching translation keys live in
 * {@code assets/mcmti_dashscope/lang/*.json} under the {@code mcmti_dashscope.midnightconfig.*}
 * namespace.
 */
public class DashScopeConfig extends MidnightConfig {

    /** Mod id, must match {@code mods.toml} / {@code fabric.mod.json}. */
    public static final String MOD_ID = "mcmti_dashscope";

    /** Default streaming model for Mandarin/multilingual real-time ASR. */
    public static final String DEFAULT_MODEL = "paraformer-realtime-v2";

    @Entry(category = "general", width = 128)
    public static String apiKey = "";

    @Entry(category = "general", width = 64)
    public static String model = DEFAULT_MODEL;

    @Entry(category = "general", width = 15)
    public static String language = "auto";

    @Entry(category = "general", min = 1000, max = 300_000)
    public static int requestTimeoutMs = 60_000;

    /** Whether the DashScope recognizer should consider itself enabled. */
    public static boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
