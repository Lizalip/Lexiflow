package app.lexiflow.data;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DictionaryLifecycleTest {
    @Test public void interruptedCandidatesAreIgnoredAndPreviousPackageSurvives() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(target.getCacheDir(),"lifecycle-" + UUID.randomUUID());
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        };
        var repo = new LocalDictionaryRepository(isolated);
        File dictionary = new File(root,"dictionary");
        try {
            install(repo,target,"dictionary");
            var first = DictionaryPackageStore.read(dictionary);
            assertNull(first.previous());
            Path orphan = new File(dictionary,UUID.randomUUID().toString()).toPath();
            Files.createDirectory(orphan);
            Files.write(orphan.resolve("manifest.json"),new byte[]{1});
            var unknown = Files.createDirectory(new File(dictionary,"unrelated").toPath());
            repo.close();
            repo = new LocalDictionaryRepository(isolated);
            repo.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,repo.status());
            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(unknown));
            install(repo,target,"dictionary-v2");
            var second = DictionaryPackageStore.read(dictionary);
            assertEquals(first.active(),second.previous());

            var live = repo;
            try (var manifest = target.getAssets().open("dictionary-v2/manifest.json");
                 var broken = new InputStream() {
                     @Override public int read() throws IOException { throw new IOException("fixture interruption"); }
                 }) {
                assertThrows(IOException.class,() -> live.install(manifest,broken));
            }
            assertEquals(second,DictionaryPackageStore.read(dictionary));
            assertEquals(DictionaryRepository.Status.AVAILABLE,repo.status());
            install(repo,target,"dictionary");
            var third = DictionaryPackageStore.read(dictionary);
            assertEquals(second.active(),third.previous());
            assertFalse(new File(dictionary,first.active()).exists());

            repo.close();
            Files.delete(new File(new File(dictionary,third.active()),"dictionary.sqlite").toPath());
            repo = new LocalDictionaryRepository(isolated);
            repo.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,repo.status());
            assertEquals(second.active(),DictionaryPackageStore.read(dictionary).active());
            assertTrue(new File(dictionary,second.active()).isDirectory());
            assertFalse(new File(dictionary,third.active()).exists());
            assertFalse(repo.search("zeta",false).isEmpty());
        } finally {
            repo.close();
            if (root.exists()) {
                try (var paths = Files.walk(root.toPath())) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    private static void install(LocalDictionaryRepository repo,Context target,String asset) throws Exception {
        try (var manifest = target.getAssets().open(asset+"/manifest.json");
             var sqlite = target.getAssets().open(asset+"/dictionary.sqlite")) {
            repo.install(manifest,sqlite);
        }
    }
}
