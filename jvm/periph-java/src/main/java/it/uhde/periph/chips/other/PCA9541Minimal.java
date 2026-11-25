package it.uhde.periph.chips.other;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * PCA9541 — 2-to-1 I²C-bus master selector (NXP) — minimal interface.
 *
 * <p>Two upstream masters share one downstream bus of slave devices through the
 * PCA9541's pass gates; this driver is used by <b>one</b> of the two masters (the
 * same software runs on both). There is no arbitration: whichever master writes
 * its CONTROL register last before a STOP wins. Each master sees its own copy of
 * the IE, CONTROL and ISTAT registers behind the same addresses. The 7-bit I²C
 * address is 0x70 + A (A3..A0); prefer 0x70–0x77.
 *
 * <p>The constructor reads CONTROL as a bus check (throws if the chip does not
 * acknowledge) and ISTAT once to clear stale flags; it writes nothing, so
 * initialising a master never changes the connection state. The CONTROL byte to
 * write depends on the read-only complement bits, so every change reads CONTROL
 * first and applies the datasheet's Table 12 rule.
 */
public class PCA9541Minimal {

    /** 7-bit I²C address with A3..A0 = 0. */
    public static final int DEFAULT_ADDRESS = 0x70;

    protected static final int REG_IE = 0x00;
    protected static final int REG_CONTROL = 0x01;
    protected static final int REG_ISTAT = 0x02;
    protected static final int AI = 0x10;

    protected static final int C_MYBUS = 0x01;
    protected static final int C_NMYBUS = 0x02;
    protected static final int C_BUSON = 0x04;
    protected static final int C_NBUSON = 0x08;
    protected static final int C_BUSINIT = 0x10;
    protected static final int C_TESTON = 0x40;
    protected static final int C_NTESTON = 0x80;

    protected final RegisterConnection connection;

    /**
     * Construct the driver and run the initialization sequence (CONTROL read as a
     * bus check, ISTAT read); nothing is written.
     *
     * @param connection I²C register connection bound to the device (0x70–0x77)
     * @throws IOException on bus error, including a chip that does not acknowledge
     */
    public PCA9541Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        readReg(REG_CONTROL);
        readReg(REG_ISTAT);
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected static boolean hasControlBits(int c) {
        return ((c & C_MYBUS) != 0) == ((c & C_NMYBUS) != 0);
    }

    protected static boolean connectedBits(int c) {
        return ((c & C_BUSON) != 0) != ((c & C_NBUSON) != 0);
    }

    protected static int nbuson(int c) {
        return (c & C_NBUSON) != 0 ? 1 : 0;
    }

    protected static int nmybus(int c) {
        return (c & C_NMYBUS) != 0 ? 1 : 0;
    }

    /**
     * Report whether this master's channel is connected to the downstream bus.
     *
     * @return true if MYBUS equals NMYBUS and the bus is connected (BUSON differs from NBUSON)
     * @throws IOException on bus error
     */
    public boolean hasControl() throws IOException {
        int c = readReg(REG_CONTROL);
        return hasControlBits(c) && connectedBits(c);
    }

    /**
     * Take the downstream bus. Reads CONTROL and writes MYBUS / BUSON per the
     * datasheet rule; the write ends with a STOP, which makes the switch. With
     * {@code busInit} the chip first sends nine clocks, a NACK and a STOP on the
     * downstream bus and flags BUSINIT in ISTAT.
     *
     * @param busInit   true to request bus initialization / recovery
     * @param timeoutMs maximum time to wait for the switch, in milliseconds
     * @return true if this master has control afterwards, false if not within {@code timeoutMs}
     * @throws IOException on bus error
     */
    public boolean acquire(boolean busInit, int timeoutMs) throws IOException {
        int c = readReg(REG_CONTROL);
        int w = ((1 - nbuson(c)) << 2) | nmybus(c);
        if (busInit) w |= C_BUSINIT;
        writeReg(REG_CONTROL, w);
        for (int i = 0; i <= timeoutMs; i++) {
            if (busInit && (readReg(REG_ISTAT) & 0x02) == 0) {
                sleepMs(1);
                continue;
            }
            if (hasControl()) return true;
            sleepMs(1);
        }
        return false;
    }

    /**
     * Take the downstream bus without bus initialization, waiting up to 20 ms.
     *
     * @return true if this master has control afterwards
     * @throws IOException on bus error
     */
    public boolean acquire() throws IOException {
        return acquire(false, 20);
    }

    /**
     * Give the downstream bus to the other master (MYBUS = NOT NMYBUS; the bus stays connected).
     *
     * @throws IOException on bus error
     */
    public void release() throws IOException {
        int c = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (1 - nmybus(c)));
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
