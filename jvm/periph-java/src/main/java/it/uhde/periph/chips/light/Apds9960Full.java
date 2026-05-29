package it.uhde.periph.chips.light;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * APDS-9960 — full driver. Extends {@link Apds9960Minimal} with proximity, gesture,
 * wait engine, threshold and interrupt configuration, status queries, and device identification.
 */
public class Apds9960Full extends Apds9960Minimal {

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the APDS-9960 device address (0x39)
     * @throws IOException on I²C error
     */
    public Apds9960Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Enable or disable the proximity engine.
     *
     * @param enabled true to enable PEN, false to disable
     * @throws IOException on I²C error
     */
    public void enableProximity(boolean enabled) throws IOException {
        int val = readReg(REG_ENABLE);
        if (enabled) val |= 0x04; else val &= ~0x04;
        writeReg(REG_ENABLE, val);
    }

    /**
     * Read the proximity count.
     *
     * @return proximity count 0-255; higher means closer
     * @throws IOException on I²C error
     */
    public int proximity() throws IOException {
        return readReg(REG_PDATA);
    }

    /**
     * Enable or disable the wait engine.
     *
     * @param enabled true to enable WEN, false to disable
     * @throws IOException on I²C error
     */
    public void enableWait(boolean enabled) throws IOException {
        int val = readReg(REG_ENABLE);
        if (enabled) val |= 0x08; else val &= ~0x08;
        writeReg(REG_ENABLE, val);
    }

    /**
     * Configure the wait time between ALS/proximity cycles.
     *
     * @param wtime WTIME register value 0-255
     * @param wlong true to enable WLONG 12x multiplier
     * @throws IOException on I²C error
     */
    public void configureWait(int wtime, boolean wlong) throws IOException {
        writeReg(REG_WTIME, wtime & 0xFF);
        int c1 = readReg(REG_CONFIG1);
        if (wlong) c1 |= 0x02; else c1 &= ~0x02;
        c1 = (c1 & 0x03) | 0x60;
        writeReg(REG_CONFIG1, c1);
    }

    /**
     * Configure ALS integration time and gain.
     *
     * @param atime ATIME register value 0-255
     * @param again ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x)
     * @throws IOException on I²C error
     */
    public void configureAls(int atime, int again) throws IOException {
        writeReg(REG_ATIME, atime & 0xFF);
        int ctrl = readReg(REG_CONTROL);
        ctrl = (ctrl & 0xFC) | (again & 0x03);
        writeReg(REG_CONTROL, ctrl);
    }

