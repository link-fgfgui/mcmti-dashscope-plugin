package me.jaffe2718.mcmti.dashscope;

import me.jaffe2718.mcmti.asr.AsrRequest;
import me.jaffe2718.mcmti.asr.AsrResult;
import me.jaffe2718.mcmti.asr.WavAudio;
import me.jaffe2718.mcmti.dashscope.config.DashScopeConfig;
import me.jaffe2718.mcmti.util.AudioRecorder;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * mcmti 3.0.0 {@link SpeechRecognizer} for Alibaba DashScope
 * ({@code paraformer-realtime-v2} streaming WebSocket ASR).
 * <p>
 * Enabled when the plugin's {@link DashScopeConfig} has a non-blank API key.
 * Selection follows the mcmti pattern used by the built-in MiMo / OpenAI
 * recognizers: {@link #activate()} builds a fresh client and falls back to
 * {@link #unavailableToast()} if it isn't configured, {@link #deactivate()}
 * releases it, and {@link #transcribe(float[])} streams the recorded PCM to
 * DashScope.
 */
public final class DashScopeSpeechRecognizer extends SpeechRecognizer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopeSpeechRecognizer.class);

    private volatile @Nullable DashScopeAsrClient client;

    public DashScopeSpeechRecognizer(@NotNull ResourceLocation regId) {
        super(regId);
    }

    @Override
    public boolean enabled() {
        return DashScopeConfig.isConfigured();
    }

    @Override
    protected @NotNull Component availableToast() {
        return Component.translatable("message.mcmti.dashscope.available");
    }

    @Override
    protected @NotNull Component unavailableToast() {
        return Component.translatable("message.mcmti.dashscope.unavailable");
    }

    @Override
    protected boolean available() {
        return super.available() && this.client != null && this.client.isReady();
    }

    @Override
    protected void activate() throws IOException {
        DashScopeAsrClient created = new DashScopeAsrClient(
                DashScopeConfig.apiKey,
                DashScopeConfig.model,
                resolveLanguageHints(DashScopeConfig.language),
                DashScopeConfig.requestTimeoutMs
        );
        if (!created.isReady()) {
            this.client = null;
            throw new IOException("DashScope ASR is not configured (missing API key)");
        }
        this.client = created;
        super.activate();
    }

    @Override
    protected void deactivate() {
        DashScopeAsrClient old = this.client;
        this.client = null;
        if (old != null) {
            old.close();
        }
        super.deactivate();
    }

    @Override
    public @NotNull String transcribe(float[] audio) {
        DashScopeAsrClient current = this.client;
        if (current == null || !current.isReady()) {
            LOGGER.warn("DashScope recognizer is not available; returning empty transcription");
            return "";
        }
        byte[] pcm = WavAudio.floatToPcm16(audio);
        AsrResult result = current.transcribe(
                new AsrRequest(pcm, AudioRecorder.SAMPLE_RATE, DashScopeConfig.language)
        );
        return result.text().trim();
    }

    /**
     * Map the config language string to DashScope {@code language_hints}.
     * {@code auto} or blank → {@code null} (let the model auto-detect).
     */
    private static String @Nullable [] resolveLanguageHints(@Nullable String language) {
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
