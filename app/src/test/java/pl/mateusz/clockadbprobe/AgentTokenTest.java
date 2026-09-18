package pl.mateusz.clockadbprobe;

import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentTokenTest {
    @Test
    public void generatedTokenHas192BitsEncodedAsLowercaseHex() {
        String first = AgentToken.generate(new SecureRandom());
        String second = AgentToken.generate(new SecureRandom());

        assertEquals(48, first.length());
        assertTrue(AgentToken.isValid(first));
        assertTrue(AgentToken.isValid(second));
        assertFalse(first.equals(second));
        assertFalse(AgentToken.isValid("k7-clock-agent"));
    }
}
