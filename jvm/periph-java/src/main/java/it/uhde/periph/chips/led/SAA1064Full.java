package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

import java.io.IOException;
import java.util.Map;

/**
 * SAA1064 — 4-digit LED driver (full driver).
 *
 * <p>Extends {@link SAA1064Minimal} with mode, digit-pair blanking, segment
 * current, segment test, segment mapping, per-digit decimal points and the
 * status byte.
 */
public class SAA1064Full extends SAA1064Minimal {

    /**
     * Construct the driver (same initialization as the minimal driver).
     *
     * @param connection I2C connection bound to the SAA1064 address
     * @throws IOException on I2C error
     */
    public SAA1064Full(Connection connection) throws IOException {
        super(connection);
    }

    private void writeControlByte() throws IOException {
        connection.write(new byte[]{(byte) SUB_CONTROL, (byte) control});
    }

    private void setBit(int mask, boolean on) throws IOException {
        if (on) control |= mask; else control &= ~mask & 0x7F;
        writeControlByte();
    }

    /**
     * Select dynamic (all four digits) or static (digits 1 and 2) mode.
     *
     * @param dynamic true for dynamic mode
     * @throws IOException on I2C error
     */
    public void setMode(boolean dynamic) throws IOException {
        setBit(C_DYNAMIC, dynamic);
    }

    /**
     * Blank digit pairs.
     *
     * @param digits13 true to blank digits 1 and 3
     * @param digits24 true to blank digits 2 and 4
     * @throws IOException on I2C error
     */
    public void blank(boolean digits13, boolean digits24) throws IOException {
        control = digits13 ? control & ~C_UNBLANK_13 : control | C_UNBLANK_13;
        control = digits24 ? control & ~C_UNBLANK_24 : control | C_UNBLANK_24;
        control &= 0x7F;
        writeControlByte();
    }

    /**
     * Set the segment output current.
     *
     * @param ma 0, 3, 6, 9, 12, 15, 18 or 21 (mA)
     * @throws IllegalArgumentException for any other value
     * @throws IOException on I2C error
     */
    public void setCurrent(int ma) throws IOException {
        if (ma < 0 || ma > 21 || ma % 3 != 0) throw new IllegalArgumentException("ma must be one of 0, 3, 6, 9, 12, 15, 18, 21");
        control = (control & ~C_CURRENT & 0x7F) | ((ma / 3) << 4);
        writeControlByte();
    }

    /**
     * Switch all segment outputs on (segment test).
     *
     * @param on true to start the test
     * @throws IOException on I2C error
     */
    public void segmentTest(boolean on) throws IOException {
        setBit(C_TEST, on);
    }

    /**
     * Remap the helper's segment bits to the module wiring.
     *
     * @param mapping bit numbers 0-7 for the keys "a" to "g" and "dp"
     * @throws IllegalArgumentException if a key is missing or a bit number is out of range
     */
    public void setSegmentMap(Map<String, Integer> mapping) {
        String[] keys = {"a", "b", "c", "d", "e", "f", "g", "dp"};
        int[] m = new int[8];
        for (int i = 0; i < 8; i++) {
            Integer v = mapping.get(keys[i]);
            if (v == null) throw new IllegalArgumentException("mapping needs keys a-g and dp");
            if (v < 0 || v > 7) throw new IllegalArgumentException("bit numbers must be in 0..7");
            m[i] = v;
        }
        System.arraycopy(m, 0, segMap, 0, 8);
        rebuildGlyphs();
    }

    /**
     * Show one digit with an optional decimal point.
     *
     * @param position digit 1-4
     * @param value    0-9 or {@link #BLANK}
     * @param dp       true to light the decimal point
     * @throws IOException on I2C error
     */
    public void showDigit(int position, int value, boolean dp) throws IOException {
        if (position < 1 || position > DIGITS) throw new IllegalArgumentException("position must be in 1..4");
        int p = pattern(value);
        if (dp) p |= 1 << segMap[7];
        patterns[position - 1] = p;
        connection.write(new byte[]{(byte) position, (byte) p});
    }

    /**
     * Show one digit without a decimal point.
     *
     * @param position digit 1-4
     * @param value    0-9 or {@link #BLANK}
     * @throws IOException on I2C error
     */
    public void showDigit(int position, int value) throws IOException {
        showDigit(position, value, false);
    }

    /**
     * Switch a decimal point, keeping the cached segments.
     *
     * @param position digit 1-4
     * @param on       true to light the decimal point
     * @throws IOException on I2C error
     */
    public void setDecimalPoint(int position, boolean on) throws IOException {
        if (position < 1 || position > DIGITS) throw new IllegalArgumentException("position must be in 1..4");
        int bit = 1 << segMap[7];
        if (on) patterns[position - 1] |= bit; else patterns[position - 1] &= ~bit & 0xFF;
        connection.write(new byte[]{(byte) position, (byte) patterns[position - 1]});
    }

    /**
     * Read the status byte (bit 7 = PR power-reset flag, cleared by the read).
     *
     * @return the status byte, 0-255
     * @throws IOException on I2C error
     */
    public int readStatus() throws IOException {
        return connection.read(1)[0] & 0xFF;
    }

    /**
     * Read the status byte and return its PR flag.
     *
     * @return true if a power reset occurred since the status was last read
     * @throws IOException on I2C error
     */
    public boolean powerResetFlag() throws IOException {
        return (readStatus() & 0x80) != 0;
    }

    /**
     * Write a raw control byte (bit 7 forced to 0).
     *
     * @param value control byte 0-127
     * @throws IOException on I2C error
     */
    public void writeControl(int value) throws IOException {
        control = value & 0x7F;
        writeControlByte();
    }
}
