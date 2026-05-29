package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * APDS-9960 — full driver. Extends {@link Apds9960Minimal} with proximity, gesture,
 * wait engine, threshold and interrupt configuration, status queries, and device identification.
 */
@CompileStatic
class Apds9960Full extends Apds9960Minimal {

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the APDS-9960 device address (0x39)
     */
    Apds9960Full(Transport transport) {
        super(transport)
    }

    /**
     * Enable or disable the proximity engine.
     *
     * @param enabled true to enable PEN, false to disable
     */
    void enableProximity(boolean enabled) {
        int val = readReg(REG_ENABLE)
        if (enabled) val |= 0x04; else val &= ~0x04
        writeReg(REG_ENABLE, val)
    }

    /**
     * Read the proximity count.
     *
     * @return proximity count 0-255; higher means closer
     */
    int proximity() {
        readReg(REG_PDATA)
    }

    /**
     * Enable or disable the wait engine.
     *
     * @param enabled true to enable WEN, false to disable
     */
    void enableWait(boolean enabled) {
        int val = readReg(REG_ENABLE)
        if (enabled) val |= 0x08; else val &= ~0x08
        writeReg(REG_ENABLE, val)
    }

    /**
     * Configure the wait time between ALS/proximity cycles.
     *
     * @param wtime WTIME register value 0-255
     * @param wlong true to enable WLONG 12x multiplier
     */
    void configureWait(int wtime, boolean wlong = false) {
        writeReg(REG_WTIME, wtime & 0xFF)
        int c1 = readReg(REG_CONFIG1)
        if (wlong) c1 |= 0x02; else c1 &= ~0x02
        c1 = (c1 & 0x03) | 0x60
        writeReg(REG_CONFIG1, c1)
    }

    /**
     * Configure ALS integration time and gain.
     *
     * @param atime ATIME register value 0-255
     * @param again ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x)
     */
    void configureAls(int atime, int again) {
        writeReg(REG_ATIME, atime & 0xFF)
        int ctrl = readReg(REG_CONTROL)
        ctrl = (ctrl & 0xFC) | (again & 0x03)
        writeReg(REG_CONTROL, ctrl)
    }

