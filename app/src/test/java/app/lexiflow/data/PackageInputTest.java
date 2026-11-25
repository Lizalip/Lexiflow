package app.lexiflow.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PackageInputTest {
    @Test public void inputAtLimitIsCopiedAndOversizeRejected() throws IOException {
        byte[] exact = {1,2,3};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalDictionaryRepository.transfer(new ByteArrayInputStream(exact),output,3);
        assertArrayEquals(exact,output.toByteArray());
        output.reset();
        assertThrows(IOException.class,
                () -> LocalDictionaryRepository.transfer(new ByteArrayInputStream(exact),output,2));
        assertEquals(0,output.size());
    }
}
