package jcma.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The {@link SourceSet} tag and its packing into {@link Symbol#flags()} (bit 0). MAIN is the zero
 * value, so an untagged symbol (legacy {@code flags == 0}) reads back as MAIN.
 */
class SourceSetTest {

    @Test
    void zeroFlagsIsMain() {
        assertEquals(SourceSet.MAIN, SourceSet.of(0), "flags 0 (the default) decodes to MAIN");
    }

    @Test
    void bitZeroIsTest() {
        assertEquals(SourceSet.TEST, SourceSet.of(1), "bit 0 set decodes to TEST");
    }

    @Test
    void flagBitsRoundTrip() {
        for (SourceSet set : SourceSet.values()) {
            assertEquals(set, SourceSet.of(SourceSet.flagBits(set)), "round-trips through flagBits");
        }
    }

    @Test
    void mainEncodesToZeroSoOtherBitsStayFree() {
        assertEquals(0, SourceSet.flagBits(SourceSet.MAIN), "MAIN is the zero encoding");
    }

    @Test
    void symbolDecodesItsSourceSetFromFlags() {
        Symbol main = new Symbol("a/A#", SymbolKind.CLASS, SourceSet.flagBits(SourceSet.MAIN),
                null, 0, Range.NONE, "A", "a.A");
        Symbol test = new Symbol("a/B#", SymbolKind.CLASS, SourceSet.flagBits(SourceSet.TEST),
                null, 0, Range.NONE, "B", "a.B");
        assertEquals(SourceSet.MAIN, main.sourceSet());
        assertEquals(SourceSet.TEST, test.sourceSet());
    }
}