    /**
     * Configure proximity LED drive, gain, pulse count and length.
     */
    void configureProximityLed(int ldrive, int pgain, int ppulse, int pplen) {
        int ctrl = readReg(REG_CONTROL)
        ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03)
        writeReg(REG_CONTROL, ctrl)
        writeReg(REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F))
    }

    /**
     * Set additional LED current boost.
     *
     * @param boost LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%)
     */
    void setLedBoost(int boost) {
        int c2 = readReg(REG_CONFIG2)
        c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01
        writeReg(REG_CONFIG2, c2)
    }

    /**
     * Set ALS interrupt thresholds.
     */
    void alsThreshold(int low, int high) {
        writeReg(REG_AILTL, low & 0xFF)
        writeReg(REG_AILTH, (low >> 8) & 0xFF)
        writeReg(REG_AIHTL, high & 0xFF)
        writeReg(REG_AIHTH, (high >> 8) & 0xFF)
    }

    /**
     * Set proximity interrupt thresholds.
     */
    void proximityThreshold(int low, int high) {
        writeReg(REG_PILT, low & 0xFF)
        writeReg(REG_PIHT, high & 0xFF)
    }

    /**
     * Set interrupt persistence filters.
     */
    void setPersistence(int ppers, int apers) {
        writeReg(REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F))
    }

    /**
     * Enable or disable ALS interrupt.
     */
    void enableAlsInterrupt(boolean enabled) {
        int val = readReg(REG_ENABLE)
        if (enabled) val |= 0x10; else val &= ~0x10
        writeReg(REG_ENABLE, val)
    }

    /**
     * Enable or disable proximity interrupt.
     */
    void enableProximityInterrupt(boolean enabled) {
        int val = readReg(REG_ENABLE)
        if (enabled) val |= 0x20; else val &= ~0x20
        writeReg(REG_ENABLE, val)
    }

    /**
     * Clear the proximity interrupt via address-only write to PICLEAR.
     */
    void clearProximityInterrupt() {
        transport.write([(byte) REG_PICLEAR] as byte[])
    }

    /**
     * Clear the ALS/color interrupt via address-only write to CICLEAR.
     */
    void clearAlsInterrupt() {
        transport.write([(byte) REG_CICLEAR] as byte[])
    }

    /**
     * Clear all non-gesture interrupts via address-only write to AICLEAR.
     */
    void clearAllInterrupts() {
        transport.write([(byte) REG_AICLEAR] as byte[])
    }

    /**
     * Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes (sign-magnitude).
     */
    void setProximityOffset(int ur, int dl) {
        writeReg(REG_POFFSET_UR, encodeOffset(ur))
        writeReg(REG_POFFSET_DL, encodeOffset(dl))
    }

    /**
     * Mask individual photodiodes in proximity detection.
     */
    void setProximityMask(boolean u, boolean d, boolean l, boolean r) {
        int c3 = readReg(REG_CONFIG3) & 0xF0
        if (u) c3 |= 0x08
        if (d) c3 |= 0x04
        if (l) c3 |= 0x02
        if (r) c3 |= 0x01
        writeReg(REG_CONFIG3, c3)
    }

    /**
     * Enable or disable the gesture engine.
     */
    void enableGesture(boolean enabled) {
        int val = readReg(REG_ENABLE)
        if (enabled) {
            val |= 0x40
            writeReg(REG_ENABLE, val)
            int g4 = readReg(REG_GCONF4)
            g4 |= 0x01
            writeReg(REG_GCONF4, g4)
        } else {
            val &= ~0x40
            writeReg(REG_ENABLE, val)
            int g4 = readReg(REG_GCONF4)
            g4 &= ~0x01
            writeReg(REG_GCONF4, g4)
        }
    }

    /**
     * Configure gesture engine parameters.
     */
    void configureGesture(int ggain, int gldrive, int gpulse, int gplen, int gwtime, int gpenth, int gexth) {
        writeReg(REG_GPENTH, gpenth & 0xFF)
        writeReg(REG_GEXTH, gexth & 0xFF)
        int g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07)
        writeReg(REG_GCONF2, g2)
        writeReg(REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F))
    }

    /**
     * Check if gesture data is available in the FIFO.
     *
     * @return true if GSTATUS.GVALID is set
     */
    boolean gestureAvailable() {
        (readReg(REG_GSTATUS) & 0x01) != 0
    }

    /**
     * Read gesture datasets from the FIFO.
     *
     * @return list of [U, D, L, R] arrays, one per dataset
     */
    List<int[]> readGestureFifo() {
        int level = readReg(REG_GFLVL)
        if (level == 0) return []
        List<int[]> result = []
        for (int i = 0; i < level; i++) {
            byte[] raw = transport.writeRead([(byte) REG_GFIFO_U] as byte[], 4)
            result.add([raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF] as int[])
        }
        result
    }

    /**
     * Read the number of datasets in the gesture FIFO.
     */
    int gestureFifoLevel() {
        readReg(REG_GFLVL)
    }

    /**
     * Clear the gesture FIFO by setting GFIFO_CLR in GCONF4.
     */
    void clearGestureFifo() {
        int g4 = readReg(REG_GCONF4)
        g4 |= 0x04
        writeReg(REG_GCONF4, g4)
    }

    /**
     * Enable or disable gesture interrupt.
     */
    void enableGestureInterrupt(boolean enabled) {
        int g4 = readReg(REG_GCONF4)
        if (enabled) g4 |= 0x02; else g4 &= ~0x02
        writeReg(REG_GCONF4, g4)
    }

    /**
     * Read the raw STATUS register.
     */
    int status() {
        readReg(REG_STATUS)
    }

    /**
     * Check if ALS/color data is valid.
     */
    boolean isAlsValid() {
        (readReg(REG_STATUS) & 0x01) != 0
    }

    /**
     * Check if proximity data is valid.
     */
    boolean isProximityValid() {
        (readReg(REG_STATUS) & 0x02) != 0
    }

    /**
     * Check if the clear photodiode is saturated.
     */
    boolean isAlsSaturated() {
        (readReg(REG_STATUS) & 0x80) != 0
    }

    /**
     * Check if analog saturation occurred during proximity.
     */
    boolean isProximitySaturated() {
        (readReg(REG_STATUS) & 0x40) != 0
    }

    /**
     * Read the device ID register.
     *
     * @return ID register value (expect 0xAB)
     */
    int chipId() {
        readReg(REG_ID)
    }

    private static int encodeOffset(int value) {
        if (value < 0) return 0x80 | ((-value) & 0x7F)
        return value & 0x7F
    }
}
