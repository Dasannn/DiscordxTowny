package com.discordtowny.link;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias para {@link CodeGenerator}.
 *
 * <p>Verifica que los codigos generados tengan 6 caracteres, no contengan
 * caracteres ambiguos (0, O, 1, I, l, L) y utilicen SecureRandom.
 */
class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void codigoTiene6Caracteres() {
        String code = generator.nextCode();
        assertNotNull(code);
        assertEquals(6, code.length());
    }

    @Test
    void codigosNoContienenCaracteresAmbiguos() {
        // Probamos una muestra grande para garantizar que ningun codigo generado
        // contenga 0, O, 1, I, l, L.
        for (int i = 0; i < 10_000; i++) {
            String code = generator.nextCode();
            assertFalse(CodeGenerator.containsAmbiguousCharacters(code),
                    "El codigo generado no debe contener caracteres ambiguos: " + code);
            assertFalse(code.contains("0"), "No debe contener 0");
            assertFalse(code.contains("O"), "No debe contener O");
            assertFalse(code.contains("1"), "No debe contener 1");
            assertFalse(code.contains("I"), "No debe contener I");
            assertFalse(code.contains("l"), "No debe contener l");
            assertFalse(code.contains("L"), "No debe contener L");
        }
    }

    @Test
    void codigosCumplenPatronDeAlfabeto() {
        String pattern = "^[" + CodeGenerator.ALPHABET + "]{6}$";
        for (int i = 0; i < 1_000; i++) {
            String code = generator.nextCode();
            assertTrue(code.matches(pattern), "El codigo debe coincidir con el alfabeto permitido: " + code);
        }
    }

    @Test
    void isAmbiguousDetectaCorrectamente() {
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
    void diversidadDeCodigosGenerados() {
        Set<String> generados = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generados.add(generator.nextCode());
        }
        // Con 31^6 combinaciones y la paradoja del cumpleanos (~0,056 % prob. de colision en 1000 muestras),
        // no se exige unicidad absoluta del 100 % para no provocar falsos positivos en el build.
        assertTrue(generados.size() >= 995, "La gran mayoria de codigos deben ser distintos: " + generados.size());
    }

    @Test
    void constructorAceptaSecureRandomPersonalizado() {
        SecureRandom customRandom = new SecureRandom();
        CodeGenerator customGenerator = new CodeGenerator(customRandom);
        assertEquals(6, customGenerator.nextCode().length());
    }
}
