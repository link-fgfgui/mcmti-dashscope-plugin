package me.jaffe2718.mcmti.doubao.config;

import eu.midnightdust.lib.config.MidnightConfig;

/**
 * MidnightLib config for the Doubao (Volcengine) plugin.
 * <p>
 * Registered from the platform entry points via
 * {@link MidnightConfig#init(String, Class)}. The plugin is considered
 * configured once {@link #apiKey} is non-blank, which is what drives
 * {@link me.jaffe2718.mcmti.doubao.DoubaoSpeechRecognizer#enabled()}.
 * <p>
 * The matching translation keys live in
 * {@code assets/mcmti_doubao/lang/*.json} under the {@code mcmti_doubao.midnightconfig.*}
 * namespace.
 */
public class DoubaoConfig extends MidnightConfig {

    /** Mod id, must match {@code mods.toml} / {@code fabric.mod.json}. */
    public static final String MOD_ID = "mcmti_doubao";

    /** Model name required by the flash endpoint; currently only {@code bigmodel}. */
    public static final String DEFAULT_MODEL = "bigmodel";

    /**
     * Volcengine speech API key (new console). Sent as {@code X-Api-Key}.
     * Leave empty to disable this recognizer.
     */
    @Entry(category = "general", width = 128)
    public static String apiKey = "";

    @Entry(category = "general", width = 64)
    public static String model = DEFAULT_MODEL;

    /**
     * Recognition language. {@code auto} (or blank) omits the field so the model
     * auto-detects Chinese/English and the supported dialects; otherwise pass a
     * code such as {@code zh-CN} / {@code en-US} (short forms like {@code zh}/{@code en}
     * are accepted too).
     */
    @Entry(category = "general", width = 15)
    public static String language = "auto";

    /** Inverse text normalization ("一九七零年" → "1970 年"). */
    @Entry(category = "general")
    public static boolean enableItn = true;

    /** Add punctuation to the transcript. */
    @Entry(category = "general")
    public static boolean enablePunc = true;

    /** Semantic smoothing — drop filler words / repetitions. */
    @Entry(category = "general")
    public static boolean enableDdc = false;

    @Entry(category = "general", min = 1000, max = 300_000)
    public static int requestTimeoutMs = 60_000;

    /** Whether the Doubao recognizer should consider itself enabled. */
    public static boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
