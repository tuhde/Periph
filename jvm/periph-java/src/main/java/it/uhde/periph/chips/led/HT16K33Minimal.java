package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HT16K33 — 16 x 8 LED matrix driver (minimal driver).
 *
 * <p>Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM that
 * is shadowed in the driver. I2C address 0x70 (A0-A2 select 0x70-0x77).
 *
 * <p>Defaults baked in at construction: oscillator on, ROW15 pin as a ROW
 * output, brightness 16/16, blink off, RAM cleared, display on.
 */
public class HT16K33Minimal {

    public static final int COMMONS = 8;
    public static final int ROWS    = 16;

    protected static final int CMD_SYSTEM  = 0x20;
    protected static final int CMD_DISPLAY = 0x80;
    protected static final int CMD_DIMMING = 0xE0;
    protected static final int CMD_KEY_PTR = 0x40;
    protected static final int CMD_INT_PTR = 0x60;

    protected final Connection connection;
    protected final byte[] ram = new byte[16];
    protected int brightness = 15;
    protected int blink = 0;
    protected boolean display = true;
    protected int rowInt = 0xA0;

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I2C connection bound to the HT16K33 address
     * @throws IOException on I2C error
     */
    public HT16K33Minimal(Connection connection) throws IOException {
        this.connection = connection;
        command(CMD_SYSTEM | 0x01);
        command(rowInt);
        command(CMD_DIMMING | brightness);
        show();
        applyDisplay();
    }

    protected void command(int b) throws IOException {
        connection.write(new byte[]{(byte) b});
    }

    protected void applyDisplay() throws IOException {
        command(CMD_DISPLAY | (blink << 1) | (display ? 1 : 0));
    }

    /**
     * Zero the shadow RAM and write it to the chip.
     *
     * @throws IOException on I2C error
     */
    public void clear() throws IOException {
        java.util.Arrays.fill(ram, (byte) 0);
        show();
    }

    /**
     * Set or clear one LED in the shadow RAM (does not write to the chip).
     *
     * @param com COM line, 0-7
     * @param row ROW line, 0-15
     * @param on  true to light the LED
     * @throws IllegalArgumentException if com or row is out of range
     */
    public void setPixel(int com, int row, boolean on) {
        if (com < 0 || com > 7) throw new IllegalArgumentException("com must be in 0..7");
        if (row < 0 || row > 15) throw new IllegalArgumentException("row must be in 0..15");
        int idx = 2 * com + (row >> 3);
        int mask = 1 << (row & 7);
        if (on) ram[idx] |= (byte) mask;
        else ram[idx] &= (byte) ~mask;
    }

    /**
     * Light one LED in the shadow RAM (does not write to the chip).
     *
     * @param com COM line, 0-7
     * @param row ROW line, 0-15
     */
    public void setPixel(int com, int row) {
        setPixel(com, row, true);
    }

    /**
     * Write raw RAM bytes at {@code address} and update the shadow RAM.
     *
     * @param data    1-16 bytes of display data
     * @param address start RAM address, 0-15
     * @throws IllegalArgumentException if data is empty or address + length exceeds 16
     * @throws IOException on I2C error
     */
    public void writeRaw(byte[] data, int address) throws IOException {
        if (data.length < 1 || address < 0 || address + data.length > 16) {
            throw new IllegalArgumentException("address + data.length must be within 0..16");
        }
        System.arraycopy(data, 0, ram, address, data.length);
        byte[] buf = new byte[1 + data.length];
        buf[0] = (byte) address;
        System.arraycopy(data, 0, buf, 1, data.length);
        connection.write(buf);
    }

    /**
     * Write the whole 16-byte shadow RAM to the chip.
     *
     * @throws IOException on I2C error
     */
    public void show() throws IOException {
        byte[] buf = new byte[17];
        System.arraycopy(ram, 0, buf, 1, 16);
        connection.write(buf);
    }

    /**
     * Set the dimming level.
     *
     * @param level 0-15, giving (level + 1)/16 duty
     * @throws IllegalArgumentException if level is out of range
     * @throws IOException on I2C error
     */
    public void setBrightness(int level) throws IOException {
        if (level < 0 || level > 15) throw new IllegalArgumentException("level must be in 0..15");
        brightness = level;
        command(CMD_DIMMING | level);
    }
}
