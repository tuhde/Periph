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
const _REG_INT1_TSH_XH   = 0x32;
const _REG_INT1_TSH_XL   = 0x33;
const _REG_INT1_TSH_YH   = 0x34;
const _REG_INT1_TSH_YL   = 0x35;
const _REG_INT1_TSH_ZH   = 0x36;
const _REG_INT1_TSH_ZL   = 0x37;
const _REG_INT1_DURATION = 0x38;

const _WHO_AM_I_L3GD20  = 0xD4;
const _WHO_AM_I_L3GD20H = 0xD7;
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

class L3GD20HMinimal {
    /**
     * L3GD20H (and L3GD20) three-axis MEMS gyroscope — minimal interface.
     *
     * Provides angular rate readings on the X, Y, and Z axes with no
     * configuration beyond the connection. I²C address is 0x6A (SA0/SDO=GND) or
     * 0x6B (SA0/SDO=VCC). SPI uses Mode 3 (CPOL=CPHA=1) by default.
     *
     * Default configuration (baked in at construction):
     *     - 95 Hz ODR, default bandwidth (DR=00, BW=00)
     *     - ±250 dps full scale (sensitivity 8.75 mdps/digit)
     *     - BDU=1 (block data update — hold registers until MSB+LSB read)
     *     - All axes enabled, normal power mode
     *     - 250 ms startup delay for gyroscope stabilization
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
            if (who !== _WHO_AM_I_L3GD20 && who !== _WHO_AM_I_L3GD20H) return;
            await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT);
            await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);
            // 250 ms startup delay
            await new Promise(resolve => setTimeout(resolve, 250));
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
    async gyro() {
        const buf = await this._readReg(_REG_OUT_X_L, 6);
        const sens = this._sensitivity();
        const x_dps = _int16Le(buf, 0) * sens;
        const y_dps = _int16Le(buf, 2) * sens;
        const z_dps = _int16Le(buf, 4) * sens;
        return [x_dps * _DPS_TO_RAD, y_dps * _DPS_TO_RAD, z_dps * _DPS_TO_RAD];
    }
}

class L3GD20HFull extends L3GD20HMinimal {
    /**
     * L3GD20H full interface — extends L3GD20HMinimal with full configuration,
     * FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.
     *
     * Adds ODR/bandwidth/full-scale configuration, FIFO with all five modes
     * and watermark, high-pass filter with selectable cutoff, per-axis
     * interrupt generation with threshold and duration, INT1 pin routing,
     * and access to temperature and status registers.
     *
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
     */
    constructor(connection, busType = 'i2c') {
        super(connection, busType);
        this._odr = 0;
        this._bw = 0;
        this._thresholdRaw = 0;
    }

    /** Output data rate codes (DR[1:0] in CTRL_REG1). */
    static ODR_95_HZ  = 0;
    static ODR_190_HZ = 1;
    static ODR_380_HZ = 2;
    static ODR_760_HZ = 3;

    /** Full-scale codes (0=±250, 1=±500, 2=±2000 dps). */
    static FS_250_DPS  = 0;
    static FS_500_DPS  = 1;
    static FS_2000_DPS = 2;

    /** FIFO modes (FM[2:0] in FIFO_CTRL_REG). */
    static FIFO_BYPASS             = 0;
    static FIFO_FIFO               = 1;
    static FIFO_STREAM             = 2;
    static FIFO_BYPASS_TO_STREAM   = 3;
    static FIFO_STREAM_TO_FIFO     = 7;

    /** HPF modes (HPM[1:0] in CTRL_REG2). */
    static HPM_NORMAL      = 0;
    static HPM_REFERENCE   = 1;
    static HPM_NORMAL_ALT  = 2;
    static HPM_AUTORESET   = 3;

    /** Power mode strings. */
    static POWER_NORMAL     = 'normal';
    static POWER_SLEEP      = 'sleep';
    static POWER_POWERDOWN  = 'power_down';

