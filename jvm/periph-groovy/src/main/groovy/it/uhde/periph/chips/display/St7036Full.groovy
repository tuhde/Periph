package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * ST7036 character LCD controller — full driver.
 *
 * <p>Extends {@link St7036Minimal} with the DOGM081 / DOGM162 / DOGM163 modules at
 * 3.3 V or 5 V, display control (on/off, cursor, blink, entry mode, shift), contrast,
 * booster, follower and bias, custom characters (CGRAM), double height, ICON RAM and
 * raw instructions.
 */
@CompileStatic
class St7036Full extends St7036Minimal {

    /** Supply voltage of the module: 3.3 V (booster on) or 5 V (booster off). */
    static final int SUPPLY_3V3 = 0
    static final int SUPPLY_5V = 1

    // func, bias, power, follower, contrast low for module (1, 2, 3 lines) and supply: index (lines - 1) * 10 + supply * 5
    private static final int[] PRESETS = [
        0x31, 0x14, 0x55, 0x6D, 0x7C,  0x31, 0x1C, 0x51, 0x6A, 0x74,   // DOGM081 3.3 V, 5 V
        0x39, 0x14, 0x55, 0x6D, 0x78,  0x39, 0x1C, 0x52, 0x69, 0x74,   // DOGM162
        0x39, 0x15, 0x55, 0x6E, 0x72,  0x39, 0x1D, 0x50, 0x6C, 0x7C,   // DOGM163
    ] as int[]

    /** Module configuration array for the {@link St7036Minimal} constructor. */
    private static int[] preset(int lines, int columns, int supply, int contrast) {
        if (lines < 1 || lines > 3) throw new IllegalArgumentException('lines must be 1, 2 or 3')
        int o = (lines - 1) * 10 + (supply == SUPPLY_5V ? 5 : 0)
        int func = PRESETS[o]
        int bias = PRESETS[o + 1]
        int power = PRESETS[o + 2]
        int follower = PRESETS[o + 3]
        int low = PRESETS[o + 4]
        int c = ((power & 3) << 4) | (low & 0x0F)
        if (contrast >= 0) {
            c = contrast & 0x3F
            power = (power & ~3) | ((c >> 4) & 3)
            low = CMD_CONTRAST | (c & 0x0F)
        }
        int cols = columns > 0 ? columns : (lines == 1 ? 8 : 16)
        [lines, cols, (func >> 3) & 1, (power >> 2) & 1, c, follower & 7,
         (bias >> 3) & 1, bias & 1, func, bias, power, follower, low] as int[]
    }

    /**
     * Construct the driver for a DOG module and run its initialisation sequence.
     *
     * @param connection display connection (no BUSY pin)
     * @param lines      1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16)
     * @param columns    characters per line, or 0 for the module default (8 or 16)
     * @param supply     {@link #SUPPLY_3V3} (booster on) or {@link #SUPPLY_5V}
     * @param contrast   contrast C5..C0 0..63, or -1 for the data sheet preset of the module
     * @throws IllegalArgumentException if {@code lines} is not 1, 2 or 3
     */
    St7036Full(DisplayConn connection, int lines, int columns, int supply, int contrast) {
        super(connection, preset(lines, columns, supply, contrast))
    }

    /**
     * Construct the driver for the DOGM162 (2 x 16) at 3.3 V with the data sheet contrast.
     *
     * @param connection display connection (no BUSY pin)
     */
    St7036Full(DisplayConn connection) {
        this(connection, 2, 0, SUPPLY_3V3, -1)
    }

    // ---- display control ---------------------------------------------------

    private void setDisplayBit(int mask, boolean on) {
        display = on ? (display | mask) : (display & ~mask)
        display = (display & 0x07) | CMD_DISPLAY
        instr(display)
    }

    /**
     * Turn the display on or off (the DDRAM content is kept).
     *
     * @param on {@code true} = display on
     */
    void display(boolean on) { setDisplayBit(0x04, on) }

    /**
     * Show or hide the cursor and its blinking block.
     *
     * @param on    underline cursor
     * @param blink blinking character block
     */
    void cursor(boolean on, boolean blink) {
        setDisplayBit(0x02, on)
        setDisplayBit(0x01, blink)
    }

    /**
     * Set the entry mode: cursor direction and display shift on write.
     *
     * @param increment {@code true} = cursor moves right (address + 1), {@code false} = left
     * @param shift     {@code true} = shift the display on every write
     */
    void setEntryMode(boolean increment, boolean shift) {
        entry = CMD_ENTRY | (increment ? 2 : 0) | (shift ? 1 : 0)
        instr(entry)
    }

