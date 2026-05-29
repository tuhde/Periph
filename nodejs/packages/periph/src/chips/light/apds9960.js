'use strict';

const _REG_ENABLE       = 0x80;
const _REG_ATIME      = 0x81;
const _REG_WTIME      = 0x83;
const _REG_AILTL      = 0x84;
const _REG_AILTH      = 0x85;
const _REG_AIHTL      = 0x86;
const _REG_AIHTH      = 0x87;
const _REG_PILT       = 0x89;
const _REG_PIHT       = 0x8B;
const _REG_PERS       = 0x8C;
const _REG_CONFIG1    = 0x8D;
const _REG_PPULSE     = 0x8E;
const _REG_CONTROL    = 0x8F;
const _REG_CONFIG2    = 0x90;
const _REG_ID         = 0x92;
const _REG_STATUS     = 0x93;
const _REG_CDATAL     = 0x94;
const _REG_RDATAL     = 0x96;
const _REG_GDATAL     = 0x98;
const _REG_BDATAL     = 0x9A;
const _REG_PDATA      = 0x9C;
const _REG_POFFSET_UR = 0x9D;
const _REG_POFFSET_DL = 0x9E;
const _REG_CONFIG3    = 0x9F;
const _REG_GPENTH     = 0xA0;
const _REG_GEXTH      = 0xA1;
const _REG_GCONF1     = 0xA2;
const _REG_GCONF2     = 0xA3;
const _REG_GOFFSET_U  = 0xA4;
const _REG_GOFFSET_D  = 0xA5;
const _REG_GPULSE     = 0xA6;
const _REG_GOFFSET_L  = 0xA7;
const _REG_GOFFSET_R  = 0xA9;
const _REG_GCONF3     = 0xAA;
const _REG_GCONF4     = 0xAB;
const _REG_GFLVL      = 0xAE;
const _REG_GSTATUS    = 0xAF;
const _REG_PICLEAR    = 0xE5;
const _REG_CICLEAR    = 0xE6;
const _REG_AICLEAR    = 0xE7;
const _REG_GFIFO_U    = 0xFC;

const _ATIME_DEFAULT   = 0xB6;
const _CONTROL_DEFAULT = 0x01;
const _CONFIG2_DEFAULT = 0x01;

function sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

/**
 * APDS-9960 digital proximity, ambient light, RGB and gesture sensor — minimal interface.
 *
 * Provides ambient light and color (RGBC) readings with no configuration
 * beyond the transport. The ALS/Color engine is enabled at construction
 * with sensible defaults.
 *
 * Default configuration (written at construction):
 * - ATIME = 0xB6 (72 cycles, ~200 ms integration, max count 65535)
 * - AGAIN = 1 (4x ALS gain)
 * - CONFIG2 = 0x01 (LED_BOOST=100%, reserved bit 0 set)
 * - PON + AEN enabled; no wait, proximity, gesture, or interrupts
 */
