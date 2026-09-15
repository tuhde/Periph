'use strict';

const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x20;
const _REG_CTRL_REG2     = 0x21;
const _REG_CTRL_REG3     = 0x22;
const _REG_CTRL_REG4     = 0x23;
const _REG_CTRL_REG5     = 0x24;
const _REG_OUT_TEMP      = 0x26;
const _REG_STATUS        = 0x27;
const _REG_OUT_X_L       = 0x28;
const _REG_OUT_X_H       = 0x29;
const _REG_OUT_Y_L       = 0x2A;
const _REG_OUT_Y_H       = 0x2B;
const _REG_OUT_Z_L       = 0x2C;
const _REG_OUT_Z_H       = 0x2D;
const _REG_FIFO_CTRL     = 0x2E;
const _REG_FIFO_SRC      = 0x2F;
const _REG_INT1_CFG      = 0x30;
const _REG_INT1_SRC      = 0x31;
const _REG_INT1_THS_XH   = 0x32;
const _REG_INT1_THS_XL   = 0x33;
const _REG_INT1_THS_YH   = 0x34;
const _REG_INT1_THS_YL   = 0x35;
const _REG_INT1_THS_ZH   = 0x36;
const _REG_INT1_THS_ZL   = 0x37;
const _REG_INT1_DURATION = 0x38;

const _WHO_AM_I_EXPECTED = 0xD3;
const _CTRL_REG1_DEFAULT = 0x0F;
const _CTRL_REG4_DEFAULT = 0x80;

const _SENSITIVITY = {
    250:  8.75e-3,
    500:  17.5e-3,
    2000: 70.0e-3,
};

const _DPS_TO_RAD = Math.PI / 180.0;

function _int16Le(data, offset) {
    let v = data[offset] | (data[offset + 1] << 8);
    if (v & 0x8000) v -= 0x10000;
    return v;
}

class L3G4200DMinimal {
    /**
     * L3G4200D three-axis MEMS gyroscope — minimal interface.
     *
     * Provides angular rate readings on the X, Y, and Z axes with no
     * configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
     * 0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.
     *
     * Default configuration (baked in at construction):
     *     - 100 Hz ODR, 12.5 Hz LPF2 cutoff (DR=00, BW=00)
     *     - ±250 dps full scale
     *     - BDU=1
     *     - All axes enabled, normal power mode
     *     - FIFO disabled
     *     - HPF disabled
     *
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
     */
    constructor(connection, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._fullScale = 250;
        this._init();
    }

    async _init() {
        try {
            const who = (await this._readReg(_REG_WHO_AM_I, 1))[0];
            if (who !== _WHO_AM_I_EXPECTED) return;
            await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT);
            await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);
        } catch (e) { /* bus may be idle */ }
    }

    async _writeReg(reg, value) {
        const addr = this._busType === 'spi' ? (reg & 0x3F) : reg;
        await this._conn.write(Buffer.from([addr, value & 0xFF]));
    }

    async _readReg(reg, n) {
        let addr;
        if (this._busType === 'spi') {
            addr = reg | 0xC0;  // READ=1, MS=1 (auto-increment)
        } else if (n > 1) {
            addr = reg | 0x80;  // MSB set = I²C multi-byte auto-increment
        } else {
            addr = reg;
        }
        return this._conn.writeRead(Buffer.from([addr & 0xFF]), n);
    }

    _sensitivity() {
        return _SENSITIVITY[this._fullScale];
    }

    /**
     * Read angular rate on all three axes as a single burst transaction.
     * @returns {Promise<[number, number, number]>} (x_rad_s, y_rad_s, z_rad_s).
     */
    async angularRate() {
        const buf = await this._readReg(_REG_OUT_X_L, 6);
        const sens = this._sensitivity();
        const x_dps = _int16Le(buf, 0) * sens;
        const y_dps = _int16Le(buf, 2) * sens;
        const z_dps = _int16Le(buf, 4) * sens;
        return [x_dps * _DPS_TO_RAD, y_dps * _DPS_TO_RAD, z_dps * _DPS_TO_RAD];
    }
}

class L3G4200DFull extends L3G4200DMinimal {
    /**
     * L3G4200D full interface — extends L3G4200DMinimal with full configuration,
     * FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
     *
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
     */
    constructor(connection, busType = 'i2c') {
        super(connection, busType);
        this._odr = 0;
        this._bw = 0;
    }

    /** Output data rate codes (DR[1:0] in CTRL_REG1). */
    static ODR_100_HZ = 0;
    static ODR_200_HZ = 1;
    static ODR_400_HZ = 2;
    static ODR_800_HZ = 3;

    /** Full-scale ranges. */
    static FS_250_DPS  = 250;
    static FS_500_DPS  = 500;
    static FS_2000_DPS = 2000;

