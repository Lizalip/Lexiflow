package app.lexiflow.ui;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflinePronunciationTest {
    @Test public void probesInstalledEngineWithoutSpeakingOrChangingConnectivity() throws InterruptedException {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var pronunciation = new AtomicReference<OfflinePronunciation>();
        var initialized = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> pronunciation.set(
                new OfflinePronunciation(instrumentation.getTargetContext(),initialized::countDown)));
        try {
            boolean replied = initialized.await(8,TimeUnit.SECONDS);
            instrumentation.runOnMainSync(() -> {
                boolean available = pronunciation.get().available();
                Log.i("LexiFlowOffline","engineReplied=" + replied + " localEnglishVoice=" + available);
            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                pronunciation.get().close();
                assertFalse("availability must reset when the engine is closed",pronunciation.get().available());
            });
        }
    }

    @Test public void neverSelectsNetworkOrUninstalledVoicesAndTriesNextOfflineVoice() {
        var voices = new HashSet<>(Arrays.asList(
                voice("a-network",Locale.US,true),
                voice("b-missing",Locale.US,false,TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                voice("c-other-language",Locale.forLanguageTag("ru"),false),
                voice("d-rejected",Locale.UK,false),
                voice("e-accepted",Locale.US,false)
        ));
        var attempts = new ArrayList<String>();
        assertTrue(OfflinePronunciation.activateOfflineEnglishVoice(voices,voice -> {
            attempts.add(voice.getName());
            return voice.getName().equals("e-accepted");
        }));
        assertEquals(Arrays.asList("d-rejected","e-accepted"),attempts);
        assertFalse(OfflinePronunciation.activateOfflineEnglishVoice(null,voice -> true));
        assertFalse(OfflinePronunciation.activateOfflineEnglishVoice(Collections.singleton(
                voice("network-only",Locale.US,true)),voice -> true));
    }

    private static Voice voice(String name,Locale locale,boolean network,String... features) {
        return new Voice(name,locale,Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,network,
                new HashSet<>(Arrays.asList(features)));
    }
}
