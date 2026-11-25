package app.lexiflow.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Test-only proof. The key pair lives only for the duration of this instrumentation test. */
@RunWith(AndroidJUnit4.class)
public class SignedManifestProofTest {
    @Test public void pinnedPublicKeyBindsManifestBytesAndDatabase() throws Exception {
        var assets = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        byte[] manifest = read(assets.open("dictionary-v2/manifest.json"));
        byte[] database = read(assets.open("dictionary-v2/dictionary.sqlite"));
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var signer = keys.generateKeyPair();
        var other = keys.generateKeyPair();
        var algorithm = Signature.getInstance("SHA256withRSA");
        algorithm.initSign(signer.getPrivate());
        algorithm.update(manifest);
        byte[] signature = algorithm.sign();

        assertTrue(verify(signer.getPublic(),manifest,signature));
        assertFalse(verify(other.getPublic(),manifest,signature));
        byte[] tamperedManifest = manifest.clone();
        tamperedManifest[0] ^= 1;
        assertFalse(verify(signer.getPublic(),tamperedManifest,signature));
        byte[] tamperedSignature = signature.clone();
        tamperedSignature[0] ^= 1;
        assertFalse(verify(signer.getPublic(),manifest,tamperedSignature));

        var parsed = PackageManifest.parse(new InputStreamReader(
                new ByteArrayInputStream(manifest),StandardCharsets.UTF_8));
        assertEquals(parsed.digest(),digest(database));
        database[database.length-1] ^= 1;
        assertNotEquals(parsed.digest(),digest(database));
    }

    private static boolean verify(PublicKey key,byte[] manifest,byte[] signature) throws Exception {
        var algorithm = Signature.getInstance("SHA256withRSA");
        algorithm.initVerify(key);
        algorithm.update(manifest);
        try {
            return algorithm.verify(signature);
        } catch (java.security.SignatureException malformed) {
            return false;
        }
    }

    private static byte[] read(InputStream stream) throws Exception {
        try (stream; var bytes = new ByteArrayOutputStream()) {
            LocalDictionaryRepository.transfer(stream,bytes,1024*1024);
            return bytes.toByteArray();
        }
    }

    private static String digest(byte[] input) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(input)) {
            result.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
        }
        return result.toString();
    }
}