class APDS9960Minimal {
    /**
     * @param {object} transport - Configured I2C transport pointing at the device (address 0x39).
     */
    constructor(transport) {
        this._transport = transport;
        sleep(6);
        const id = this._readReg(_REG_ID);
        if (id !== 0xAB) throw new Error('APDS-9960 not found (ID=0x' + id.toString(16) + ', expected 0xAB)');
        this._writeReg(_REG_ENABLE, 0x00);
        this._writeReg(_REG_ATIME, _ATIME_DEFAULT);
        this._writeReg(_REG_CONTROL, _CONTROL_DEFAULT);
        this._writeReg(_REG_CONFIG2, _CONFIG2_DEFAULT);
        this._writeReg(_REG_ENABLE, 0x03);
        sleep(210);
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value]));
    }

    _readReg(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 1)[0];
    }

    _readReg16LE(reg) {
        const buf = this._transport.writeRead(Buffer.from([reg]), 2);
        return buf[0] | (buf[1] << 8);
    }

    /**
     * Read the clear (unfiltered) channel.
     * @returns {number} Raw clear channel count, 0-65535.
     */
    colorClear() { return this._readReg16LE(_REG_CDATAL); }

    /**
     * Read the red channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     * @returns {number} Raw red channel count, 0-65535.
     */
    colorRed() {
        const raw = this._transport.writeRead(Buffer.from([_REG_CDATAL]), 8);
        return raw[2] | (raw[3] << 8);
    }

    /**
     * Read the green channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     * @returns {number} Raw green channel count, 0-65535.
     */
    colorGreen() {
        const raw = this._transport.writeRead(Buffer.from([_REG_CDATAL]), 8);
        return raw[4] | (raw[5] << 8);
    }

    /**
     * Read the blue channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     * @returns {number} Raw blue channel count, 0-65535.
     */
    colorBlue() {
        const raw = this._transport.writeRead(Buffer.from([_REG_CDATAL]), 8);
        return raw[6] | (raw[7] << 8);
    }

    /**
     * Read all four RGBC channels in one burst.
     *
     * Reading CDATAL at 0x94 atomically latches all eight bytes 0x94-0x9B.
     *
     * @returns {{ clear: number, red: number, green: number, blue: number }}
     */
    color() {
        const raw = this._transport.writeRead(Buffer.from([_REG_CDATAL]), 8);
        return {
            clear: raw[0] | (raw[1] << 8),
            red:   raw[2] | (raw[3] << 8),
            green: raw[4] | (raw[5] << 8),
            blue:  raw[6] | (raw[7] << 8)
        };
    }
}

/**
 * APDS-9960 full interface — extends APDS9960Minimal with proximity, gesture, and configuration.
 *
 * Adds proximity detection, gesture engine, wait engine, threshold and
 * interrupt configuration, status queries, and device identification.
 */
class APDS9960Full extends APDS9960Minimal {
    /**
     * @param {object} transport - Configured I2C transport pointing at the device (address 0x39).
     */
    constructor(transport) {
        super(transport);
    }

    /**
     * Enable or disable the proximity engine.
     * @param {boolean} enabled - true to enable PEN, false to disable.
     */
    enableProximity(enabled) {
        let val = this._readReg(_REG_ENABLE);
        if (enabled) val |= 0x04; else val &= ~0x04;
        this._writeReg(_REG_ENABLE, val);
    }

    /**
     * Read the proximity count.
     * @returns {number} Proximity count 0-255; higher means closer.
     */
    proximity() { return this._readReg(_REG_PDATA); }

    /**
     * Enable or disable the wait engine.
     * @param {boolean} enabled - true to enable WEN, false to disable.
     */
    enableWait(enabled) {
        let val = this._readReg(_REG_ENABLE);
        if (enabled) val |= 0x08; else val &= ~0x08;
        this._writeReg(_REG_ENABLE, val);
    }

    /**
     * Configure the wait time between ALS/proximity cycles.
     * @param {number} wtime - WTIME register value 0-255.
     * @param {boolean} [wlong=false] - true to enable WLONG 12x multiplier.
     */
    configureWait(wtime, wlong = false) {
        this._writeReg(_REG_WTIME, wtime & 0xFF);
        let c1 = this._readReg(_REG_CONFIG1);
        if (wlong) c1 |= 0x02; else c1 &= ~0x02;
        c1 = (c1 & 0x03) | 0x60;
        this._writeReg(_REG_CONFIG1, c1);
    }

    /**
     * Configure ALS integration time and gain.
     * @param {number} atime - ATIME register value 0-255.
     * @param {number} again - ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x).
     */
    configureAls(atime, again) {
        this._writeReg(_REG_ATIME, atime & 0xFF);
        let ctrl = this._readReg(_REG_CONTROL);
        ctrl = (ctrl & 0xFC) | (again & 0x03);
        this._writeReg(_REG_CONTROL, ctrl);
    }

