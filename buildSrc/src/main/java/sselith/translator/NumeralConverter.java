package sselith.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Base-6 cardinal conversion for Sselith. Cardinals 0–46,655 use unit-name form
 * ({@code tesk-schest} = 2×6); 46,656+ falls back to a pure digit-chain. {@code skarn}
 * is reserved for fractional (holy-number) expressions and never appears in cardinals.
 * The dictionary's {@code numerals} block is human reference only — this logic doesn't read it.
 */
public final class NumeralConverter {

    private static final String[] DIGITS = {
        "ul", "vir", "tesk", "moroch", "kelkargh", "vraestmorocht"
    };
    private static final String[] UNITS = {
        null, "schest", "schaer", "schalmokt", "schaelvit", "schorruekt"
    };
    private static final int MAX_UNIT_POWER = 5;
    private static final String RADIX = "skarn";
    private static final String NON_TERMINATING = "vrecht";

    private NumeralConverter() {}

    public static String integerToCardinal(long n) {
        if (n < 0) {
            return "ont-" + integerToCardinal(-n);  // REV prefix for negatives
        }
        if (n == 0) {
            return "ul";
        }

        // Collect base-6 digits, least significant first.
        List<Integer> digitList = new ArrayList<>();
        long temp = n;
        while (temp > 0) {
            digitList.add((int) (temp % 6));
            temp /= 6;
        }
        int highest = digitList.size() - 1;

        // If the highest place exceeds our unit-name family, use a pure digit-chain.
        if (highest > MAX_UNIT_POWER) {
            StringBuilder sb = new StringBuilder();
            for (int i = highest; i >= 0; i--) {
                if (sb.length() > 0) sb.append('-');
                sb.append(DIGITS[digitList.get(i)]);
            }
            return sb.toString();
        }

        // Otherwise, use unit-name form.
        StringBuilder sb = new StringBuilder();
        for (int i = highest; i >= 0; i--) {
            int d = digitList.get(i);
            if (d == 0) continue;
            if (sb.length() > 0) sb.append('-');
            if (i == 0) {
                sb.append(DIGITS[d]);
            } else if (d == 1) {
                sb.append(UNITS[i]);          // bare unit means 1× that unit
            } else {
                sb.append(DIGITS[d]).append('-').append(UNITS[i]);
            }
        }
        return sb.toString();
    }

    public static String decimalToSselith(double d) {
        // Split into integer and fractional parts.
        long integerPart = (long) d;
        double fraction = d - integerPart;

        String intStr = integerToCardinal(integerPart);

        if (fraction == 0.0) {
            return intStr;
        }

        // Convert the fraction to base-6 by repeated multiplication.
        StringBuilder fracDigits = new StringBuilder();
        int maxFracDigits = 8;  // cap to avoid runaway
        boolean terminating = true;
        for (int i = 0; i < maxFracDigits && fraction > 0; i++) {
            fraction *= 6;
            int digit = (int) fraction;
            fracDigits.append('-').append(DIGITS[digit]);
            fraction -= digit;
        }
        if (fraction > 0) {
            terminating = false;
        }

        StringBuilder result = new StringBuilder(intStr);
        result.append('-').append(RADIX);
        result.append(fracDigits);
        if (!terminating) {
            result.append('-').append(NON_TERMINATING);
        }
        return result.toString();
    }
}
