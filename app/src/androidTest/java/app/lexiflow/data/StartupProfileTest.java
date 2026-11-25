package app.lexiflow.data;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class StartupProfileTest {
    private static final String TAG = "LexiFlowStartup";

    @Test public void profilesVerifiedReopeningOfCompactFixtures() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var assets = instrumentation.getContext().getAssets();
        Assume.assumeTrue("local compact fixtures",assets.list("runtime-scale/5000-compact").length == 2);
        for (String path : new String[]{"runtime-scale/5000-compact","runtime-scale/20000-compact",
                "runtime-capacity/100000-compact"}) {
            if (assets.list(path).length != 2) continue;
            String size = path.substring(path.indexOf('/')+1,path.lastIndexOf('-'));
            var context = instrumentation.getTargetContext();
            File root = new File(context.getCacheDir(),"startup-" + UUID.randomUUID());
            var wrapped = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return root; }
            };
            try {
                var installer = new LocalDictionaryRepository(wrapped);
                try (var json = assets.open(path+"/manifest.json");
                     var db = assets.open(path+"/dictionary.sqlite")) {
                    installer.install(json,db);
                    assertTrue(installer.isSynthetic());
                } finally {
                    installer.close();
                }
                File packageRoot = new File(root,"dictionary");
                String installed = DictionaryPackageStore.read(packageRoot).active();
                File manifest = new File(new File(packageRoot,installed),"manifest.json");
                for (int sample = 1; sample <= 3; sample++) {
                    String label = size + " sample=" + sample;
                    measureFile(label,manifest,false);
                    measureFile(label,manifest,true);
                    var repository = new LocalDictionaryRepository(wrapped,(phase,nanos) -> log(label,phase,nanos));
                    try {
                        repository.load();
                        assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
                        assertEquals(1,repository.search("artificial-scale-000001",false).size());
                    } finally {
                        repository.close();
                    }
                }
            } finally {
                if (root.exists()) {
                    try (var paths = Files.walk(root.toPath())) {
                        for (var file : paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) {
                            Files.delete(file);
                        }
                    }
                }
            }
        }
    }

    private static void measureFile(String label,File file,boolean hash) throws Exception {
        long start = System.nanoTime();
        var digest = hash ? MessageDigest.getInstance("SHA-256") : null;
        var bytes = new byte[32*1024];
        try (var input = new FileInputStream(file)) {
            for (int count; (count = input.read(bytes)) != -1;) {
                if (digest != null) digest.update(bytes,0,count);
            }
        }
        if (digest != null) digest.digest();
        log(label,hash ? "raw_manifest_read_and_digest" : "raw_manifest_read",System.nanoTime()-start);
    }

    private static void log(String label,String phase,long nanos) {
        Log.i(TAG,label + " phase=" + phase + " ms=" + nanos/1_000_000.0);
    }
}