    /** FIFO modes (FM[2:0] in FIFO_CTRL_REG). */
    static FIFO_BYPASS           = 0;
    static FIFO_FIFO             = 1;
    static FIFO_STREAM           = 2;
    static FIFO_STREAM_TO_FIFO   = 3;
    static FIFO_BYPASS_TO_STREAM = 4;

    /**
     * Configure ODR, LPF2 bandwidth, and full scale.
     * @param {number} odr          ODR code (0=100, 1=200, 2=400, 3=800 Hz).
     * @param {number} bandwidth    LPF2 bandwidth code 0-3.
     * @param {number} fullScale    Full-scale in dps (250, 500, or 2000).
     */
    async configure(odr, bandwidth, fullScale) {
        if (![250, 500, 2000].includes(fullScale)) {
            throw new Error('fullScale must be 250, 500 or 2000 dps');
        }
        this._odr = odr & 0x3;
        this._bw = bandwidth & 0x3;
        this._fullScale = fullScale;
        const ctrl1 = _CTRL_REG1_DEFAULT | ((this._odr & 0x3) << 6) | ((this._bw & 0x3) << 4);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        const fsBits = fullScale === 250 ? 0 : (fullScale === 500 ? 1 : 2);
        await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT | ((fsBits & 0x3) << 4));
    }

    /**
     * Update the full-scale range.
     * @param {number} fullScale 250, 500, or 2000 dps.
     */
    async setFullScale(fullScale) {
        if (![250, 500, 2000].includes(fullScale)) {
            throw new Error('fullScale must be 250, 500 or 2000 dps');
        }
        this._fullScale = fullScale;
        const fsBits = fullScale === 250 ? 0 : (fullScale === 500 ? 1 : 2);
        const ctrl4 = (await this._readReg(_REG_CTRL_REG4, 1))[0];
        await this._writeReg(_REG_CTRL_REG4, (ctrl4 & 0xCF) | ((fsBits & 0x3) << 4));
    }

    /** @returns {Promise<number>} WHO_AM_I (0xD3 for genuine L3G4200D). */
    async whoAmI() {
        return (await this._readReg(_REG_WHO_AM_I, 1))[0];
    }

    /** @returns {Promise<number>} Raw STATUS_REG byte. */
    async status() {
        return (await this._readReg(_REG_STATUS, 1))[0];
    }

    /** @returns {Promise<boolean>} True if STATUS_REG.ZYXDA (bit 3) is set. */
    async dataReady() {
        return Boolean((await this.status()) & 0x08);
    }

    /** @returns {Promise<number>} Signed 8-bit temperature count (-1 °C/digit). */
    async temperature() {
        let raw = (await this._readReg(_REG_OUT_TEMP, 1))[0];
        if (raw & 0x80) raw -= 0x100;
        return raw;
    }

    /** Enter power-down mode (PD=0 in CTRL_REG1). */
    async powerDown() {
        const ctrl1 = (await this._readReg(_REG_CTRL_REG1, 1))[0] & 0xF7;
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }

    /** Wake from power-down (PD=1); previously enabled axes restored. */
    async wakeUp() {
        const ctrl1 = (await this._readReg(_REG_CTRL_REG1, 1))[0] | 0x08;
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }

    /** Enter sleep mode (PD=1, all axes disabled). */
    async sleep() {
        await this._writeReg(_REG_CTRL_REG1, 0x08);
    }

    /**
     * Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1).
     * @param {boolean} x
     * @param {boolean} y
     * @param {boolean} z
     */
    async enableAxes(x, y, z) {
        const ctrl1 = (await this._readReg(_REG_CTRL_REG1, 1))[0] & 0xF8;
        const val = ctrl1 | (z ? 0x04 : 0) | (y ? 0x02 : 0) | (x ? 0x01 : 0);
        await this._writeReg(_REG_CTRL_REG1, val);
    }

    /**
     * Configure and enable the FIFO.
     * @param {number} mode      FIFO mode 0-4.
     * @param {number} watermark Watermark threshold 0-31.
     */
    async enableFifo(mode, watermark) {
        if (mode < 0 || mode > 4) throw new Error('mode must be 0..4');
        if (watermark < 0 || watermark > 31) throw new Error('watermark must be 0..31');
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0] | 0x40;
        await this._writeReg(_REG_CTRL_REG5, ctrl5);
        await this._writeReg(_REG_FIFO_CTRL, ((mode & 0x7) << 5) | (watermark & 0x1F));
    }

    /** Disable the FIFO. */
    async disableFifo() {
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0] & ~0x40;
        await this._writeReg(_REG_CTRL_REG5, ctrl5);
        await this._writeReg(_REG_FIFO_CTRL, 0x00);
    }

    /** @returns {Promise<number>} FSS[4:0] from FIFO_SRC_REG. */
    async fifoSamples() {
        return (await this._readReg(_REG_FIFO_SRC, 1))[0] & 0x1F;
    }

    /**
     * Read all stored FIFO samples.
     * @returns {Promise<Array<[number, number, number]>>} List of (x_rad_s, y_rad_s, z_rad_s).
     */
    async readFifo() {
        const n = await this.fifoSamples();
        if (n === 0) return [];
        const sens = this._sensitivity();
        const buf = await this._readReg(_REG_OUT_X_L, n * 6);
        const out = [];
        for (let i = 0; i < n; i++) {
            const o = i * 6;
            const x = _int16Le(buf, o) * sens * _DPS_TO_RAD;
            const y = _int16Le(buf, o + 2) * sens * _DPS_TO_RAD;
            const z = _int16Le(buf, o + 4) * sens * _DPS_TO_RAD;
            out.push([x, y, z]);
        }
        return out;
    }

    /**
     * Enable the high-pass filter.
     * @param {number} mode   HPF mode 0-3.
     * @param {number} cutoff HPF cutoff code 0-9.
     */
    async enableHighpass(mode, cutoff) {
        if (mode < 0 || mode > 3) throw new Error('mode must be 0..3');
        if (cutoff < 0 || cutoff > 9) throw new Error('cutoff must be 0..9');
        await this._writeReg(_REG_CTRL_REG2, ((mode & 0x3) << 4) | (cutoff & 0x0F));
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0] | 0x10;
        await this._writeReg(_REG_CTRL_REG5, ctrl5);
    }

    /** Disable the high-pass filter. */
    async disableHighpass() {
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0] & ~0x10;
        await this._writeReg(_REG_CTRL_REG5, ctrl5);
    }

    /**
     * Configure INT1_CFG axis/direction events.
     * @param {boolean} xHigh
     * @param {boolean} xLow
     * @param {boolean} yHigh
     * @param {boolean} yLow
     * @param {boolean} zHigh
     * @param {boolean} zLow
     * @param {boolean} andMode
     * @param {boolean} latch
     */
    async setInterrupt(xHigh, xLow, yHigh, yLow, zHigh, zLow, andMode, latch) {
        let cfg = 0;
        if (andMode) cfg |= 0x80;
        if (latch)   cfg |= 0x40;
        if (zHigh)   cfg |= 0x20;
        if (zLow)    cfg |= 0x10;
        if (yHigh)   cfg |= 0x08;
        if (yLow)    cfg |= 0x04;
        if (xHigh)   cfg |= 0x02;
        if (xLow)    cfg |= 0x01;
        await this._writeReg(_REG_INT1_CFG, cfg);
        if (cfg & 0x3F) {
            const ctrl3 = (await this._readReg(_REG_CTRL_REG3, 1))[0] | 0x80;
            await this._writeReg(_REG_CTRL_REG3, ctrl3);
        }
    }

    /**
     * Set the interrupt threshold for one axis.
     * @param {string} axis            'x', 'y', or 'z'.
     * @param {number} thresholdDps    Threshold in dps.
     */
    async setThreshold(axis, thresholdDps) {
        const raw = Math.trunc(thresholdDps / this._sensitivity()) & 0x7FFF;
        let hi, lo;
        if (axis === 'x')      { hi = _REG_INT1_THS_XH; lo = _REG_INT1_THS_XL; }
        else if (axis === 'y') { hi = _REG_INT1_THS_YH; lo = _REG_INT1_THS_YL; }
        else if (axis === 'z') { hi = _REG_INT1_THS_ZH; lo = _REG_INT1_THS_ZL; }
        else throw new Error("axis must be 'x', 'y' or 'z'");
        await this._writeReg(hi, (raw >> 8) & 0x7F);
        await this._writeReg(lo, raw & 0xFF);
    }

    /**
     * Set INT1_DURATION.
     * @param {number} samples Duration 0-127.
     * @param {boolean} wait    If true, INT1 stays asserted until INT1_SRC is read.
     */
    async setDuration(samples, wait) {
        if (samples < 0 || samples > 127) throw new Error('samples must be 0..127');
        await this._writeReg(_REG_INT1_DURATION, ((wait ? 1 : 0) << 7) | (samples & 0x7F));
    }

    /** @returns {Promise<number>} Raw INT1_SRC byte; reading clears the interrupt-active bit. */
    async readIntSource() {
        return (await this._readReg(_REG_INT1_SRC, 1))[0];
    }

    /**
     * Route the data-ready signal to the DRDY/INT2 pin.
     * @param {boolean} enable
     */
    async setDataReadyPin(enable) {
        const ctrl3 = (await this._readReg(_REG_CTRL_REG3, 1))[0];
        await this._writeReg(_REG_CTRL_REG3, enable ? (ctrl3 | 0x08) : (ctrl3 & ~0x08));
    }
}

module.exports = { L3G4200DMinimal, L3G4200DFull };
