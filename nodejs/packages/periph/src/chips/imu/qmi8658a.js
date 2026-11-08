'use strict';

const _REG_WHO_AM_I      = 0x00;
const _REG_REVISION_ID   = 0x01;
const _REG_CTRL1         = 0x02;
const _REG_CTRL2         = 0x03;
const _REG_CTRL3         = 0x04;
const _REG_CTRL5         = 0x06;
const _REG_CTRL7         = 0x08;
const _REG_CTRL8         = 0x09;
const _REG_CTRL9         = 0x0A;
const _REG_CAL1_L        = 0x0B;
const _REG_FIFO_WTM_TH   = 0x13;
const _REG_FIFO_CTRL     = 0x14;
const _REG_FIFO_SMPL_CNT = 0x15;
const _REG_FIFO_STATUS   = 0x16;
const _REG_FIFO_DATA     = 0x17;
const _REG_STATUSINT     = 0x2D;
const _REG_STATUS0       = 0x2E;
const _REG_TIMESTAMP     = 0x30;
const _REG_TEMP_L        = 0x33;
const _REG_AX_L          = 0x35;
const _REG_GX_L          = 0x3B;
const _REG_COD_STATUS    = 0x46;
const _REG_FW_VERSION    = 0x49;
const _REG_RESET_STATUS  = 0x4D;
const _REG_DVX_L         = 0x51;
const _REG_TAP_STATUS    = 0x59;
const _REG_STEP_CNT      = 0x5A;
const _REG_RESET         = 0x60;

const _WHO_AM_I_VALUE = 0x05;
const _RESET_CMD = 0xB0;

const _CMD_ACK = 0x00;
const _CMD_RST_FIFO = 0x04;
const _CMD_REQ_FIFO = 0x05;
const _CMD_WRITE_WOM = 0x08;
const _CMD_ACCEL_OFFSET = 0x09;
const _CMD_GYRO_OFFSET = 0x0A;
const _CMD_CONFIG_TAP = 0x0C;
const _CMD_CONFIG_PEDOMETER = 0x0D;
const _CMD_CONFIG_MOTION = 0x0E;
const _CMD_RESET_PEDOMETER = 0x0F;
const _CMD_COPY_USID = 0x10;
const _CMD_SET_RPU = 0x11;
const _CMD_AHB_CLOCK_GATING = 0x12;
const _CMD_COD = 0xA2;
const _CMD_APPLY_GYRO_GAINS = 0xAA;

const _G = 9.80665;
const _DEG_TO_RAD = Math.PI / 180.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const _round = (v) => Math.round(v);
const _clampU8 = (v) => Math.max(0, Math.min(255, v));

/**
 * Interrupt source bit masks used by `QMI8658AFull` (`pollInterrupt()` return value
 * and the argument of `enableInterrupt()` / `disableInterrupt()`).
 */
const QMI8658ASource = {
    DATA_READY: 0x0001,
    FIFO_WATERMARK: 0x0002,
    FIFO_FULL: 0x0004,
    ANY_MOTION: 0x0008,
    NO_MOTION: 0x0010,
    SIGNIFICANT_MOTION: 0x0020,
    STEP: 0x0040,
    TAP: 0x0080,
    WAKE_ON_MOTION: 0x0100,
};

const _ENGINE_BIT = {
    [QMI8658ASource.ANY_MOTION]: 0x02,
    [QMI8658ASource.NO_MOTION]: 0x04,
    [QMI8658ASource.SIGNIFICANT_MOTION]: 0x08,
    [QMI8658ASource.STEP]: 0x10,
    [QMI8658ASource.TAP]: 0x01,
};

/**
 * QMI8658A 6-axis IMU (QST) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. Runs the datasheet initialisation sequence: soft
 * reset, `WHO_AM_I` check, address auto-increment on, little-endian reads,
 * CTRL9 handshake by polling, both sensors on.
 *
 * Default configuration (written at construction):
 * - Accelerometer ±2 g, gyroscope ±256 dps
 * - Both output data rates code 5 (224.2 Hz in 6DOF mode)
 * - Both low-pass filters on, mode 3 (13.37 % of ODR)
 * - FIFO bypass (data-ready active), all INT pins high-Z, engines off
 *
 * Constructor caveat: JS constructors cannot be async, so the initialisation
 * sequence — including the `WHO_AM_I` identity check — starts in the
 * constructor and every public method awaits it first. A wrong or absent
 * device therefore rejects the first call made on the driver; call
 * `await imu.init()` right after construction to surface it at a predictable
 * point.
 */
