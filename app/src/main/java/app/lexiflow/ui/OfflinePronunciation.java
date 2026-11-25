package app.lexiflow.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;

/** Experimental English pronunciation via an already installed offline voice. */
final class OfflinePronunciation implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private boolean ready;

    OfflinePronunciation(Context context, Runnable stateChanged) {
        engine = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> {
            if (engine == null) return;
            if (status == TextToSpeech.SUCCESS) {
                ready = activateOfflineEnglishVoice(engine.getVoices(),
                        voice -> engine.setVoice(voice) == TextToSpeech.SUCCESS);
            }
            stateChanged.run();
        }));
    }

    boolean available() { return ready; }

    static boolean activateOfflineEnglishVoice(Set<Voice> voices,Predicate<Voice> activate) {
        return voices != null && voices.stream()
                .filter(voice -> "en".equals(voice.getLocale().getLanguage())
                        && !voice.isNetworkConnectionRequired()
                        && (voice.getFeatures() == null
                        || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                .sorted(Comparator.comparing(Voice::getName))
                .anyMatch(activate);
    }

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