    /**
     * Shift the cursor one position.
     *
     * @param right {@code true} = right, {@code false} = left
     */
    void shiftCursor(boolean right) { instr(CMD_SHIFT | (right ? 0x04 : 0)) }

    /**
     * Shift the whole display one position (the cursor follows).
     *
     * @param right {@code true} = right, {@code false} = left
     */
    void shiftDisplay(boolean right) { instr(CMD_SHIFT | 0x08 | (right ? 0x04 : 0)) }

    /** Return the cursor and a shifted display to the home position (Return Home, 2 ms). */
    void returnHome() { home() }

    /**
     * Set a raw DDRAM address (for use with display shift).
     *
     * @param address DDRAM address 0..0x7F
     */
    void setDdramAddress(int address) { setDdram(address) }

    // ---- contrast and supply -----------------------------------------------

    private void extBegin() { instr(function(1)) }

    private void extEnd() { instr(function(0)) }

    /**
     * Set the contrast C5..C0.
     *
     * @param value contrast 0..63
     */
    void setContrast(int value) {
        this.@contrast = value & 0x3F  // not 'contrast = ...': inside setContrast() that would call itself
        extBegin()
        instr(powerByte())
        instr(CMD_CONTRAST | (contrast & 0x0F))
        extEnd()
    }

    /**
     * Switch the voltage booster on or off (needed at 3.3 V).
     *
     * @param on {@code true} = booster on
     */
    void setBooster(boolean on) {
        bon = on ? 1 : 0
        extBegin()
        instr(powerByte())
        extEnd()
    }

    /**
     * Set the voltage follower and the V0 amplification.
     *
     * @param on    {@code true} = follower on
     * @param ratio Rab2..Rab0 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75
     */
    void setFollower(boolean on, int ratio) {
        fon = on ? 1 : 0
        rab = ratio & 7
        extBegin()
        instr(CMD_FOLLOWER | (fon << 3) | rab)
        extEnd()
    }

    /**
     * Set the LCD bias: 1/5 (default) or 1/4. FX follows the line count.
     *
     * @param quarter {@code true} = 1/4 bias, {@code false} = 1/5 bias
     */
    void setBias(boolean quarter) {
        this.quarter = quarter
        extBegin()
        instr(CMD_BIAS | (quarter ? 8 : 0) | fx)
        extEnd()
    }

    // ---- custom characters and special modes -------------------------------

    /**
     * Define a user character (CGRAM) and restore the DDRAM address.
     *
     * @param index character code 0..7
     * @param rows  8 row patterns, 5 bits each (bit 4 = leftmost pixel)
     */
    void createChar(int index, int[] rows) {
        int saved = addr
        instr(CMD_CGRAM | ((index & 7) << 3))
        for (int i = 0; i < 8; i++) data(rows[i] & 0x1F)
        setDdram(saved)
    }

    /**
     * Double-height font (1-line module: 5 x 16 font; 3-line module: top or bottom pair of
     * lines). Ignored on the 2-line module (the combination is forbidden by the data sheet).
     *
     * @param on    double height on
     * @param upper 3-line module only: {@code true} = COM1..16 (UD = 1), {@code false} = COM9..24
     */
    void setDoubleHeight(boolean on, boolean upper) {
        if (lines == 2) return
        dh = on ? 1 : 0
        instr(function(lines == 1 ? 0 : 2))
        if (lines == 3) instr(CMD_DHPOS | (upper ? 8 : 0))
        instr(function(0))
    }

    /**
     * Turn the ICON display on or off (no icons are wired on the DOG modules).
     *
     * @param on {@code true} = ICON display on
     */
    void icon(boolean on) {
        ion = on ? 1 : 0
        extBegin()
        instr(powerByte())
        extEnd()
    }

    /**
     * Write one ICON RAM byte.
     *
     * @param address ICON RAM address 0..15
     * @param value   byte to store
     */
    void writeIcon(int address, int value) {
        int saved = addr
        extBegin()
        instr(CMD_ICONADDR | (address & 0x0F))
        data(value)
        extEnd()
        setDdram(saved)
    }

    /**
     * Send any instruction byte (RS low) and wait 30 us.
     *
     * @param instruction instruction byte
     */
    void sendCommand(int instruction) { instr(instruction & 0xFF) }

    /**
     * Send any data byte (RS high) and wait 30 us.
     *
     * @param value data byte
     */
    void sendData(int value) { writeChar(value) }
}
