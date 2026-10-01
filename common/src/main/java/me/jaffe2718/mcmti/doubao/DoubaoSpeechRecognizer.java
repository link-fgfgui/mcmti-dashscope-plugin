package me.jaffe2718.mcmti.doubao;

import me.jaffe2718.mcmti.asr.AsrRequest;
import me.jaffe2718.mcmti.asr.AsrResult;
import me.jaffe2718.mcmti.asr.WavAudio;
import me.jaffe2718.mcmti.doubao.config.DoubaoConfig;
import me.jaffe2718.mcmti.util.AudioRecorder;
import me.jaffe2718.mcmti.util.SpeechRecognizer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * mcmti 3.0.0 {@link SpeechRecognizer} for Volcengine Doubao big-model speech
 * recognition (record file flash endpoint, synchronous HTTP).
 * <p>
 * Enabled when the plugin's {@link DoubaoConfig} has a non-blank API key.
 * Selection follows the mcmti pattern used by the built-in MiMo / OpenAI
 * recognizers: {@link #activate()} builds a fresh client and falls back to
 * {@link #unavailableToast()} if it isn't configured, {@link #deactivate()}
 * releases it, and {@link #transcribe(float[])} posts the recorded PCM (wrapped
 * as WAV) to Doubao.
 */
public final class DoubaoSpeechRecognizer extends SpeechRecognizer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DoubaoSpeechRecognizer.class);

    private volatile @Nullable DoubaoAsrClient client;

    public DoubaoSpeechRecognizer(@NotNull ResourceLocation regId) {
        super(regId);
    }

    @Override
    public boolean enabled() {
        return DoubaoConfig.isConfigured();
    }

    @Override
    protected @NotNull Component availableToast() {
        return Component.translatable("message.mcmti.doubao.available");
    }

    @Override
    protected @NotNull Component unavailableToast() {
        return Component.translatable("message.mcmti.doubao.unavailable");
    }

    @Override
    protected boolean available() {
        return super.available() && this.client != null && this.client.isReady();
    }

    @Override
    protected void activate() throws IOException {
        DoubaoAsrClient created = new DoubaoAsrClient(
                DoubaoConfig.apiKey,
                DoubaoConfig.model,
                DoubaoConfig.language,
                DoubaoConfig.enableItn,
                DoubaoConfig.enablePunc,
                DoubaoConfig.enableDdc,
                DoubaoConfig.requestTimeoutMs
        );
        if (!created.isReady()) {
            this.client = null;
            throw new IOException("Doubao ASR is not configured (missing API key)");
        }
        this.client = created;
        super.activate();
    }

    @Override
    protected void deactivate() {
        this.client = null;
        super.deactivate();
    }

    @Override
    public @NotNull String transcribe(float[] audio) {
        DoubaoAsrClient current = this.client;
        if (current == null || !current.isReady()) {
            LOGGER.warn("Doubao recognizer is not available; returning empty transcription");
            return "";
        }
        byte[] pcm = WavAudio.floatToPcm16(audio);
        AsrResult result = current.transcribe(
                new AsrRequest(pcm, AudioRecorder.SAMPLE_RATE, DoubaoConfig.language)
        );
        return result.text().trim();
    }
}
