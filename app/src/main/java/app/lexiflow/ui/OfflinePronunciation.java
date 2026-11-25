package app.lexiflow.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.Set;

/** Experimental English pronunciation via an already installed offline voice. */
final class OfflinePronunciation implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private boolean ready;

    OfflinePronunciation(Context context, Runnable stateChanged) {
        engine = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> {
            if (engine == null) return;
            if (status == TextToSpeech.SUCCESS) {
                Set<Voice> voices = engine.getVoices();
                if (voices != null) {
                    Voice voice = voices.stream().filter(v -> "en".equals(v.getLocale().getLanguage())
                            && !v.isNetworkConnectionRequired()
                            && (v.getFeatures() == null
                            || !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                            .min(Comparator.comparing(Voice::getName)).orElse(null);
                    ready = voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS;
                }
            }
            stateChanged.run();
        }));
    }

    boolean available() { return ready; }

    boolean speak(String text) {
        return ready && engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,"lexiflow-headword") == TextToSpeech.SUCCESS;
    }

    void stop() { if (ready) engine.stop(); }

    @Override public void close() {
        ready = false;
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
    }
}
