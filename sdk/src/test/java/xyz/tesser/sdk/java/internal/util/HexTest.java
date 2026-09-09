package xyz.tesser.sdk.java.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HexTest {

    @Test
    void encodesHighBytesAsTwoLowercaseDigits() {
        // The signed-byte trap: 0x80..0xff are negative as Java bytes, and a naive
        // encoder renders them as "ffffff80" or drops the leading zero on 0x0f.
        byte[] bytes = {0x00, 0x0f, (byte) 0x80, (byte) 0xff, 0x7f};
        assertThat(Hex.encode(bytes)).isEqualTo("000f80ff7f");
    }

    @Test
    void encodeOfEmptyIsEmpty() {
        assertThat(Hex.encode(new byte[0])).isEmpty();
    }

    @Test
    void roundTripsEveryByteValue() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        assertThat(Hex.decode(Hex.encode(all))).isEqualTo(all);
    }

    @Test
    void decodeAcceptsBothCases() {
        assertThat(Hex.decode("DeAdBeEf")).isEqualTo(Hex.decode("deadbeef"));
    }

    @Test
    void decodeRejectsOddLength() {
        assertThatThrownBy(() -> Hex.decode("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even length");
    }

    @Test
    void decodeRejectsNonAsciiDigitsThatCharacterDigitWouldAccept() {
        // Character.digit returns 5 for U+0665 and 10 for U+FF21; the Kotlin SDK
        // rejects both, so a key it refuses must not be signable here.
        for (String bad : new String[] {"٥٥", "ＡＡ", "zz", "g0"}) {
            assertThatThrownBy(() -> Hex.decode(bad))
                    .as("must reject %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