class QMI8658AMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C (0x6B with SA0 = GND, 0x6A with SA0 = VDDIO), SMBus or SPI (read bit 0x80) register connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._ctrl1 = 0x40;
        this._ctrl7 = 0x03;
        this._ctrl8 = 0x80;
        this._accelFs = 0;
        this._gyroFs = 4;
        this._accelOdr = 5;
        this._gyroOdr = 5;
        this._accelSens = 16384;
        this._gyroSens = 128;
        this._fifoCtrl = 0;
        this._fifoWtm = 0;
        this._sync = false;
        this._ready = this._runInitSequence();
        // Re-raised by every public method; mark handled so it never surfaces on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the initialisation sequence started by the constructor.
     * @returns {Promise<void>}
     * @throws {Error} If `WHO_AM_I` is not 0x05 (wrong chip, wrong address, or wiring problem).
     */
    async init() {
        await this._ready;
    }

    async _runInitSequence() {
        await _sleep(15);
        await this._conn.writeReg(_REG_RESET, _RESET_CMD);
        await _sleep(15);
        await this._conn.readReg(_REG_RESET_STATUS, 1);   // 0x80 after reset, informational
        const who = (await this._conn.readReg(_REG_WHO_AM_I, 1))[0];
        if (who !== _WHO_AM_I_VALUE) {
            throw new Error('QMI8658A WHO_AM_I: expected 0x' + _WHO_AM_I_VALUE.toString(16).padStart(2, '0') +
                ', got 0x' + who.toString(16).padStart(2, '0'));
        }
        this._ctrl1 = 0x40; this._ctrl7 = 0x03; this._ctrl8 = 0x80;
        this._fifoCtrl = 0; this._fifoWtm = 0; this._sync = false;
        await this._conn.writeReg(_REG_CTRL1, this._ctrl1);   // ADDR_AI = 1, BE = 0
        await this._conn.writeReg(_REG_CTRL8, this._ctrl8);   // handshake by polling
        this._accelFs = 0; this._gyroFs = 4; this._accelOdr = 5; this._gyroOdr = 5;
        this._updateSens();
        await this._conn.writeReg(_REG_CTRL2, 0x05);
        await this._conn.writeReg(_REG_CTRL3, 0x45);
        await this._conn.writeReg(_REG_CTRL5, 0x77);
        await this._conn.writeReg(_REG_CTRL7, this._ctrl7);
        await _sleep(165);                                    // gyroscope turn-on
    }

    _updateSens() {
        this._accelSens = 16384 >> this._accelFs;
        this._gyroSens = 2048 >> this._gyroFs;
    }

    async _readData(reg, length) {
        return this._conn.readReg(reg, length);
    }

    async _vec(reg, sens, scale) {
        const b = await this._readData(reg, 6);
        return [0, 2, 4].map((o) => b.readInt16LE(o) / sens * scale);
    }

    /**
     * Read 3-axis linear acceleration.
     * @returns {Promise<number[]>} `[x, y, z]` in m/s².
     */
    async accel() {
        await this._ready;
        return this._vec(_REG_AX_L, this._accelSens, _G);
    }

    /**
     * Read 3-axis angular rate.
     * @returns {Promise<number[]>} `[x, y, z]` in rad/s.
     */
    async gyro() {
        await this._ready;
        return this._vec(_REG_GX_L, this._gyroSens, _DEG_TO_RAD);
    }
}

/**
 * QMI8658A full interface — extends `QMI8658AMinimal`.
 *
 * Adds raw and burst reads, temperature, sample counter, full-scale / ODR /
 * filter configuration, power control, SyncSample data locking, the FIFO, the
 * motion engines (any / no / significant motion, tap, pedometer,
 * wake-on-motion), offsets, calibration-on-demand, self-test, device info,
 * pull-up control and interrupts.
 */
