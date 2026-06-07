package sselith.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NumeralConverterTest {

    @Test
    void zero() {
        assertEquals("ul", NumeralConverter.integerToCardinal(0));
    }

    @Test
    void one() {
        assertEquals("vir", NumeralConverter.integerToCardinal(1));
    }

    @Test
    void five() {
        assertEquals("vraestmorocht", NumeralConverter.integerToCardinal(5));
    }

    @Test
    void six() {
        assertEquals("schest", NumeralConverter.integerToCardinal(6));
    }

    @Test
    void seven() {
        assertEquals("schest-vir", NumeralConverter.integerToCardinal(7));
    }

    @Test
    void twelve() {
        assertEquals("tesk-schest", NumeralConverter.integerToCardinal(12));
    }

    @Test
    void thirtySix() {
        assertEquals("schaer", NumeralConverter.integerToCardinal(36));
    }

    @Test
    void ninety() {
        assertEquals("tesk-schaer-moroch-schest", NumeralConverter.integerToCardinal(90));
    }

    @Test
    void hundred() {
        assertEquals("tesk-schaer-kelkargh-schest-kelkargh", NumeralConverter.integerToCardinal(100));
    }

    @Test
    void twoHundredSixteen() {
        assertEquals("schalmokt", NumeralConverter.integerToCardinal(216));
    }

    @Test
    void thousand() {
        assertEquals("kelkargh-schalmokt-moroch-schaer-kelkargh-schest-kelkargh",
                NumeralConverter.integerToCardinal(1000));
    }

    @Test
    void sevenThousandSevenHundredSeventySix() {
        assertEquals("schorruekt", NumeralConverter.integerToCardinal(7776));
    }

    @Test
    void fortySixSixFiftySix_pureDigitChain() {
        // 46,656 = 6^6 → highest place exceeds the unit-name family → pure digit-chain.
        assertEquals("vir-ul-ul-ul-ul-ul-ul", NumeralConverter.integerToCardinal(46656));
    }

    @Test
    void million_pureDigitChain() {
        assertEquals("moroch-moroch-tesk-moroch-moroch-moroch-kelkargh-kelkargh",
                NumeralConverter.integerToCardinal(1000000));
    }

    @Test
    void negativeGetsRevPrefix() {
        assertEquals("ont-schest-vir", NumeralConverter.integerToCardinal(-7));
    }

    @Test
    void testHolyNumberMatchesCanonicalForm() {
        String expected = "vraestmorocht-schest-kelkargh-skarn-moroch";
        String actual = NumeralConverter.decimalToSselith(34.5);
        assertEquals(expected, actual,
            "The holy number is gameplay-critical: if the algorithm now produces a different " +
            "string for 34.5, update the canonical form in the lore — don't patch the converter.");
    }

    @Test
    void halfTerminates() {
        assertEquals("ul-skarn-moroch", NumeralConverter.decimalToSselith(0.5));
    }

    @Test
    void onePointOneNonTerminating() {
        // 0.1 doesn't terminate in base-6 (it's 0.0(3)), so the 8-digit cap + -vrecht marker
        // apply. The addendum's table is wrong here; algorithm wins per "don't patch the converter".
        assertEquals("vir-skarn-ul-moroch-moroch-moroch-moroch-moroch-moroch-moroch-vrecht",
                NumeralConverter.decimalToSselith(1.1));
    }

    @Test
    void integerViaDecimalEntryPoint() {
        // Pure-integer tokens route through decimalToSselith with a zero fraction.
        assertEquals("vraestmorocht-schest-kelkargh", NumeralConverter.decimalToSselith(34.0));
    }
}