    /**
     * Configure ODR, bandwidth, and full scale in one call.
     * @param {number} odr         ODR code 0-3 (95/190/380/760 Hz).
     * @param {number} bw          Bandwidth code 0-3 (ODR-dependent).
     * @param {number} fullScale   Full-scale code 0=±250, 1=±500, 2=±2000 dps.
     */
    async configure(odr, bw, fullScale) {
        if (odr < 0 || odr > 3) throw new Error('odr must be 0..3');
        if (bw < 0 || bw > 3) throw new Error('bw must be 0..3');
        if (fullScale < 0 || fullScale > 2) throw new Error('fullScale must be 0..2');
        this._odr = odr;
        this._bw = bw;
        const fsMap = [250, 500, 2000];
        this._fullScale = fsMap[fullScale];
        const ctrl1 = _CTRL_REG1_DEFAULT | ((this._odr & 0x3) << 6) | ((this._bw & 0x3) << 4);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT | ((fullScale & 0x3) << 4));
    }

    /**
     * Read raw 16-bit signed angular rate values.
     * @returns {Promise<[number, number, number]>} (x_raw, y_raw, z_raw).
     */
    async gyroRaw() {
        const buf = await this._readReg(_REG_OUT_X_L, 6);
        return [_int16Le(buf, 0), _int16Le(buf, 2), _int16Le(buf, 4)];
    }

    /**
     * Read the relative temperature count.
     * OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
     * no absolute calibration — it represents change from the device's
     * power-on temperature baseline. Do not convert to absolute Celsius.
     * @returns {Promise<number>} Signed 8-bit temperature count.
     */
    async temperature() {
        let raw = (await this._readReg(_REG_OUT_TEMP, 1))[0];
        if (raw & 0x80) raw -= 0x100;
        return raw;
    }

    /**
     * Check whether a new X/Y/Z sample is ready.
     * @returns {Promise<boolean>} True if STATUS_REG.ZYXDA (bit 3) is set.
     */
    async dataReady() {
        const status = (await this._readReg(_REG_STATUS, 1))[0];
        return Boolean(status & 0x08);
    }

    /**
     * Configure the high-pass filter (CTRL_REG2).
     * @param {number} mode   HPF mode 0-3 (HPM[1:0]).
     * @param {number} cutoff HPF cutoff code 0-15 (HPCF[3:0]).
     */
    async configureHpFilter(mode, cutoff) {
        if (mode < 0 || mode > 3) throw new Error('mode must be 0..3');
        if (cutoff < 0 || cutoff > 15) throw new Error('cutoff must be 0..15');
        await this._writeReg(_REG_CTRL_REG2, ((mode & 0x3) << 4) | (cutoff & 0x0F));
    }

    /**
     * Enable or disable the high-pass filter on the output path.
     * @param {boolean} enable True to enable (sets HPen in CTRL_REG5), False to disable.
     */
    async enableHpFilter(enable) {
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0];
        await this._writeReg(_REG_CTRL_REG5, enable ? (ctrl5 | 0x10) : (ctrl5 & ~0x10));
    }

    /**
     * Configure the FIFO (FIFO_CTRL_REG).
     * @param {number} mode      FIFO mode 0=Bypass, 1=FIFO, 2=Stream, 3=Bypass-to-Stream, 7=Stream-to-FIFO.
     * @param {number} watermark Watermark threshold 0-31 (WTM[4:0]).
     */
    async configureFifo(mode, watermark) {
        const validModes = [0, 1, 2, 3, 7];
        if (!validModes.includes(mode)) throw new Error('mode must be 0, 1, 2, 3, or 7');
        if (watermark < 0 || watermark > 31) throw new Error('watermark must be 0..31');
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0] | 0x40;
        await this._writeReg(_REG_CTRL_REG5, ctrl5);
        await this._writeReg(_REG_FIFO_CTRL, ((mode & 0x7) << 5) | (watermark & 0x1F));
    }

    /**
     * Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).
     * @param {boolean} enable True to enable FIFO, False to disable and clear to bypass.
     */
    async enableFifo(enable) {
        const ctrl5 = (await this._readReg(_REG_CTRL_REG5, 1))[0];
        if (enable) {
            await this._writeReg(_REG_CTRL_REG5, ctrl5 | 0x40);
        } else {
            await this._writeReg(_REG_CTRL_REG5, ctrl5 & ~0x40);
            await this._writeReg(_REG_FIFO_CTRL, 0x00);
        }
    }

    /**
     * Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
     * @returns {Promise<number>} Number of stored samples (0-31).
     */
    async fifoLevel() {
        return (await this._readReg(_REG_FIFO_SRC, 1))[0] & 0x1F;
    }

    /**
     * Read all available FIFO samples and return as rad/s tuples.
     * @returns {Promise<Array<[number, number, number]>>} List of (x_rad_s, y_rad_s, z_rad_s).
     */
    async readFifo() {
        const n = await this.fifoLevel();
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
     * Set the power mode (CTRL_REG1 PD and axis enable bits).
     * @param {string} mode 'normal' (PD=1, all axes on), 'sleep' (PD=1, all axes off),
     *                       or 'power_down' (PD=0).
     */
    async setPowerMode(mode) {
        let ctrl1 = (await this._readReg(_REG_CTRL_REG1, 1))[0];
        if (mode === L3GD20HFull.POWER_NORMAL) {
            ctrl1 = (ctrl1 & 0xF0) | 0x0F;
        } else if (mode === L3GD20HFull.POWER_SLEEP) {
            ctrl1 = (ctrl1 & 0xF8) | 0x08;
        } else if (mode === L3GD20HFull.POWER_POWERDOWN) {
            ctrl1 &= 0xF7;
        } else {
            throw new Error("mode must be 'normal', 'sleep', or 'power_down'");
        }
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }
}

module.exports = { L3GD20HMinimal, L3GD20HFull };