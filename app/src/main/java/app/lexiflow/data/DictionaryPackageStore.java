package app.lexiflow.data;

import android.database.sqlite.SQLiteException;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The active and fallback identities are committed together. Call while holding LOCK. */
final class DictionaryPackageStore {
    static final Object LOCK = new Object();
    private static final Map<File,Integer> OPEN = new HashMap<>();
    record State(String active,String previous) {}

    private DictionaryPackageStore() {}

    static boolean isKey(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static State read(File root) throws IOException {
        try (var input = new AtomicFile(new File(root,"active")).openRead()) {
            var data = new ByteArrayOutputStream(73);
            LocalDictionaryRepository.transfer(input,data,73);
            String[] keys = new String(data.toByteArray(),StandardCharsets.US_ASCII).split("\\n",-1);
            if (!isKey(keys[0]) || keys.length > 2
                    || (keys.length == 2 && (!isKey(keys[1]) || keys[0].equals(keys[1])))) {
                throw new SQLiteException("invalid package pointers");
            }
            return new State(keys[0],keys.length == 2 ? keys[1] : null);
        } catch (FileNotFoundException absent) {
            // A missing pointer means no candidate has been committed.
            if (new File(root,"active").exists()) throw absent;
            return new State(null,null);
        }
    }

    static void write(File root,State state) throws IOException {
        AtomicFile file = new AtomicFile(new File(root,"active"));
        String text = state.active() + (state.previous() == null ? "" : "\n"+state.previous());
        FileOutputStream output = file.startWrite();
        try {
            output.write(text.getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(output);
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
        if (!state.equals(read(root))) throw new IOException("package pointers were not updated");
    }

    static void opened(File folder) { OPEN.merge(folder.getAbsoluteFile(),1,Integer::sum); }

    static void closed(File folder) {
        OPEN.computeIfPresent(folder.getAbsoluteFile(),(path,count) -> count == 1 ? null : count-1);
    }

    static void prune(File root,State state) {
        File[] folders = root.listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            String name = folder.getName();
            if (!isKey(name) || name.equals(state.active()) || name.equals(state.previous())
                    || OPEN.containsKey(folder.getAbsoluteFile())) continue;
            removeCandidate(folder);
        }
    }

    static void removeCandidate(File folder) {
        if (!Files.isDirectory(folder.toPath(),LinkOption.NOFOLLOW_LINKS)) return;
        File[] contents = folder.listFiles();
        if (contents == null) return;
        for (File item : contents) {
            String name = item.getName();
            if (!Files.isRegularFile(item.toPath(),LinkOption.NOFOLLOW_LINKS)
                    || !(name.equals("manifest.json") || name.equals("dictionary.sqlite")
                      || name.equals("validation-receipt") || name.equals("validation-receipt.new")
                      || name.equals("validation-receipt.bak"))) return;
        }
        try {
            for (File item : contents) Files.deleteIfExists(item.toPath());
            Files.deleteIfExists(folder.toPath());
        } catch (IOException ignored) {
            // Storage maintenance cannot make a validated package unavailable.
        }
    }
}
