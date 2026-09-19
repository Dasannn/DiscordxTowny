package com.discordtowny.link;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generador criptograficamente seguro de codigos de vinculacion.
 *
 * <p>Genera codigos de 6 caracteres alfanumericos sin caracteres ambiguos
 * (se excluyen 0, O, 1, I, l, L). Usa {@link SecureRandom}, nunca {@link java.util.Random},
 * para evitar que los codigos sean predecibles.
 *
 * <p>Analisis de entropia y riesgo residual de fuerza bruta distribuida (hallazgo 13):
 * El alfabeto consta de 31 simbolos. Un codigo de 6 simbolos produce 31^6 = 887.503.681
 * combinaciones posibles (aproximadamente 29,73 bits de entropia). Con el limite por
 * usuario de 5 intentos por ventana (AttemptTracker), la probabilidad de acertar un
 * codigo especifico es de solo 5 / 887.503.681 ≈ 5,63 x 10^-9. Sin embargo, en un
 * ataque distribuido mediante mil cuentas de Discord simultaneas, el presupuesto conjunto
 * alcanza 5.000 intentos (probabilidad ≈ 5,63 x 10^-6 contra un codigo particular,
 * multiplicada linealmente si existen M codigos vivos concurrentes). Este riesgo residual
 * queda documentado para que el arquitecto evalue si en el futuro se requiere una
 * defensa agregada global por ventana.
 */
public final class CodeGenerator {

    /**
     * Alfabeto de 31 caracteres alfanumericos no ambiguos:
     * Digitos: 2-9 (sin 0 ni 1)
     * Letras: A-Z (sin I, L, O)
     */
    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int CODE_LENGTH = 6;

    private final SecureRandom random;

    public CodeGenerator() {
        this(new SecureRandom());
    }

    public CodeGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Genera un nuevo codigo de vinculacion.
     */
    public String nextCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }

    /**
     * Comprueba si un caracter se considera ambiguo (0, O, 1, I, l, L).
     */
    public static boolean isAmbiguous(char c) {
        return c == '0' || c == 'O' || c == 'o'
                || c == '1' || c == 'I' || c == 'i'
                || c == 'l' || c == 'L';
    }

    /**
     * Comprueba si una cadena contiene algun caracter ambiguo.
     */
    public static boolean containsAmbiguousCharacters(String code) {
        if (code == null) return false;
        for (int i = 0; i < code.length(); i++) {
            if (isAmbiguous(code.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