class QMI8658AFull extends QMI8658AMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C, SMBus or SPI register connection.
     */
    constructor(connection) {
        super(connection);
        this._any = { thr: 0.25, win: 3, axes: 0x07, and: false };
        this._no = { thr: 0.125, win: 30, axes: 0x07, and: true };
        this._sig = { wait: 150, confirm: 600 };
        this._intSources = 0;
        this._fifoPin = 2;
        this._activityPin = 2;
        this._womPin = 1;
        this._intCallback = null;
        this._intHandler = null;
        this._pollTimer = null;
    }

    async _w(reg, value) {
        await this._conn.writeReg(reg, value);
    }

    async _r1(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _setCtrl1(v) { this._ctrl1 = v & 0xFF; await this._w(_REG_CTRL1, this._ctrl1); }
    async _setCtrl7(v) { this._ctrl7 = v & 0xFF; await this._w(_REG_CTRL7, this._ctrl7); }
    async _setCtrl8(v) { this._ctrl8 = v & 0xFF; await this._w(_REG_CTRL8, this._ctrl8); }

    async _waitStatus(mask, want, timeoutMs, what) {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            const st = await this._r1(_REG_STATUSINT);
            if (((st & mask) !== 0) === want) return st;
            if (Date.now() > deadline) throw new Error('QMI8658A timeout waiting for ' + what);
            await _sleep(1);
        }
    }

    async _ctrl9(cmd, timeoutMs = 100) {
        await this._w(_REG_CTRL9, cmd);
        await this._waitStatus(0x80, true, timeoutMs, 'CTRL9 CmdDone');
        await this._w(_REG_CTRL9, _CMD_ACK);
        await this._waitStatus(0x80, false, timeoutMs, 'CTRL9 ack');
    }

    async _calCmd(cmd, params, timeoutMs = 100) {
        for (let i = 0; i < 8; i++) await this._w(_REG_CAL1_L + i, (params[i] || 0) & 0xFF);
        await this._ctrl9(cmd, timeoutMs);
    }

    async _twoSets(cmd, set1, set2) {
        await this._calCmd(cmd, [...set1, ...new Array(7 - set1.length).fill(0), 0x10]);
        await this._calCmd(cmd, [...set2, ...new Array(7 - set2.length).fill(0), 0x20]);
    }

    async _sensorsOff() {
        const prev = this._ctrl7 & 0x03;
        if (prev) {
            await this._setCtrl7(this._ctrl7 & ~0x03);
            await _sleep(5);
        }
        return prev;
    }

    async _sensorsRestore(prev) {
        if (prev) {
            await this._setCtrl7(this._ctrl7 | prev);
            await _sleep((prev & 0x02) ? 165 : 10);
        }
    }

    async _readData(reg, length) {
        if (this._sync) {
            await this._waitStatus(0x01, true, 100, 'SyncSample Avail');
            await this._waitStatus(0x02, true, 100, 'SyncSample Locked');
        }
        return this._conn.readReg(reg, length);
    }

    /**
     * Read raw accelerometer counts.
     * @returns {Promise<number[]>} Signed 16-bit `[x, y, z]`.
     */
    async accelRaw() {
        await this._ready;
        const b = await this._readData(_REG_AX_L, 6);
        return [b.readInt16LE(0), b.readInt16LE(2), b.readInt16LE(4)];
    }

    /**
     * Read raw gyroscope counts.
     * @returns {Promise<number[]>} Signed 16-bit `[x, y, z]`.
     */
    async gyroRaw() {
        await this._ready;
        const b = await this._readData(_REG_GX_L, 6);
        return [b.readInt16LE(0), b.readInt16LE(2), b.readInt16LE(4)];
    }

    /**
     * Burst-read temperature, acceleration and angular rate in one sample.
     * @returns {Promise<Array>} `[[ax, ay, az], [gx, gy, gz], tempC]` — m/s², rad/s, °C.
     */
    async readAll() {
        await this._ready;
        const b = await this._readData(_REG_TEMP_L, 14);
        const accel = [2, 4, 6].map((o) => b.readInt16LE(o) / this._accelSens * _G);
        const gyro = [8, 10, 12].map((o) => b.readInt16LE(o) / this._gyroSens * _DEG_TO_RAD);
        return [accel, gyro, b.readInt16LE(0) / 256.0];
    }

    /**
     * Read the die temperature.
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        await this._ready;
        const b = await this._readData(_REG_TEMP_L, 2);
        return b.readInt16LE(0) / 256.0;
    }

    /**
     * Read the 24-bit sample counter.
     * @returns {Promise<number>} Counter value, wraps at 0xFFFFFF.
     */
    async timestamp() {
        await this._ready;
        const b = await this._conn.readReg(_REG_TIMESTAMP, 3);
        return b[0] | (b[1] << 8) | (b[2] << 16);
    }

    /**
     * Check for new sensor data (`STATUS0` aDA / gDA, `STATUSINT.Avail` while SyncSample is on).
     * @returns {Promise<boolean>}
     */
    async dataReady() {
        await this._ready;
        if (this._sync) return (await this._r1(_REG_STATUSINT) & 0x01) !== 0;
        return (await this._r1(_REG_STATUS0) & 0x03) !== 0;
    }

    /**
     * Configure the accelerometer (CTRL2).
     * @param {number} [fullScale=0] - 0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g.
     * @param {number} [odr=5] - ODR code 0–8 or 12–15 (12–15 only with the gyroscope off).
     * @returns {Promise<void>}
     */
    async configureAccel(fullScale = 0, odr = 5) {
        await this._ready;
        if (!(fullScale >= 0 && fullScale <= 3)) throw new RangeError('fullScale must be 0-3');
        if (!((odr >= 0 && odr <= 8) || (odr >= 12 && odr <= 15))) throw new RangeError('odr must be 0-8 or 12-15');
        this._accelFs = fullScale;
        this._accelOdr = odr;
        this._updateSens();
        await this._w(_REG_CTRL2, (fullScale << 4) | odr);
    }

    /**
     * Configure the gyroscope (CTRL3).
     * @param {number} [fullScale=4] - 0 = ±16 dps … 7 = ±2048 dps (doubling).
     * @param {number} [odr=5] - ODR code 0–8.
     * @returns {Promise<void>}
     */
    async configureGyro(fullScale = 4, odr = 5) {
        await this._ready;
        if (!(fullScale >= 0 && fullScale <= 7)) throw new RangeError('fullScale must be 0-7');
        if (!(odr >= 0 && odr <= 8)) throw new RangeError('odr must be 0-8');
        this._gyroFs = fullScale;
        this._gyroOdr = odr;
        this._updateSens();
        await this._w(_REG_CTRL3, (fullScale << 4) | odr);
    }

    /**
     * Configure the low-pass filters (CTRL5).
     * @param {boolean} [accelFilter=true] - Enable the accelerometer filter.
     * @param {number} [accelMode=3] - 0–3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR.
     * @param {boolean} [gyroFilter=true] - Enable the gyroscope filter.
     * @param {number} [gyroMode=3] - 0–3, same bandwidths.
     * @returns {Promise<void>}
     */
    async configureFilters(accelFilter = true, accelMode = 3, gyroFilter = true, gyroMode = 3) {
        await this._ready;
        if (!(accelMode >= 0 && accelMode <= 3 && gyroMode >= 0 && gyroMode <= 3)) {
            throw new RangeError('filter mode must be 0-3');
        }
        await this._w(_REG_CTRL5, ((gyroMode & 3) << 5) | (gyroFilter ? 0x10 : 0) |
            ((accelMode & 3) << 1) | (accelFilter ? 1 : 0));
    }

    /**
     * Enable or disable the sensors (CTRL7 aEN / gEN / gSN). The gyroscope needs
     * 150 ms + 3/ODR, the accelerometer 3 ms + 3/ODR before data is valid.
     * @param {boolean} [accel=true]
     * @param {boolean} [gyro=true]
     * @param {boolean} [gyroSnooze=false]
     * @returns {Promise<void>}
     */
    async setSensors(accel = true, gyro = true, gyroSnooze = false) {
        await this._ready;
        const v = (this._ctrl7 & ~0x13) | (accel ? 0x01 : 0) | (gyro ? 0x02 : 0) | (gyroSnooze ? 0x10 : 0);
        await this._setCtrl7(v);
    }

    /**
     * Power the chip down (CTRL1.SensorDisable, sensors disabled first) or wake it.
     * @param {boolean} [down=true]
     * @returns {Promise<void>}
     */
    async setPowerDown(down = true) {
        await this._ready;
        if (down) {
            await this._setCtrl7(this._ctrl7 & ~0x03);
            await this._setCtrl1(this._ctrl1 | 0x01);
        } else {
            await this._setCtrl1(this._ctrl1 & ~0x01);
            await _sleep(15);
        }
    }

    /**
     * Enable or disable SyncSample (data-locking) mode; incompatible with the FIFO and motion engines.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setSyncSample(enabled) {
        await this._ready;
        if (enabled) {
            await this._calCmd(_CMD_AHB_CLOCK_GATING, [1]);
            await this._setCtrl7(this._ctrl7 | 0x80);
        } else {
            await this._setCtrl7(this._ctrl7 & ~0x80);
            await this._calCmd(_CMD_AHB_CLOCK_GATING, [0]);
        }
        this._sync = !!enabled;
    }

    /**
     * Configure the FIFO.
     * @param {number} [mode=2] - 0 = bypass, 1 = FIFO (stops when full), 2 = stream.
     * @param {number} [size=3] - 0–3 = 16 / 32 / 64 / 128 samples.
     * @param {number} [watermark=0] - Watermark in samples (0 disables).
     * @returns {Promise<void>}
     */
    async configureFifo(mode = 2, size = 3, watermark = 0) {
        await this._ready;
        if (!(mode >= 0 && mode <= 2 && size >= 0 && size <= 3)) throw new RangeError('mode must be 0-2 and size 0-3');
        this._fifoWtm = watermark & 0xFF;
        await this._w(_REG_FIFO_WTM_TH, this._fifoWtm);
        this._fifoCtrl = (size << 2) | mode;
        await this._w(_REG_FIFO_CTRL, this._fifoCtrl);
        if (mode === 0) await this._setCtrl7(this._ctrl7 & ~0x20);   // DRDY back on
    }

    /**
     * Read the raw `FIFO_STATUS` byte (full 7, watermark 6, overflow 5, not-empty 4).
     * @returns {Promise<number>}
     */
    async fifoStatus() {
        await this._ready;
        return this._r1(_REG_FIFO_STATUS);
    }

    /**
     * Number of bytes in the FIFO.
     * @returns {Promise<number>} 2 × the sample-word count.
     */
    async fifoCount() {
        await this._ready;
        const st = await this._r1(_REG_FIFO_STATUS);
        const lo = await this._r1(_REG_FIFO_SMPL_CNT);
        return 2 * (((st & 0x03) << 8) | lo);
    }

    /**
     * Drain the FIFO with the read-mode procedure (little-endian 6-byte frames, accelerometer first).
     * @param {number} [count] - Maximum number of bytes (default: all available).
     * @returns {Promise<Buffer>}
     */
    async readFifo(count) {
        await this._ready;
        const total = await this.fifoCount();
        const n = count === undefined || count === null ? total : Math.min(count, total);
        if (n <= 0) return Buffer.alloc(0);
        await this._ctrl9(_CMD_REQ_FIFO);
        await this._w(_REG_CTRL1, this._ctrl1 & ~0x40);   // ADDR_AI off: FIFO_DATA repeats
        const chunks = [];
        let got = 0;
        try {
            while (got < n) {
                const chunk = Math.min(32, n - got);
                chunks.push(Buffer.from(await this._conn.readReg(_REG_FIFO_DATA, chunk)));
                got += chunk;
            }
        } finally {
            await this._w(_REG_CTRL1, this._ctrl1);
            await this._w(_REG_FIFO_CTRL, this._fifoCtrl & 0x7F);
        }
        return Buffer.concat(chunks);
    }

    /**
     * Reset the FIFO (CTRL9 RST_FIFO).
     * @returns {Promise<void>}
     */
    async resetFifo() {
        await this._ready;
        await this._ctrl9(_CMD_RST_FIFO);
    }

    async _sendMotion() {
        const prev = await this._sensorsOff();
        try {
            const a = _clampU8(_round(this._any.thr * 32));
            const n = _clampU8(_round(this._no.thr * 32));
            const mode = (this._no.and ? 0x80 : 0) | ((this._no.axes & 7) << 4) |
                (this._any.and ? 0x08 : 0) | (this._any.axes & 7);
            const sw = this._sig.wait, cw = this._sig.confirm;
            await this._twoSets(_CMD_CONFIG_MOTION,
                [a, a, a, n, n, n, mode],
                [this._any.win & 0xFF, this._no.win & 0xFF, sw & 0xFF, (sw >> 8) & 0xFF, cw & 0xFF, (cw >> 8) & 0xFF]);
        } finally {
            await this._sensorsRestore(prev);
        }
    }

    /**
     * Configure the any-motion engine.
     * @param {number} [thresholdG=0.25] - Slope threshold in g (1/32 g steps, 0–7.97).
     * @param {number} [window=3] - Window in samples.
     * @param {number} [axes=0b111] - Axis mask (bit 0 X, 1 Y, 2 Z).
     * @param {boolean} [logicAnd=false] - AND instead of OR over the enabled axes.
     * @returns {Promise<void>}
     */
    async configureAnyMotion(thresholdG = 0.25, window = 3, axes = 0b111, logicAnd = false) {
        await this._ready;
        this._any = { thr: thresholdG, win: window, axes, and: logicAnd };
        await this._sendMotion();
    }

    /**
     * Configure the no-motion engine (arguments as for `configureAnyMotion`).
     * @param {number} [thresholdG=0.125]
     * @param {number} [window=30]
     * @param {number} [axes=0b111]
     * @param {boolean} [logicAnd=true]
     * @returns {Promise<void>}
     */
    async configureNoMotion(thresholdG = 0.125, window = 30, axes = 0b111, logicAnd = true) {
        await this._ready;
        this._no = { thr: thresholdG, win: window, axes, and: logicAnd };
        await this._sendMotion();
    }

    /**
     * Configure the significant-motion engine; requires any- and no-motion configured and enabled.
     * @param {number} [waitWindow=150] - Wait window in samples (16-bit).
     * @param {number} [confirmWindow=600] - Confirmation window in samples (16-bit).
     * @returns {Promise<void>}
     */
    async configureSignificantMotion(waitWindow = 150, confirmWindow = 600) {
        await this._ready;
        this._sig = { wait: waitWindow, confirm: confirmWindow };
        await this._sendMotion();
    }

    /**
     * Enable the motion engines (CTRL8 bits 1–3).
     * @param {boolean} [anyMotion=false]
     * @param {boolean} [noMotion=false]
     * @param {boolean} [significant=false]
     * @returns {Promise<void>}
     */
    async enableMotion(anyMotion = false, noMotion = false, significant = false) {
        await this._ready;
        await this._setCtrl8((this._ctrl8 & ~0x0E) | (anyMotion ? 0x02 : 0) | (noMotion ? 0x04 : 0) |
            (significant ? 0x08 : 0));
    }

    /**
     * Configure the tap engine (two CTRL9 CONFIGURE_TAP commands).
     * @param {number} [priority=0] - Axis priority 0–5.
     * @param {number} [peakWindow=20] - Peak window in samples.
     * @param {number} [tapWindow=50] - Quiet window in samples.
     * @param {number} [doubleTapWindow=250] - Double-tap window in samples.
     * @param {number} [alpha=0.0625] - Acceleration averaging weight (1/128 steps).
     * @param {number} [gamma=0.25] - Magnitude averaging weight (1/128 steps).
     * @param {number} [peakThreshold=0.8] - Peak threshold in g² (1/1024 steps).
     * @param {number} [quietThreshold=0.4] - Undefined-motion threshold in g² (1/1024 steps).
     * @returns {Promise<void>}
     */
    async configureTap(priority = 0, peakWindow = 20, tapWindow = 50, doubleTapWindow = 250,
        alpha = 0.0625, gamma = 0.25, peakThreshold = 0.8, quietThreshold = 0.4) {
        await this._ready;
        const peak = _round(peakThreshold * 1024);
        const quiet = _round(quietThreshold * 1024);
        const prev = await this._sensorsOff();
        try {
            await this._twoSets(_CMD_CONFIG_TAP,
                [peakWindow & 0xFF, priority & 0xFF, tapWindow & 0xFF, (tapWindow >> 8) & 0xFF,
                    doubleTapWindow & 0xFF, (doubleTapWindow >> 8) & 0xFF],
                [_round(alpha * 128) & 0x7F, _round(gamma * 128) & 0x7F, peak & 0xFF, (peak >> 8) & 0xFF,
                    quiet & 0xFF, (quiet >> 8) & 0xFF]);
        } finally {
            await this._sensorsRestore(prev);
        }
    }

    /**
     * Enable or disable the tap engine (CTRL8.Tap_EN). Use an accelerometer ODR above 200 Hz.
     * @param {boolean} [enabled=true]
     * @returns {Promise<void>}
     */
    async enableTap(enabled = true) {
        await this._ready;
        await this._setCtrl8(enabled ? (this._ctrl8 | 0x01) : (this._ctrl8 & ~0x01));
    }

    /**
     * Read the last tap event.
     * @returns {Promise<Array>} `[count, axis, negative]` — count 0/1/2, axis 0 none / 1 X / 2 Y / 3 Z.
     */
    async tapStatus() {
        await this._ready;
        const v = await this._r1(_REG_TAP_STATUS);
        return [v & 0x03, (v >> 4) & 0x03, (v & 0x80) !== 0];
    }

    /**
     * Configure the pedometer (two CTRL9 CONFIGURE_PEDOMETER commands; defaults for a 50 Hz ODR).
     * @param {number} [sampleCount=50]
     * @param {number} [peakToPeakMg=200]
     * @param {number} [peakMg=100]
     * @param {number} [timeUp=200]
     * @param {number} [timeLow=20]
     * @param {number} [entryCount=10]
     * @param {number} [precision=0]
     * @param {number} [reportEvery=4] - Steps per pedometer event / count update.
     * @returns {Promise<void>}
     */
    async configurePedometer(sampleCount = 50, peakToPeakMg = 200, peakMg = 100, timeUp = 200,
        timeLow = 20, entryCount = 10, precision = 0, reportEvery = 4) {
        await this._ready;
        const p2p = _round(peakToPeakMg / 1000 * 1024);
        const pk = _round(peakMg / 1000 * 1024);
        const prev = await this._sensorsOff();
        try {
            await this._twoSets(_CMD_CONFIG_PEDOMETER,
                [sampleCount & 0xFF, (sampleCount >> 8) & 0xFF, p2p & 0xFF, (p2p >> 8) & 0xFF, pk & 0xFF, (pk >> 8) & 0xFF],
                [timeUp & 0xFF, (timeUp >> 8) & 0xFF, timeLow & 0xFF, entryCount & 0xFF, precision & 0xFF, reportEvery & 0xFF]);
        } finally {
            await this._sensorsRestore(prev);
        }
    }

    /**
     * Enable or disable the pedometer (CTRL8.Pedo_EN); a 0→1 transition resets the count.
     * @param {boolean} [enabled=true]
     * @returns {Promise<void>}
     */
    async enablePedometer(enabled = true) {
        await this._ready;
        await this._setCtrl8(enabled ? (this._ctrl8 | 0x10) : (this._ctrl8 & ~0x10));
    }

    /**
     * Read the 24-bit step count.
     * @returns {Promise<number>}
     */
    async stepCount() {
        await this._ready;
        const b = await this._conn.readReg(_REG_STEP_CNT, 3);
        return b[0] | (b[1] << 8) | (b[2] << 16);
    }

    /**
     * Clear the step count (CTRL9 RESET_PEDOMETER).
     * @returns {Promise<void>}
     */
    async resetStepCount() {
        await this._ready;
        await this._ctrl9(_CMD_RESET_PEDOMETER);
    }

    /**
     * Enter or leave wake-on-motion mode. Uses accelerometer ±2 g at ODR code 12 (128 Hz).
     * @param {number} thresholdMg - 1–255 mg enables, 0 exits WoM mode.
     * @param {number} [interruptPin=1] - 1 or 2.
     * @param {number} [initialLevel=0] - Initial pin level 0 or 1.
     * @param {number} [blankingSamples=0] - Samples ignored after enabling (0–63).
     * @returns {Promise<void>}
     */
    async setWakeOnMotion(thresholdMg, interruptPin = 1, initialLevel = 0, blankingSamples = 0) {
        await this._ready;
        await this._sensorsOff();
        if (thresholdMg === 0) {
            await this._calCmd(_CMD_WRITE_WOM, [0, 0]);
            this._intSources &= ~QMI8658ASource.WAKE_ON_MOTION;
            await this._updateIntPins();
            return;
        }
        this._accelFs = 0; this._accelOdr = 12;
        this._updateSens();
        await this._w(_REG_CTRL2, 0x0C);
        this._womPin = interruptPin === 2 ? 2 : 1;
        const sel = (this._womPin === 2 ? 0x40 : 0x00) | (initialLevel ? 0x80 : 0x00);
        await this._calCmd(_CMD_WRITE_WOM, [Math.min(255, thresholdMg), sel | (blankingSamples & 0x3F)]);
        this._intSources |= QMI8658ASource.WAKE_ON_MOTION;
        await this._updateIntPins();
        await this._setCtrl7((this._ctrl7 & ~0x03) | 0x01);
        await _sleep(10);
    }

    async _offsetCmd(cmd, codes) {
        const prev = await this._sensorsOff();
        try {
            const params = [];
            for (const c of codes) params.push(c & 0xFF, (c >> 8) & 0xFF);
            await this._calCmd(cmd, params);
        } finally {
            await this._sensorsRestore(prev);
        }
    }

    /**
     * Apply an accelerometer offset (signed 4.12 g; volatile).
     * @param {number} x - X offset in m/s².
     * @param {number} y - Y offset in m/s².
     * @param {number} z - Z offset in m/s².
     * @returns {Promise<void>}
     */
    async setAccelOffset(x, y, z) {
        await this._ready;
        await this._offsetCmd(_CMD_ACCEL_OFFSET, [x, y, z].map((v) => _round(v / _G * 4096) & 0xFFFF));
    }

    /**
     * Apply a gyroscope offset (signed 11.5 dps; volatile).
     * @param {number} x - X offset in rad/s.
     * @param {number} y - Y offset in rad/s.
     * @param {number} z - Z offset in rad/s.
     * @returns {Promise<void>}
     */
    async setGyroOffset(x, y, z) {
        await this._ready;
        await this._offsetCmd(_CMD_GYRO_OFFSET, [x, y, z].map((v) => _round(v / _DEG_TO_RAD * 32) & 0xFFFF));
    }

    /**
     * Run gyroscope calibration-on-demand (about 1.5 s; keep the device still).
     * @returns {Promise<Array>} `[success, codStatus]`.
     */
    async calibrateGyro() {
        await this._ready;
        const prev = await this._sensorsOff();
        let status;
        try {
            await this._ctrl9(_CMD_COD, 3000);
            status = await this._r1(_REG_COD_STATUS);
        } finally {
            await this._sensorsRestore(prev);
        }
        return [status === 0, status];
    }

    /**
     * Read the raw gyroscope gains, valid right after `calibrateGyro()`.
     * @returns {Promise<number[]>} Raw 16-bit `[x, y, z]` gains.
     */
    async getGyroGains() {
        await this._ready;
        const b = await this._conn.readReg(_REG_DVX_L, 6);
        return [b.readUInt16LE(0), b.readUInt16LE(2), b.readUInt16LE(4)];
    }

    /**
     * Restore previously saved gyroscope gains.
     * @param {number} x - Raw X gain.
     * @param {number} y - Raw Y gain.
     * @param {number} z - Raw Z gain.
     * @returns {Promise<void>}
     */
    async setGyroGains(x, y, z) {
        await this._ready;
        const prev = await this._sensorsOff();
        try {
            await this._calCmd(_CMD_APPLY_GYRO_GAINS,
                [x & 0xFF, (x >> 8) & 0xFF, y & 0xFF, (y >> 8) & 0xFF, z & 0xFF, (z >> 8) & 0xFF]);
        } finally {
            await this._sensorsRestore(prev);
        }
    }

    async _selfTest(ctrlReg, saved, scale) {
        const prev = await this._sensorsOff();
        let b;
        try {
            await this._w(ctrlReg, saved | 0x80);
            await this._waitStatus(0x01, true, 3000, 'self-test Avail');
            b = await this._conn.readReg(_REG_DVX_L, 6);
            await this._w(ctrlReg, saved);
            await this._waitStatus(0x01, false, 3000, 'self-test end');
        } finally {
            await this._sensorsRestore(prev);
        }
        return [0, 2, 4].map((o) => b.readInt16LE(o) / scale);
    }

    /**
     * Run the accelerometer self-test.
     * @returns {Promise<Array>} `[passed, [x, y, z]]` — values in m/s²; passes when every axis exceeds 200 mg.
     */
    async selfTestAccel() {
        await this._ready;
        const g = await this._selfTest(_REG_CTRL2, (this._accelFs << 4) | this._accelOdr, 2048);
        return [g.every((v) => Math.abs(v) > 0.2), g.map((v) => v * _G)];
    }

    /**
     * Run the gyroscope self-test.
     * @returns {Promise<Array>} `[passed, [x, y, z]]` — values in rad/s; passes when every axis exceeds 300 dps.
     */
    async selfTestGyro() {
        await this._ready;
        const d = await this._selfTest(_REG_CTRL3, (this._gyroFs << 4) | this._gyroOdr, 16);
        return [d.every((v) => Math.abs(v) > 300), d.map((v) => v * _DEG_TO_RAD)];
    }

    /**
     * Read revision, firmware version and USID (CTRL9 COPY_USID).
     * @returns {Promise<{revision: number, firmware: Buffer, usid: Buffer}>}
     */
    async deviceInfo() {
        await this._ready;
        await this._ctrl9(_CMD_COPY_USID);
        const revision = await this._r1(_REG_REVISION_ID);
        const firmware = Buffer.concat([Buffer.from(await this._conn.readReg(_REG_FW_VERSION, 2)),
            Buffer.from(await this._conn.readReg(_REG_FW_VERSION + 2, 1))]);
        const usid = Buffer.from(await this._conn.readReg(_REG_DVX_L, 6));
        return { revision, firmware, usid };
    }

    /**
     * Control the internal 200 kΩ pull-ups (CTRL9 SET_RPU); `false` disables a group.
     * Disabling `i2c` on a bus without external pull-ups breaks the bus.
     * @param {boolean} [aux=true]
     * @param {boolean} [sdx=true]
     * @param {boolean} [cs=true]
     * @param {boolean} [i2c=true]
     * @returns {Promise<void>}
     */
    async setPullups(aux = true, sdx = true, cs = true, i2c = true) {
        await this._ready;
        await this._calCmd(_CMD_SET_RPU, [(aux ? 0 : 1) | (sdx ? 0 : 2) | (cs ? 0 : 4) | (i2c ? 0 : 8)]);
    }

    /**
     * Soft reset and wait 15 ms; the sensors are off afterwards.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._w(_REG_RESET, _RESET_CMD);
        await _sleep(15);
        this._ctrl1 = 0x40; this._ctrl7 = 0x00; this._ctrl8 = 0x80;
        await this._w(_REG_CTRL1, this._ctrl1);
        await this._w(_REG_CTRL8, this._ctrl8);
        this._sync = false;
        this._intSources = 0;
    }

    _sourcePin(source) {
        if (source === QMI8658ASource.DATA_READY) return 2;
        if (source === QMI8658ASource.FIFO_WATERMARK || source === QMI8658ASource.FIFO_FULL) return this._fifoPin;
        if (source === QMI8658ASource.WAKE_ON_MOTION) return this._womPin;
        return this._activityPin;
    }

    async _updateIntPins() {
        let pins = 0;
        for (let bit = 0; bit < 9; bit++) {
            const s = 1 << bit;
            if (this._intSources & s) pins |= 1 << (this._sourcePin(s) - 1);
        }
        let v = this._ctrl1 & ~0x18;
        v |= ((pins & 1) ? 0x08 : 0) | ((pins & 2) ? 0x10 : 0);
        v = this._fifoPin === 1 ? (v | 0x04) : (v & ~0x04);
        if (v !== this._ctrl1) await this._setCtrl1(v);
    }

    /**
     * Route the FIFO interrupt (CTRL1.FIFO_INT_SEL) and the motion-engine group (CTRL8.ACTIVITY_INT_SEL).
     * @param {number} [fifoPin=2] - 1 or 2.
     * @param {number} [activityPin=2] - 1 or 2.
     * @returns {Promise<void>}
     */
    async setInterruptPins(fifoPin = 2, activityPin = 2) {
        await this._ready;
        this._fifoPin = fifoPin === 1 ? 1 : 2;
        this._activityPin = activityPin === 1 ? 1 : 2;
        await this._setCtrl8(this._activityPin === 1 ? (this._ctrl8 | 0x40) : (this._ctrl8 & ~0x40));
        await this._updateIntPins();
    }

    /**
     * Enable one interrupt source.
     * @param {number} source - One of the `QMI8658ASource` constants.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        await this._ready;
        if (source === QMI8658ASource.DATA_READY) await this._setCtrl7(this._ctrl7 & ~0x20);
        else if (source === QMI8658ASource.FIFO_WATERMARK) await this._w(_REG_FIFO_WTM_TH, this._fifoWtm);
        else if (_ENGINE_BIT[source]) await this._setCtrl8(this._ctrl8 | _ENGINE_BIT[source]);
        this._intSources |= source;
        await this._updateIntPins();
    }

    /**
     * Disable one interrupt source.
     * @param {number} source - One of the `QMI8658ASource` constants.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._ready;
        if (source === QMI8658ASource.DATA_READY) await this._setCtrl7(this._ctrl7 | 0x20);
        else if (source === QMI8658ASource.FIFO_WATERMARK) await this._w(_REG_FIFO_WTM_TH, 0);
        else if (_ENGINE_BIT[source]) await this._setCtrl8(this._ctrl8 & ~_ENGINE_BIT[source]);
        this._intSources &= ~source;
        await this._updateIntPins();
    }

    /**
     * Read STATUS0, STATUS1 and FIFO_STATUS (clearing what clears on read).
     * @returns {Promise<number>} Combined status using the `QMI8658ASource` bit layout.
     */
    async pollInterrupt() {
        await this._ready;
        const s = await this._conn.readReg(_REG_STATUS0, 2);
        const fs = await this._r1(_REG_FIFO_STATUS);
        let status = 0;
        if (s[0] & 0x03) status |= QMI8658ASource.DATA_READY;
        if (fs & 0x40) status |= QMI8658ASource.FIFO_WATERMARK;
        if (fs & 0x80) status |= QMI8658ASource.FIFO_FULL;
        if (s[1] & 0x20) status |= QMI8658ASource.ANY_MOTION;
        if (s[1] & 0x40) status |= QMI8658ASource.NO_MOTION;
        if (s[1] & 0x80) status |= QMI8658ASource.SIGNIFICANT_MOTION;
        if (s[1] & 0x10) status |= QMI8658ASource.STEP;
        if (s[1] & 0x02) status |= QMI8658ASource.TAP;
        if (s[1] & 0x04) status |= QMI8658ASource.WAKE_ON_MOTION;
        return status;
    }

    /**
     * Subscribe to rising edges on the connection's interrupt pin (a 5 ms polling timer when none is attached).
     * @param {function(number): void} callback - Called with the `pollInterrupt()` status.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        this._intCallback = callback;
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin) {
            this._intHandler = () => { this._dispatchInt(); };
            await intPin.onEdge(this._intHandler, 'rising');
        } else {
            this._pollTimer = setInterval(() => { this._dispatchInt(); }, 5);
        }
    }

    /**
     * Unsubscribe from interrupt-pin edges.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        this._intCallback = null;
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin && this._intHandler) await intPin.offEdge(this._intHandler);
        this._intHandler = null;
        if (this._pollTimer !== null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    async _dispatchInt() {
        const cb = this._intCallback;
        if (!cb) return;
        try {
            const status = await this.pollInterrupt();
            if (status) cb(status);
        } catch (err) {
            // ignore: the next edge or poll retries
        }
    }
}

Object.assign(QMI8658AFull, {
    SOURCE_DATA_READY: QMI8658ASource.DATA_READY,
    SOURCE_FIFO_WATERMARK: QMI8658ASource.FIFO_WATERMARK,
    SOURCE_FIFO_FULL: QMI8658ASource.FIFO_FULL,
    SOURCE_ANY_MOTION: QMI8658ASource.ANY_MOTION,
    SOURCE_NO_MOTION: QMI8658ASource.NO_MOTION,
    SOURCE_SIGNIFICANT_MOTION: QMI8658ASource.SIGNIFICANT_MOTION,
    SOURCE_STEP: QMI8658ASource.STEP,
    SOURCE_TAP: QMI8658ASource.TAP,
    SOURCE_WAKE_ON_MOTION: QMI8658ASource.WAKE_ON_MOTION,
});

module.exports = { QMI8658AMinimal, QMI8658AFull, QMI8658ASource };
