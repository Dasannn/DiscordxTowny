package com.discordtowny.link;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CodeGenerator}.
 *
 * <p>Verifies that generated codes have 6 characters, do not contain
 * ambiguous characters (0, O, 1, I, l, L), and use SecureRandom.
 */
class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void codeHas6Characters() {
        String code = generator.nextCode();
        assertNotNull(code);
        assertEquals(6, code.length());
    }

    @Test
    void codesDoNotContainAmbiguousCharacters() {
        // We test a large sample to ensure that no generated code
        // contains 0, O, 1, I, l, L.
        for (int i = 0; i < 10_000; i++) {
            String code = generator.nextCode();
            assertFalse(CodeGenerator.containsAmbiguousCharacters(code),
                    "Generated code must not contain ambiguous characters: " + code);
            assertFalse(code.contains("0"), "Must not contain 0");
            assertFalse(code.contains("O"), "Must not contain O");
            assertFalse(code.contains("1"), "Must not contain 1");
            assertFalse(code.contains("I"), "Must not contain I");
            assertFalse(code.contains("l"), "Must not contain l");
            assertFalse(code.contains("L"), "Must not contain L");
        }
    }

    @Test
    void codesMatchAlphabetPattern() {
        String pattern = "^[" + CodeGenerator.ALPHABET + "]{6}$";
        for (int i = 0; i < 1_000; i++) {
            String code = generator.nextCode();
            assertTrue(code.matches(pattern), "Code must match the allowed alphabet: " + code);
        }
    }

    @Test
    void isAmbiguousDetectsCorrectly() {
        assertTrue(CodeGenerator.isAmbiguous('0'));
        assertTrue(CodeGenerator.isAmbiguous('O'));
        assertTrue(CodeGenerator.isAmbiguous('o'));
        assertTrue(CodeGenerator.isAmbiguous('1'));
        assertTrue(CodeGenerator.isAmbiguous('I'));
        assertTrue(CodeGenerator.isAmbiguous('i'));
        assertTrue(CodeGenerator.isAmbiguous('l'));
        assertTrue(CodeGenerator.isAmbiguous('L'));

        assertFalse(CodeGenerator.isAmbiguous('2'));
        assertFalse(CodeGenerator.isAmbiguous('A'));
        assertFalse(CodeGenerator.isAmbiguous('Z'));
    }

    @Test
    void diversityOfGeneratedCodes() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(generator.nextCode());
        }
        // With 31^6 combinations and the birthday paradox (~0.056% collision probability across 1000 samples),
        // absolute 100% uniqueness is not required to avoid false positives in the build.
        assertTrue(generated.size() >= 995, "The vast majority of codes must be distinct: " + generated.size());
    }

    @Test
    void constructorAcceptsCustomSecureRandom() {
        SecureRandom customRandom = new SecureRandom();
        CodeGenerator customGenerator = new CodeGenerator(customRandom);
        assertEquals(6, customGenerator.nextCode().length());
    }
}