    /**
     * Configure proximity LED drive, gain, pulse count and length.
     * @param {number} ldrive - LED drive strength 0-3.
     * @param {number} pgain - Proximity gain 0-3.
     * @param {number} ppulse - Pulse count minus 1, 0-63.
     * @param {number} pplen - Pulse length 0-3.
     */
    configureProximityLed(ldrive, pgain, ppulse, pplen) {
        let ctrl = this._readReg(_REG_CONTROL);
        ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03);
        this._writeReg(_REG_CONTROL, ctrl);
        this._writeReg(_REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F));
    }

    /**
     * Set additional LED current boost.
     * @param {number} boost - LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%).
     */
    setLedBoost(boost) {
        let c2 = this._readReg(_REG_CONFIG2);
        c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01;
        this._writeReg(_REG_CONFIG2, c2);
    }

    /**
     * Set ALS interrupt thresholds.
     * @param {number} low - Low threshold 0-65535.
     * @param {number} high - High threshold 0-65535.
     */
    alsThreshold(low, high) {
        this._writeReg(_REG_AILTL, low & 0xFF);
        this._writeReg(_REG_AILTH, (low >> 8) & 0xFF);
        this._writeReg(_REG_AIHTL, high & 0xFF);
        this._writeReg(_REG_AIHTH, (high >> 8) & 0xFF);
    }

    /**
     * Set proximity interrupt thresholds.
     * @param {number} low - Low threshold 0-255.
     * @param {number} high - High threshold 0-255.
     */
    proximityThreshold(low, high) {
        this._writeReg(_REG_PILT, low & 0xFF);
        this._writeReg(_REG_PIHT, high & 0xFF);
    }

    /**
     * Set interrupt persistence filters.
     * @param {number} ppers - Proximity persistence 0-15.
     * @param {number} apers - ALS persistence 0-15.
     */
    setPersistence(ppers, apers) {
        this._writeReg(_REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F));
    }

    /**
     * Enable or disable ALS interrupt.
     * @param {boolean} enabled - true to enable AIEN, false to disable.
     */
    enableAlsInterrupt(enabled) {
        let val = this._readReg(_REG_ENABLE);
        if (enabled) val |= 0x10; else val &= ~0x10;
        this._writeReg(_REG_ENABLE, val);
    }

    /**
     * Enable or disable proximity interrupt.
     * @param {boolean} enabled - true to enable PIEN, false to disable.
     */
    enableProximityInterrupt(enabled) {
        let val = this._readReg(_REG_ENABLE);
        if (enabled) val |= 0x20; else val &= ~0x20;
        this._writeReg(_REG_ENABLE, val);
    }

    /**
     * Clear the proximity interrupt via address-only write to PICLEAR.
     */
    clearProximityInterrupt() {
        this._transport.write(Buffer.from([_REG_PICLEAR]));
    }

    /**
     * Clear the ALS/color interrupt via address-only write to CICLEAR.
     */
    clearAlsInterrupt() {
        this._transport.write(Buffer.from([_REG_CICLEAR]));
    }

    /**
     * Clear all non-gesture interrupts via address-only write to AICLEAR.
     */
    clearAllInterrupts() {
        this._transport.write(Buffer.from([_REG_AICLEAR]));
    }

    /**
     * Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes.
     * @param {number} ur - UP/RIGHT offset -127 to +127 (sign-magnitude).
     * @param {number} dl - DOWN/LEFT offset -127 to +127 (sign-magnitude).
     */
    setProximityOffset(ur, dl) {
        const encode = (v) => v < 0 ? (0x80 | ((-v) & 0x7F)) : (v & 0x7F);
        this._writeReg(_REG_POFFSET_UR, encode(ur));
        this._writeReg(_REG_POFFSET_DL, encode(dl));
    }

    /**
     * Mask individual photodiodes in proximity detection.
     * @param {boolean} u - true to mask UP.
     * @param {boolean} d - true to mask DOWN.
     * @param {boolean} l - true to mask LEFT.
     * @param {boolean} r - true to mask RIGHT.
     */
    setProximityMask(u, d, l, r) {
        let c3 = this._readReg(_REG_CONFIG3) & 0xF0;
        if (u) c3 |= 0x08;
        if (d) c3 |= 0x04;
        if (l) c3 |= 0x02;
        if (r) c3 |= 0x01;
        this._writeReg(_REG_CONFIG3, c3);
    }

    /**
     * Enable or disable the gesture engine.
     * @param {boolean} enabled - true to enable GEN and set GMODE, false to disable.
     */
    enableGesture(enabled) {
        let val = this._readReg(_REG_ENABLE);
        if (enabled) {
            val |= 0x40;
            this._writeReg(_REG_ENABLE, val);
            let g4 = this._readReg(_REG_GCONF4);
            g4 |= 0x01;
            this._writeReg(_REG_GCONF4, g4);
        } else {
            val &= ~0x40;
            this._writeReg(_REG_ENABLE, val);
            let g4 = this._readReg(_REG_GCONF4);
            g4 &= ~0x01;
            this._writeReg(_REG_GCONF4, g4);
        }
    }

    /**
     * Configure gesture engine parameters.
     * @param {number} ggain - Gesture gain 0-3.
     * @param {number} gldrive - Gesture LED drive 0-3.
     * @param {number} gpulse - Gesture pulse count minus 1, 0-63.
     * @param {number} gplen - Gesture pulse length 0-3.
     * @param {number} gwtime - Gesture wait time 0-7.
     * @param {number} gpenth - Gesture proximity entry threshold 0-255.
     * @param {number} gexth - Gesture exit threshold 0-255.
     */
    configureGesture(ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) {
        this._writeReg(_REG_GPENTH, gpenth & 0xFF);
        this._writeReg(_REG_GEXTH, gexth & 0xFF);
        const g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07);
        this._writeReg(_REG_GCONF2, g2);
        this._writeReg(_REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F));
    }

    /**
     * Check if gesture data is available in the FIFO.
     * @returns {boolean} true if GSTATUS.GVALID is set.
     */
    gestureAvailable() { return !!(this._readReg(_REG_GSTATUS) & 0x01); }

    /**
     * Read all gesture datasets from the FIFO.
     * @returns {Array<{ u: number, d: number, l: number, r: number }>}
     */
    readGestureFifo() {
        const level = this._readReg(_REG_GFLVL);
        if (level === 0) return [];
        const result = [];
        for (let i = 0; i < level; i++) {
            const raw = this._transport.writeRead(Buffer.from([_REG_GFIFO_U]), 4);
            result.push({ u: raw[0], d: raw[1], l: raw[2], r: raw[3] });
        }
        return result;
    }

    /**
     * Read the number of datasets in the gesture FIFO.
     * @returns {number} Number of 4-byte datasets currently in FIFO.
     */
    gestureFifoLevel() { return this._readReg(_REG_GFLVL); }

    /**
     * Clear the gesture FIFO by setting GFIFO_CLR in GCONF4.
     */
    clearGestureFifo() {
        let g4 = this._readReg(_REG_GCONF4);
        g4 |= 0x04;
        this._writeReg(_REG_GCONF4, g4);
    }

    /**
     * Enable or disable gesture interrupt.
     * @param {boolean} enabled - true to enable GIEN, false to disable.
     */
    enableGestureInterrupt(enabled) {
        let g4 = this._readReg(_REG_GCONF4);
        if (enabled) g4 |= 0x02; else g4 &= ~0x02;
        this._writeReg(_REG_GCONF4, g4);
    }

    /**
     * Read the raw STATUS register.
     * @returns {number} Raw STATUS byte.
     */
    status() { return this._readReg(_REG_STATUS); }

    /**
     * Check if ALS/color data is valid.
     * @returns {boolean} true if STATUS.AVALID is set.
     */
    isAlsValid() { return !!(this._readReg(_REG_STATUS) & 0x01); }

    /**
     * Check if proximity data is valid.
     * @returns {boolean} true if STATUS.PVALID is set.
     */
    isProximityValid() { return !!(this._readReg(_REG_STATUS) & 0x02); }

    /**
     * Check if the clear photodiode is saturated.
     * @returns {boolean} true if STATUS.CPSAT is set.
     */
    isAlsSaturated() { return !!(this._readReg(_REG_STATUS) & 0x80); }

    /**
     * Check if analog saturation occurred during proximity.
     * @returns {boolean} true if STATUS.PGSAT is set.
     */
    isProximitySaturated() { return !!(this._readReg(_REG_STATUS) & 0x40); }

    /**
     * Read the device ID register.
     * @returns {number} ID register value (expect 0xAB).
     */
    chipId() { return this._readReg(_REG_ID); }
}

module.exports = { APDS9960Minimal, APDS9960Full };
