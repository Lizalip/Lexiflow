package app.lexiflow.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class PackageReceiptTest {
    @Test public void compactAttributionIsReverifiedWhenManifestBytesChange() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(target.getCacheDir(),"receipt-context-" + UUID.randomUUID());
        var repository = isolated(target,root);
        try (var json = target.getAssets().open("dictionary-context/manifest.json");
             var database = target.getAssets().open("dictionary-context/dictionary.sqlite")) {
            repository.install(json,database);
            repository.close();
            repository.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            repository.close();
            Path active = active(root);
            Path path = active.resolve("manifest.json");
            var manifest = new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));
            var attributions = manifest.getJSONObject("attributions");
            attributions.getJSONArray(attributions.keys().next()).put(2,"changed without recomputing provenance");
            Files.write(path,manifest.toString().getBytes(StandardCharsets.UTF_8));
            repository.load();
            assertEquals(DictionaryRepository.Status.CORRUPTED,repository.status());
            assertTrue(repository.search("zeta",false).isEmpty());
        } finally {
            repository.close();
            cleanup(root);
        }
    }

    @Test public void replacedBytesAndSidecarsCannotReuseReceipt() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        File root = new File(target.getCacheDir(),"receipt-" + UUID.randomUUID());
        var repository = isolated(target,root);
        try (var json = target.getAssets().open("dictionary-v2/manifest.json");
             var database = target.getAssets().open("dictionary-v2/dictionary.sqlite")) {
            repository.install(json,database);
            Path active = active(root);
            Path manifest = active.resolve("manifest.json");
            Path sqlite = active.resolve("dictionary.sqlite");
            Path receipt = active.resolve("validation-receipt");
            byte[] prior = Files.readAllBytes(receipt);
            repository.close();
            String current = new String(Files.readAllBytes(receipt),StandardCharsets.UTF_8);
            assertTrue(current.startsWith("5\n"));
            Files.write(receipt,("4"+current.substring(1)).getBytes(StandardCharsets.UTF_8));
            repository.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            assertEquals(current,new String(Files.readAllBytes(receipt),StandardCharsets.UTF_8));
            repository.close();
            var sidecar = active.resolve("dictionary.sqlite-wal");
            Files.createFile(sidecar);
            repository.load();
            assertEquals(DictionaryRepository.Status.CORRUPTED,repository.status());
            repository.close();
            Files.delete(sidecar);
            Files.write(manifest," ".getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
            repository.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            assertFalse(Arrays.equals(prior,Files.readAllBytes(receipt)));
            repository.close();
            try (var db = SQLiteDatabase.openDatabase(sqlite.toString(),null,SQLiteDatabase.OPEN_READWRITE)) {
                db.execSQL("UPDATE entry SET search_key='incorrect'");
            }
            repository.load();
            assertEquals(DictionaryRepository.Status.CORRUPTED,repository.status());
            repository.close();
            var altered = new JSONObject(new String(Files.readAllBytes(manifest),StandardCharsets.UTF_8));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sqlite));
            var hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            altered.put("database_sha256",hex.toString());
            Files.write(manifest,altered.toString().getBytes(StandardCharsets.UTF_8));
            repository.load();
            assertEquals(DictionaryRepository.Status.INCOMPATIBLE,repository.status());
        } finally {
            repository.close();
            cleanup(root);
        }
    }

    @Test public void compareFiveThousandEntryReopenWithAndWithoutReceipt() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        var assets = instrumentation.getContext().getAssets();
        String fixture = "runtime-scale/5000-compact";
        assumeTrue(assets.list(fixture).length == 2);
        File root = new File(target.getCacheDir(),"receipt-" + UUID.randomUUID());
        var repository = isolated(target,root);
        try (var json = assets.open(fixture+"/manifest.json"); var sqlite = assets.open(fixture+"/dictionary.sqlite")) {
            repository.install(json,sqlite);
            Path receipt = active(root).resolve("validation-receipt");
            long[] full = new long[3];
            long[] reused = new long[3];
            for (int i = 0; i < full.length; i++) {
                repository.close();
                Files.delete(receipt);
                full[i] = timedLoad(repository);
                repository.close();
                reused[i] = timedLoad(repository);
            }
            Arrays.sort(full);
            Arrays.sort(reused);
            Log.i("LexiFlowReceipt","5000-compact n=3 full_ms="+Arrays.toString(full)
                    +" receipt_ms="+Arrays.toString(reused));
        } finally {
            repository.close();
            cleanup(root);
        }
    }

    private static long timedLoad(LocalDictionaryRepository repository) {
        long stamp = SystemClock.elapsedRealtime();
        repository.load();
        assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
        return SystemClock.elapsedRealtime()-stamp;
    }

    private static LocalDictionaryRepository isolated(Context target,File root) {
        return new LocalDictionaryRepository(new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        });
    }

    private static Path active(File root) throws Exception {
        Path dictionary = root.toPath().resolve("dictionary");
        return dictionary.resolve(new String(Files.readAllBytes(dictionary.resolve("active")),StandardCharsets.UTF_8));
    }

    private static void cleanup(File root) throws Exception {
        if (!root.exists()) return;
        try (var files = Files.walk(root.toPath())) {
            for (var path : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
        }
    }
}
