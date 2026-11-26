package app.lexiflow.data;

import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class ResearchActivationProfileTest {
    @Test public void measuresInitialActivationAndReopenSeparately() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var expected = InstrumentationRegistry.getArguments().getString("lexiflowCorpus");
        assumeTrue(expected!=null && "app.lexiflow.researchcheck".equals(context.getPackageName()));
        var activation = profile(context,expected,"activation");
        for (String phase : new String[]{"manifest_copy","sqlite_copy","sqlite_digest","json_with_input",
                "attribution_decode_and_verify","sqlite_integrity","attributed_metadata",
                "package_relationships_and_keys","atomic_activation","repository"}) {
            assertTrue("Missing activation phase: "+phase,activation.containsKey(phase));
        }
        Log.i("LexiFlowResearch","activation phases ms="+activation);
        var reopen = profile(context,expected,"reopen");
        assertTrue(reopen.containsKey("repository"));
        assertFalse(reopen.containsKey("atomic_activation"));
        Log.i("LexiFlowResearch","reopen phases ms="+reopen);
    }

    private static Map<String,List<Double>> profile(android.content.Context context,String expected,String scenario) {
        Map<String,List<Double>> phases = new LinkedHashMap<>();
        var dictionary = new LocalDictionaryRepository(context,(phase,time) ->
                phases.computeIfAbsent(phase,key -> new ArrayList<>()).add(time/1_000_000.0));
        try {
            dictionary.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,dictionary.status());
            assertEquals(expected,dictionary.corpusVersion());
            var memory = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(memory);
            Log.i("LexiFlowResearch",scenario+" ready PSS KiB="+memory.getTotalPss());
        } finally {
            dictionary.close();
        }
        return phases;
    }
}
