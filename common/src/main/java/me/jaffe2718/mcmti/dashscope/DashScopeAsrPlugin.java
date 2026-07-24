package me.jaffe2718.mcmti.dashscope;

import me.jaffe2718.mcmti.asr.AsrConfig;
import me.jaffe2718.mcmti.asr.SpeechAsrClient;
import me.jaffe2718.mcmti.asr.SpeechAsrClientPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * mcmti ASR plugin entry point for Alibaba DashScope.
 * <p>
 * Registered via {@code META-INF/services/me.jaffe2718.mcmti.asr.SpeechAsrClientPlugin}
 * and discovered by mcmti via {@link java.util.ServiceLoader}. Selected when
 * the user sets ASR Provider = {@code PLUGIN} and
 * {@code pluginProviderId = "dashscope"}.
 *
 * <p>Reads shared config from the {@link AsrConfig} passed by mcmti:
 * <ul>
 *   <li>{@link AsrConfig#apiKey()} — DashScope API key
 *       (falls back to {@code DASHSCOPE_API_KEY} env var)</li>
 *   <li>{@link AsrConfig#model()} — defaults to
 *       {@link DashScopeAsrClient#DEFAULT_MODEL} if blank or MiMo's model</li>
 *   <li>{@link AsrConfig#language()} — mapped to {@code language_hints};
 *       {@code auto} = omit (let model auto-detect)</li>
 *   <li>{@link AsrConfig#requestTimeoutMs()} — total call budget</li>
 * </ul>
 */
public final class DashScopeAsrPlugin implements SpeechAsrClientPlugin {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopeAsrPlugin.class);

    @Override
    public @NotNull String providerId() {
        return DashScopeAsrClient.PROVIDER_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "Alibaba DashScope (paraformer-realtime-v2)";
    }

    @Override
    public @NotNull SpeechAsrClient createFromConfig(@NotNull AsrConfig config) {
        String apiKey = resolveApiKey(config);
        String model = resolveModel(config);
        String[] hints = resolveLanguageHints(config.language());
        int timeoutMs = config.requestTimeoutMs() > 0
                ? config.requestTimeoutMs()
                : 60_000;

        LOGGER.info("Creating DashScope ASR client: model={}, language={}, languageHints={}, timeoutMs={}",
                model,
                config.language(),
                hints == null ? "(auto)" : String.join(",", hints),
                timeoutMs);

        return new DashScopeAsrClient(
                apiKey,
                model,
                hints,
                timeoutMs
        );
    }

    private static @NotNull String resolveApiKey(@NotNull AsrConfig config) {
        String key = config.apiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("DASHSCOPE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            LOGGER.warn("DashScope API key is empty. Set mcmti's apiKey config "
                    + "or the DASHSCOPE_API_KEY environment variable.");
            key = "";
        }
        return key;
    }

    private static @NotNull String resolveModel(@NotNull AsrConfig config) {
        String m = config.model();
        if (m == null || m.isBlank()
                || "mimo-v2.5-asr".equalsIgnoreCase(m)) {
            // User didn't switch the model field; apply DashScope default
            return DashScopeAsrClient.DEFAULT_MODEL;
        }
        return m;
    }

    /**
     * Map the language string to DashScope {@code language_hints} (String[]).
     * <p>
     * {@code auto} or blank → {@code null} (let model auto-detect).
     * Otherwise split on comma/slash/whitespace and trim each entry.
     */
    private static String @Nullable [] resolveLanguageHints(
            @Nullable String language
    ) {
        if (language == null || language.isBlank()
                || "auto".equalsIgnoreCase(language.trim())) {
            return null;
        }
        String[] parts = language.split("[,/\\s]+");
        List<String> hints = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                hints.add(trimmed);
            }
        }
        return hints.isEmpty() ? null : hints.toArray(new String[0]);
    }
}