    /**
     * Configure proximity LED drive, gain, pulse count and length.
     *
     * @param ldrive LED drive strength 0-3
     * @param pgain  proximity gain 0-3
     * @param ppulse pulse count minus 1, 0-63
     * @param pplen  pulse length 0-3
     * @throws IOException on I²C error
     */
    public void configureProximityLed(int ldrive, int pgain, int ppulse, int pplen) throws IOException {
        int ctrl = readReg(REG_CONTROL);
        ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03);
        writeReg(REG_CONTROL, ctrl);
        writeReg(REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F));
    }

    /**
     * Set additional LED current boost.
     *
     * @param boost LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%)
     * @throws IOException on I²C error
     */
    public void setLedBoost(int boost) throws IOException {
        int c2 = readReg(REG_CONFIG2);
        c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01;
        writeReg(REG_CONFIG2, c2);
    }

    /**
     * Set ALS interrupt thresholds.
     *
     * @param low  low threshold 0-65535
     * @param high high threshold 0-65535
     * @throws IOException on I²C error
     */
    public void alsThreshold(int low, int high) throws IOException {
        writeReg(REG_AILTL, low & 0xFF);
        writeReg(REG_AILTH, (low >> 8) & 0xFF);
        writeReg(REG_AIHTL, high & 0xFF);
        writeReg(REG_AIHTH, (high >> 8) & 0xFF);
    }

    /**
     * Set proximity interrupt thresholds.
     *
     * @param low  low threshold 0-255
     * @param high high threshold 0-255
     * @throws IOException on I²C error
     */
    public void proximityThreshold(int low, int high) throws IOException {
        writeReg(REG_PILT, low & 0xFF);
        writeReg(REG_PIHT, high & 0xFF);
    }

    /**
     * Set interrupt persistence filters.
     *
     * @param ppers proximity persistence 0-15
     * @param apers ALS persistence 0-15
     * @throws IOException on I²C error
     */
    public void setPersistence(int ppers, int apers) throws IOException {
        writeReg(REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F));
    }

    /**
     * Enable or disable ALS interrupt.
     *
     * @param enabled true to enable AIEN, false to disable
     * @throws IOException on I²C error
     */
    public void enableAlsInterrupt(boolean enabled) throws IOException {
        int val = readReg(REG_ENABLE);
        if (enabled) val |= 0x10; else val &= ~0x10;
        writeReg(REG_ENABLE, val);
    }

    /**
     * Enable or disable proximity interrupt.
     *
     * @param enabled true to enable PIEN, false to disable
     * @throws IOException on I²C error
     */
    public void enableProximityInterrupt(boolean enabled) throws IOException {
        int val = readReg(REG_ENABLE);
        if (enabled) val |= 0x20; else val &= ~0x20;
        writeReg(REG_ENABLE, val);
    }

    /**
     * Clear the proximity interrupt via address-only write to PICLEAR.
     *
     * @throws IOException on I²C error
     */
    public void clearProximityInterrupt() throws IOException {
        transport.write(new byte[]{(byte) REG_PICLEAR});
    }

    /**
     * Clear the ALS/color interrupt via address-only write to CICLEAR.
     *
     * @throws IOException on I²C error
     */
    public void clearAlsInterrupt() throws IOException {
        transport.write(new byte[]{(byte) REG_CICLEAR});
    }

    /**
     * Clear all non-gesture interrupts via address-only write to AICLEAR.
     *
     * @throws IOException on I²C error
     */
    public void clearAllInterrupts() throws IOException {
        transport.write(new byte[]{(byte) REG_AICLEAR});
    }

    /**
     * Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes (sign-magnitude).
     *
     * @param ur UP/RIGHT offset -127 to +127
     * @param dl DOWN/LEFT offset -127 to +127
     * @throws IOException on I²C error
     */
    public void setProximityOffset(int ur, int dl) throws IOException {
        writeReg(REG_POFFSET_UR, encodeOffset(ur));
        writeReg(REG_POFFSET_DL, encodeOffset(dl));
    }

    /**
     * Mask individual photodiodes in proximity detection.
     *
     * @param u true to mask UP
     * @param d true to mask DOWN
     * @param l true to mask LEFT
     * @param r true to mask RIGHT
     * @throws IOException on I²C error
     */
    public void setProximityMask(boolean u, boolean d, boolean l, boolean r) throws IOException {
        int c3 = readReg(REG_CONFIG3) & 0xF0;
        if (u) c3 |= 0x08;
        if (d) c3 |= 0x04;
        if (l) c3 |= 0x02;
        if (r) c3 |= 0x01;
        writeReg(REG_CONFIG3, c3);
    }

    /**
     * Enable or disable the gesture engine.
     *
     * @param enabled true to enable GEN and set GMODE, false to disable
     * @throws IOException on I²C error
     */
    public void enableGesture(boolean enabled) throws IOException {
        int val = readReg(REG_ENABLE);
        if (enabled) {
            val |= 0x40;
            writeReg(REG_ENABLE, val);
            int g4 = readReg(REG_GCONF4);
            g4 |= 0x01;
            writeReg(REG_GCONF4, g4);
        } else {
            val &= ~0x40;
            writeReg(REG_ENABLE, val);
            int g4 = readReg(REG_GCONF4);
            g4 &= ~0x01;
            writeReg(REG_GCONF4, g4);
        }
    }

    /**
     * Configure gesture engine parameters.
     *
     * @param ggain   gesture gain 0-3
     * @param gldrive gesture LED drive 0-3
     * @param gpulse  gesture pulse count minus 1, 0-63
     * @param gplen   gesture pulse length 0-3
     * @param gwtime  gesture wait time 0-7
     * @param gpenth  gesture proximity entry threshold 0-255
     * @param gexth   gesture exit threshold 0-255
     * @throws IOException on I²C error
     */
    public void configureGesture(int ggain, int gldrive, int gpulse, int gplen, int gwtime, int gpenth, int gexth) throws IOException {
        writeReg(REG_GPENTH, gpenth & 0xFF);
        writeReg(REG_GEXTH, gexth & 0xFF);
        int g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07);
        writeReg(REG_GCONF2, g2);
        writeReg(REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F));
    }

    /**
     * Check if gesture data is available in the FIFO.
     *
     * @return true if GSTATUS.GVALID is set
     * @throws IOException on I²C error
     */
    public boolean gestureAvailable() throws IOException {
        return (readReg(REG_GSTATUS) & 0x01) != 0;
    }

    /**
     * Read gesture datasets from the FIFO.
     *
     * @return array of [U, D, L, R] arrays, one per dataset
     * @throws IOException on I²C error
     */
    public int[][] readGestureFifo() throws IOException {
        int level = readReg(REG_GFLVL);
        if (level == 0) return new int[0][];
        int[][] result = new int[level][4];
        for (int i = 0; i < level; i++) {
            byte[] raw = transport.writeRead(new byte[]{(byte) REG_GFIFO_U}, 4);
            result[i] = new int[]{raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF};
        }
        return result;
    }

    /**
     * Read the number of datasets in the gesture FIFO.
     *
     * @return number of 4-byte datasets currently in FIFO
     * @throws IOException on I²C error
     */
    public int gestureFifoLevel() throws IOException {
        return readReg(REG_GFLVL);
    }

    /**
     * Clear the gesture FIFO by setting GFIFO_CLR in GCONF4.
     *
     * @throws IOException on I²C error
     */
    public void clearGestureFifo() throws IOException {
        int g4 = readReg(REG_GCONF4);
        g4 |= 0x04;
        writeReg(REG_GCONF4, g4);
    }

    /**
     * Enable or disable gesture interrupt.
     *
     * @param enabled true to enable GIEN, false to disable
     * @throws IOException on I²C error
     */
    public void enableGestureInterrupt(boolean enabled) throws IOException {
        int g4 = readReg(REG_GCONF4);
        if (enabled) g4 |= 0x02; else g4 &= ~0x02;
        writeReg(REG_GCONF4, g4);
    }

    /**
     * Read the raw STATUS register.
     *
     * @return raw STATUS byte
     * @throws IOException on I²C error
     */
    public int status() throws IOException {
        return readReg(REG_STATUS);
    }

    /**
     * Check if ALS/color data is valid.
     *
     * @return true if STATUS.AVALID is set
     * @throws IOException on I²C error
     */
    public boolean isAlsValid() throws IOException {
        return (readReg(REG_STATUS) & 0x01) != 0;
    }

    /**
     * Check if proximity data is valid.
     *
     * @return true if STATUS.PVALID is set
     * @throws IOException on I²C error
     */
    public boolean isProximityValid() throws IOException {
        return (readReg(REG_STATUS) & 0x02) != 0;
    }

    /**
     * Check if the clear photodiode is saturated.
     *
     * @return true if STATUS.CPSAT is set
     * @throws IOException on I²C error
     */
    public boolean isAlsSaturated() throws IOException {
        return (readReg(REG_STATUS) & 0x80) != 0;
    }

    /**
     * Check if analog saturation occurred during proximity.
     *
     * @return true if STATUS.PGSAT is set
     * @throws IOException on I²C error
     */
    public boolean isProximitySaturated() throws IOException {
        return (readReg(REG_STATUS) & 0x40) != 0;
    }

    /**
     * Read the device ID register.
     *
     * @return ID register value (expect 0xAB)
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        return readReg(REG_ID);
    }

    private static int encodeOffset(int value) {
        if (value < 0) return 0x80 | ((-value) & 0x7F);
        return value & 0x7F;
    }
}
