package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * SAA1064 — 4-digit LED driver (minimal driver).
 *
 * <p>Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
 * decimal points. I2C address 0x38-0x3B, selected by the voltage on the ADR pin.
 *
 * <p>Defaults baked in at construction: control byte 0x47 (dynamic mode, all
 * digits unblanked, 12 mA segment current), digits blank, default segment
 * mapping a = bit 0 ... g = bit 6, dp = bit 7.
 */
@CompileStatic
class SAA1064Minimal {

    static final int DIGITS = 4
    /** Value that blanks a digit in {@link #showDigits(int[])}. */
    static final int BLANK = -1

    static final int SUB_CONTROL  = 0x00
    static final int C_DYNAMIC    = 0x01
    static final int C_UNBLANK_13 = 0x02
    static final int C_UNBLANK_24 = 0x04
    static final int C_TEST       = 0x08
    static final int C_CURRENT    = 0x70

    private static final String[] SEGMENTS = [
        'abcdef', 'bc', 'abdeg', 'abcdg', 'bcfg', 'acdfg', 'acdefg', 'abc', 'abcdefg', 'abcdfg'
    ] as String[]
    private static final String KEYS = 'abcdefg'

    protected final Connection connection
    protected int control = 0x47
    protected final int[] patterns = new int[4]
    protected final int[] segMap = [0, 1, 2, 3, 4, 5, 6, 7] as int[]
    private final int[] glyphs = new int[10]

    /**
     * Construct the driver, write control 0x47 and blank the digits.
     *
     * @param connection I2C connection bound to the SAA1064 address
     */
    SAA1064Minimal(Connection connection) throws IOException {
        this.connection = connection
        rebuildGlyphs()
        connection.write([(byte) SUB_CONTROL, (byte) control, (byte) 0, (byte) 0, (byte) 0, (byte) 0] as byte[])
    }

    protected final void rebuildGlyphs() {
        for (int v = 0; v < 10; v++) {
            int p = 0
            String segs = SEGMENTS[v]
            for (int i = 0; i < segs.length(); i++) {
                p |= 1 << segMap[KEYS.indexOf(segs.substring(i, i + 1))]
            }
            glyphs[v] = p
        }
    }

    protected final int pattern(int value) {
        if (value == BLANK) return 0
        if (value < 0 || value > 9) throw new IllegalArgumentException('digit must be 0-9 or BLANK')
        return glyphs[value]
    }

    protected final void writeDigits(int first) throws IOException {
        int n = DIGITS - first + 1
        byte[] buf = new byte[1 + n]
        buf[0] = (byte) first
        for (int i = 0; i < n; i++) buf[1 + i] = (byte) patterns[first - 1 + i]
        connection.write(buf)
    }

    /**
     * Show four digits using the segment mapping.
     *
     * @param digits four values, each 0-9 or {@link #BLANK}
     */
    void showDigits(int[] digits) throws IOException {
        if (digits.length != DIGITS) throw new IllegalArgumentException('digits must have 4 values')
        int[] p = new int[4]
        for (int i = 0; i < 4; i++) p[i] = pattern(digits[i])
        for (int i = 0; i < 4; i++) patterns[i] = p[i]
        writeDigits(1)
    }

    /**
     * Show a right-aligned decimal number with leading zeros blanked.
     *
     * @param value 0-9999
     */
    void showNumber(int value) throws IOException {
        if (value < 0 || value > 9999) throw new IllegalArgumentException('value must be in 0..9999')
        int[] digits = [BLANK, BLANK, BLANK, value % 10] as int[]
        int v = value.intdiv(10)
        for (int i = 2; i >= 0; i--) {
            if (v > 0) { digits[i] = v % 10; v = v.intdiv(10) }
        }
        showDigits(digits)
    }

    /** Blank all four digits. */
    void clear() throws IOException {
        for (int i = 0; i < 4; i++) patterns[i] = 0
        writeDigits(1)
    }

    /**
     * Write raw segment bytes starting at a digit.
     *
     * @param raw        1-4 segment bytes (bit 0 = P1/P9 ... bit 7 = P8/P16)
     * @param firstDigit first digit to write, 1-4
     */
    void writeRaw(int[] raw, int firstDigit) throws IOException {
        if (firstDigit < 1 || firstDigit > DIGITS) throw new IllegalArgumentException('firstDigit must be in 1..4')
        if (raw.length < 1 || firstDigit - 1 + raw.length > DIGITS) throw new IllegalArgumentException('raw must fit within digits 1..4')
        byte[] buf = new byte[1 + raw.length]
        buf[0] = (byte) firstDigit
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] < 0 || raw[i] > 255) throw new IllegalArgumentException('pattern must be 0..255')
            patterns[firstDigit - 1 + i] = raw[i]
            buf[1 + i] = (byte) raw[i]
        }
        connection.write(buf)
    }

    /**
     * Write raw segment bytes starting at digit 1.
     *
     * @param raw 1-4 segment bytes
     */
    void writeRaw(int[] raw) throws IOException {
        writeRaw(raw, 1)
    }
}
