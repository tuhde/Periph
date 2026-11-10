'use strict';

const R = {
    GCONF: 0x00, GSTAT: 0x01, IFCNT: 0x02, SLAVECONF: 0x03, IOIN: 0x04, OUTPUT: 0x04,
    X_COMPARE: 0x05, OTP_READ: 0x07, SHORT_CONF: 0x09, DRV_CONF: 0x0A, GLOBALSCALER: 0x0B,
    OFFSET_READ: 0x0C, IHOLD_IRUN: 0x10, TPOWERDOWN: 0x11, TSTEP: 0x12, TPWMTHRS: 0x13,
    TCOOLTHRS: 0x14, THIGH: 0x15, RAMPMODE: 0x20, XACTUAL: 0x21, VACTUAL: 0x22, VSTART: 0x23,
    A1: 0x24, V1: 0x25, AMAX: 0x26, VMAX: 0x27, DMAX: 0x28, D1: 0x2A, VSTOP: 0x2B,
    TZEROWAIT: 0x2C, XTARGET: 0x2D, VDCMIN: 0x33, SW_MODE: 0x34, RAMP_STAT: 0x35, XLATCH: 0x36,
    ENCMODE: 0x38, X_ENC: 0x39, ENC_CONST: 0x3A, ENC_STATUS: 0x3B, ENC_LATCH: 0x3C,
    ENC_DEVIATION: 0x3D, MSLUT0: 0x60, MSLUTSEL: 0x68, MSLUTSTART: 0x69, MSCNT: 0x6A,
    MSCURACT: 0x6B, CHOPCONF: 0x6C, COOLCONF: 0x6D, DCCTRL: 0x6E, DRV_STATUS: 0x6F,
    PWMCONF: 0x70, PWM_SCALE: 0x71, PWM_AUTO: 0x72, LOST_STEPS: 0x73,
};
const READABLE = new Set([0x00, 0x01, 0x02, 0x04, 0x07, 0x0C, 0x12, 0x20, 0x21, 0x22, 0x2D, 0x34,
    0x35, 0x36, 0x38, 0x39, 0x3B, 0x3C, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x72, 0x73]);
const WRITABLE = new Set([0x00, 0x01, 0x03, 0x04, 0x05, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x13, 0x14,
    0x15, 0x20, 0x21, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2A, 0x2B, 0x2C, 0x2D, 0x33, 0x34,
    0x35, 0x38, 0x39, 0x3A, 0x3B, 0x3D, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6C, 0x6D, 0x6E, 0x70]);

const VERSION = 0x30;
const V_FS = 0.325;
const CHOPCONF_DEFAULT = 0x000100C3;
const GCONF_EN_PWM_MODE = 1 << 2;
const GCONF_SHAFT = 1 << 4;
const GCONF_SMALL_HYSTERESIS = 1 << 14;
const GCONF_STOP_ENABLE = 1 << 15;
const GCONF_DIRECT_MODE = 1 << 16;
const SW_SG_STOP = 1 << 10;
const SW_SOFTSTOP = 1 << 11;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const signed = (v, bits) => {
    const x = v & ((1 << bits) - 1);
    return (x & (1 << (bits - 1))) ? x - (1 << bits) : x;
};
const s32 = (v) => v | 0;

/** CRC-8 (polynomial 0x07, init 0, LSB first) over a datagram body. */
function crc8(data) {
    let crc = 0;
    for (let byte of data) {
        for (let i = 0; i < 8; i++) {
            if (((crc >> 7) ^ (byte & 1)) !== 0) crc = ((crc << 1) ^ 0x07) & 0xFF;
            else crc = (crc << 1) & 0xFF;
            byte >>= 1;
        }
    }
    return crc;
}

/**
 * Trinamic TMC5160 stepper controller/driver over SPI or single-wire UART --
 * minimal interface.
 *
 * Brings the chip up with the internal SixPoint ramp generator in positioning
 * mode (256 microsteps, StealthChop below TPWMTHRS = 500, 0.5 A RMS run
 * current, 200 full steps/s, 400 full steps/s^2) and moves the motor with
 * register writes. Positions are in full steps, velocities in full steps per
 * second, accelerations in full steps per second squared.
 *
 * The constructor performs no I/O; call `await init()` before use.
 */
class TMC5160Minimal {
    /**
     * @param {object} connection - SPI connection (Mode 3) or UART connection.
     * @param {object} [options]
     * @param {string} [options.busType='spi'] - 'spi' or 'uart'.
     * @param {number} [options.rSenseOhm=0.075] - External sense resistor in ohm.
     * @param {object|null} [options.enPin=null] - OutputPin wired to DRV_ENN (active low).
     * @param {number} [options.address=0] - UART node address 0-253.
     * @param {boolean} [options.echo=true] - UART: TX and RX share the wire.
     * @param {boolean} [options.multiNode=false] - UART: write SENDDELAY = 2 first.
     * @param {number} [options.fclkHz=12000000] - Chip clock in Hz.
     * @throws {RangeError} If busType is unknown.
     */
    constructor(connection, options = {}) {
        const busType = options.busType ?? 'spi';
        if (busType !== 'spi' && busType !== 'uart') throw new RangeError("busType must be 'spi' or 'uart'");
        this._conn = connection;
        this._uart = busType === 'uart';
        this._rSense = options.rSenseOhm ?? 0.075;
        this._enPin = options.enPin ?? null;
        this._address = options.address ?? 0;
        this._echo = options.echo ?? true;
        this._multiNode = options.multiNode ?? false;
        this._fclk = options.fclkHz ?? 12000000;
        this._shadow = new Map();
        this._usc = 256;
        this._toff = 3;
        this._rampmode = 0;
        this._speed = 200;
        this._speedReg = 0;
        this._accel = 400;
        this._senddelay = 0;
        this._lastMove = [0, 200, 400];
        this._writes = 0;
        this._irun = 0;
        this._ihold = 0;
        this._iholdDelay = 6;
    }

    async _spiSecond() {
        const r = Buffer.from(await this._conn.writeRead(Buffer.from([R.GCONF]), 4));
        if (r.length !== 4) throw new Error('TMC5160: short read');
        return r.readUInt32BE(0);
    }

    async _uartFlush() {
        if (typeof this._conn.flush === 'function') await this._conn.flush();
        await sleep(2);
    }

    async _uartWrite(reg, value) {
        const body = Buffer.alloc(7);
        body[0] = 0x05; body[1] = this._address; body[2] = (reg | 0x80) & 0xFF;
        body.writeUInt32BE(value >>> 0, 3);
        const dg = Buffer.concat([body, Buffer.from([crc8(body)])]);
        await this._conn.write(dg);
        if (this._echo && !Buffer.from(await this._conn.read(8)).equals(dg)) {
            throw new Error('TMC5160: write echo mismatch');
        }
    }

    async _uartRead(reg) {
        const head = Buffer.from([0x05, this._address, reg & 0x7F]);
        const req = Buffer.concat([head, Buffer.from([crc8(head)])]);
        let reply;
        if (this._echo) {
            const raw = Buffer.from(await this._conn.writeRead(req, 12));
            if (raw.length !== 12 || !raw.subarray(0, 4).equals(req)) throw new Error('TMC5160: read echo mismatch');
            reply = raw.subarray(4);
        } else {
            reply = Buffer.from(await this._conn.writeRead(req, 8));
            if (reply.length !== 8) throw new Error('TMC5160: short read');
        }
        if (reply[0] !== 0x05 || reply[1] !== 0xFF || reply[2] !== (reg & 0x7F)) throw new Error('TMC5160: malformed reply');
        if (crc8(reply.subarray(0, 7)) !== reply[7]) throw new Error('TMC5160: reply CRC error');
        return reply.readUInt32BE(3);
    }

    async _writeReg(reg, value, verify = false) {
        value = value >>> 0;
        if (this._uart) {
            const before = verify ? (await this._readReg(R.IFCNT)) & 0xFF : 0;
            for (let attempt = 0; attempt < 2; attempt++) {
                try { await this._uartWrite(reg, value); break; } catch (e) {
                    if (attempt) throw e;
                    await this._uartFlush();
                }
            }
            this._writes++;
            if (verify && (((await this._readReg(R.IFCNT)) & 0xFF) !== ((before + 1) & 0xFF))) {
                throw new Error('TMC5160: IFCNT mismatch (write not acknowledged)');
            }
        } else {
            const f = Buffer.alloc(5);
            f[0] = (reg | 0x80) & 0xFF;
            f.writeUInt32BE(value, 1);
            await this._conn.write(f);
            if (verify && (await this._spiSecond()) !== value) {
                throw new Error('TMC5160: write mirror mismatch (reg 0x' + reg.toString(16) + ')');
            }
        }
        this._shadow.set(reg, value);
    }

    async _readReg(reg) {
        if (this._uart) {
            for (let attempt = 0; attempt < 2; attempt++) {
                try { return await this._uartRead(reg); } catch (e) {
                    if (attempt) throw e;
                    await this._uartFlush();
                }
            }
        }
        await this._conn.writeRead(Buffer.from([reg & 0x7F]), 4);
        return this._spiSecond();
    }

    async _modify(reg, mask, value) {
        const cur = this._shadow.get(reg) ?? 0;
        await this._writeReg(reg, ((cur & ~mask) | (value & mask)) >>> 0);
    }

    _vReg(v) { return Math.round(v * this._usc * 16777216 / this._fclk); }

    _aReg(a) { return Math.round(a * this._usc * 2199023255552 / (this._fclk * this._fclk)); }

    _check(name, value, limit) {
        if (!(value >= 0 && value <= limit)) throw new RangeError(name + ' out of range');
        return value;
    }

    async _applyCurrent(rmsA, verify = false) {
        const iMax = V_FS / (this._rSense * Math.SQRT2);
        const r = rmsA / iMax;
        if (r > 1 || r < 1 / 64) throw new RangeError('current outside ' + (iMax / 64).toFixed(3) + '..' + iMax.toFixed(3) + ' A RMS');
        let irun, scaler;
        if (r >= 0.5) { irun = 31; scaler = Math.round(256 * r); }
        else { scaler = 128; irun = Math.max(0, Math.min(31, Math.round(64 * r) - 1)); }
        await this._writeReg(R.GLOBALSCALER, scaler >= 256 ? 0 : scaler, verify);
        this._irun = irun;
        this._ihold = Math.floor(irun / 3);
        this._iholdDelay = 6;
        await this._writeIholdIrun(verify);
    }

    async _writeIholdIrun(verify = false) {
        await this._writeReg(R.IHOLD_IRUN,
            (this._ihold & 0x1F) | ((this._irun & 0x1F) << 8) | ((this._iholdDelay & 0xF) << 16), verify);
    }

    /**
     * Probe the chip, apply the default configuration and verify the writes.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not answer, is strapped for STEP/DIR
     *   operation or a write is not acknowledged.
     */
    async init() {
        if (this._enPin) await this._enPin.set(true);
        await sleep(10);
        const v = !this._uart;
        const base = this._uart ? (await this._readReg(R.IFCNT)) & 0xFF : 0;
        const ioin = await this._readReg(R.IOIN);
        if (((ioin >>> 24) & 0xFF) !== VERSION) throw new Error('TMC5160 not found (IOIN=0x' + ioin.toString(16) + ')');
        if (ioin & (1 << 6)) throw new Error('TMC5160 is strapped for STEP/DIR operation (SD_MODE = 1)');
        this._writes = 0;
        if (this._uart && this._multiNode) {
            this._senddelay = 2;
            await this._writeReg(R.SLAVECONF, 2 << 8, true);
        }
        await this._readReg(R.GSTAT);
        await this._writeReg(R.GSTAT, 0x07, v);
        await this._writeReg(R.GLOBALSCALER, 0, v);
        await this._applyCurrent(Math.min(0.5, V_FS / (this._rSense * Math.SQRT2)), v);
        await this._writeReg(R.TPOWERDOWN, 10, v);
        await this._writeReg(R.CHOPCONF, CHOPCONF_DEFAULT, v);
        await this._writeReg(R.GCONF, GCONF_EN_PWM_MODE, v);
        await this._writeReg(R.TPWMTHRS, 500, v);
        await this._writeReg(R.RAMPMODE, 0, v);
        await this._writeReg(R.VSTART, 0, v);
        await this._writeReg(R.V1, 0, v);
        await this._writeReg(R.VSTOP, 10, v);
        await this._writeReg(R.TZEROWAIT, 0, v);
        await this.setSpeed(200);
        await this.setAcceleration(400);
        if (this._uart && (((await this._readReg(R.IFCNT)) & 0xFF) !== ((base + this._writes) & 0xFF))) {
            throw new Error('TMC5160: IFCNT mismatch (writes not acknowledged)');
        }
        if ((await this._readReg(R.GCONF)) !== this._shadow.get(R.GCONF) ||
            (await this._readReg(R.CHOPCONF)) !== this._shadow.get(R.CHOPCONF)) {
            throw new Error('TMC5160: GCONF/CHOPCONF readback mismatch');
        }
        this._rampmode = 0;
    }

    /** Switch the power stage on and wait 150 ms for StealthChop to settle. @returns {Promise<void>} */
    async enable() {
        if (this._enPin) await this._enPin.set(false);
        else await this._writeReg(R.CHOPCONF, ((this._shadow.get(R.CHOPCONF) & ~0xF) | this._toff) >>> 0);
        await sleep(150);
    }

    /** Switch the power stage off; the motor outputs float. @returns {Promise<void>} */
    async disable() {
        if (this._enPin) { await this._enPin.set(true); return; }
        const chop = this._shadow.get(R.CHOPCONF);
        if (chop & 0xF) this._toff = chop & 0xF;
        await this._writeReg(R.CHOPCONF, (chop & ~0xF) >>> 0);
    }

    /**
     * Set the run current (hold current becomes one third): GLOBALSCALER and IHOLD_IRUN.
     * @param {number} rmsA - Run current in A RMS.
     * @returns {Promise<void>}
     * @throws {RangeError} If above the sense-resistor maximum or below 1/64 of it.
     */
    async setCurrent(rmsA) { await this._applyCurrent(rmsA); }

    /**
     * Set VMAX, the target velocity of positioning moves and velocity mode.
     * @param {number} fullStepsPerS - Speed in full steps per second (> 0).
     * @returns {Promise<void>}
     * @throws {RangeError} If not positive or out of range.
     */
    async setSpeed(fullStepsPerS) {
        if (!(fullStepsPerS > 0)) throw new RangeError('speed must be > 0');
        const reg = this._check('VMAX', this._vReg(fullStepsPerS), (1 << 23) - 512);
        this._speed = fullStepsPerS;
        this._speedReg = reg;
        if (this._rampmode === 0) await this._writeReg(R.VMAX, reg);
    }

    /**
     * Set a single trapezoid: A1 = AMAX = DMAX = D1 (with V1 = 0).
     * @param {number} fullStepsPerS2 - Acceleration in full steps per second squared (> 0).
     * @returns {Promise<void>}
     * @throws {RangeError} If not positive or out of range.
     */
    async setAcceleration(fullStepsPerS2) {
        if (!(fullStepsPerS2 > 0)) throw new RangeError('acceleration must be > 0');
        const reg = this._check('acceleration', this._aReg(fullStepsPerS2), 0xFFFF);
        if (reg < 1) throw new RangeError('acceleration too small');
        this._accel = fullStepsPerS2;
        for (const n of ['A1', 'AMAX', 'DMAX', 'D1']) await this._writeReg(R[n], reg);
    }

    /**
     * Start a positioning move to an absolute position (non-blocking).
     * @param {number} position - Target in full steps.
     * @returns {Promise<void>}
     */
    async moveTo(position) {
        const target = Math.round(position * this._usc);
        this._lastMove = [Math.abs(position - await this.position()), this._speed, this._accel];
        await this._writeReg(R.VMAX, this._speedReg);
        await this._writeReg(R.RAMPMODE, 0);
        this._rampmode = 0;
        await this._writeReg(R.XTARGET, target >>> 0);
    }

    /**
     * Blocking relative move; returns when the target is reached.
     * @param {number} fullSteps - Distance in full steps (sign = direction).
     * @param {number} [fullStepsPerS] - Optional speed override (restored afterwards).
     * @returns {Promise<void>}
     * @throws {Error} On timeout.
     */
    async move(fullSteps, fullStepsPerS) {
        const saved = this._speed;
        if (fullStepsPerS !== undefined) await this.setSpeed(fullStepsPerS);
        try {
            await this.moveTo((await this.position()) + fullSteps);
            await this.waitUntilReached();
        } finally {
            if (fullStepsPerS !== undefined) await this.setSpeed(saved);
        }
    }

    /**
     * Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill.
     * @param {number} fullStepsPerS - Velocity in full steps per second.
     * @returns {Promise<void>}
     */
    async run(fullStepsPerS) {
        const reg = this._check('VMAX', this._vReg(Math.abs(fullStepsPerS)), (1 << 23) - 512);
        const mode = fullStepsPerS < 0 ? 2 : 1;
        await this._writeReg(R.RAMPMODE, mode);
        this._rampmode = mode;
        await this._writeReg(R.VMAX, reg);
    }

    /** Controlled stop: velocity mode with VMAX = 0. @returns {Promise<void>} */
    async stop() {
        await this._writeReg(R.RAMPMODE, 1);
        this._rampmode = 1;
        await this._writeReg(R.VMAX, 0);
    }

    /** @returns {Promise<number>} XACTUAL in full steps. */
    async position() { return s32(await this._readReg(R.XACTUAL)) / this._usc; }

    /**
     * Define the current position (no motion): writes XACTUAL and XTARGET.
     * @param {number} position - New position in full steps.
     * @returns {Promise<void>}
     */
    async setPosition(position) {
        const v = Math.round(position * this._usc) >>> 0;
        await this._writeReg(R.XACTUAL, v);
        await this._writeReg(R.XTARGET, v);
    }

    /** @returns {Promise<number>} VACTUAL in full steps per second, signed. */
    async velocity() {
        return signed(await this._readReg(R.VACTUAL), 24) * (this._fclk / 2 / 8388608) / this._usc;
    }

    /** @returns {Promise<boolean>} RAMP_STAT.position_reached. */
    async isPositionReached() { return ((await this._readReg(R.RAMP_STAT)) & (1 << 9)) !== 0; }

    /**
     * Poll RAMP_STAT until position_reached (positioning) or vzero (velocity mode).
     * @param {number} [timeoutS] - Timeout in seconds; estimated from the last move if omitted.
     * @returns {Promise<boolean>} True once reached.
     * @throws {Error} On timeout.
     */
    async waitUntilReached(timeoutS) {
        if (timeoutS === undefined) {
            const [d, s, a] = this._lastMove;
            timeoutS = 2 * d / s + 2 * s / a + 1;
        }
        const bit = this._rampmode === 0 ? (1 << 9) : (1 << 10);
        await sleep(1);
        let waited = 0;
        for (;;) {
            if ((await this._readReg(R.RAMP_STAT)) & bit) return true;
            if (waited >= timeoutS) throw new Error('TMC5160: target not reached (timeout)');
            await sleep(5);
            waited += 0.005;
        }
    }

    /** @returns {Promise<boolean>} DRV_STATUS.stst. */
    async isStandstill() { return ((await this._readReg(R.DRV_STATUS)) >>> 31) === 1; }

    /** @returns {Promise<number>} Bitmask of FAULT_* constants, 0 when none. */
    async readFault() {
        const s = await this._readReg(R.DRV_STATUS);
        let f = 0;
        if (s & (1 << 26)) f |= TMC5160Minimal.FAULT_OVERTEMP_WARNING;
        if (s & (1 << 25)) f |= TMC5160Minimal.FAULT_OVERTEMP;
        if (s & (3 << 27)) f |= TMC5160Minimal.FAULT_SHORT_GND;
        if (s & (3 << 12)) f |= TMC5160Minimal.FAULT_SHORT_SUPPLY;
        if (s & (3 << 29)) f |= TMC5160Minimal.FAULT_OPEN_LOAD;
        return f;
    }
}
TMC5160Minimal.FAULT_OVERTEMP_WARNING = 0x01;
TMC5160Minimal.FAULT_OVERTEMP = 0x02;
TMC5160Minimal.FAULT_SHORT_GND = 0x04;
TMC5160Minimal.FAULT_SHORT_SUPPLY = 0x08;
TMC5160Minimal.FAULT_OPEN_LOAD = 0x10;

/**
 * Complete TMC5160 driver: raw register access, the full SixPoint ramp,
 * reference switches, chopper / StealthChop tuning, StallGuard2 / CoolStep,
 * DcStep, the ABN encoder, gate-driver configuration and every status read-back.
 */
class TMC5160Full extends TMC5160Minimal {
    /**
     * @param {object} connection - SPI (Mode 3) or UART connection.
     * @param {object} [options] - As TMC5160Minimal, plus:
     * @param {object|null} [options.diag0Pin=null] - InputPin wired to DIAG0 (SPI only).
     * @param {object|null} [options.diag1Pin=null] - InputPin wired to DIAG1 (SPI only).
     * @throws {RangeError} If a DIAG pin is given on the UART bus.
     */
    constructor(connection, options = {}) {
        super(connection, options);
        if (this._uart && (options.diag0Pin || options.diag1Pin)) throw new RangeError('DIAG pins are not available on the UART bus');
        this._diagPins = [options.diag0Pin ?? null, options.diag1Pin ?? null];
    }

    /** @param {number} reg - Readable register. @returns {Promise<number>} Raw 32-bit value. @throws {RangeError} For write-only/reserved registers. */
    async readRegister(reg) {
        if (!READABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not readable');
        return this._readReg(reg);
    }

    /** @param {number} reg - Writable register. @param {number} value - 32-bit value. @param {boolean} [verify=false] - SPI mirror / UART IFCNT check. @returns {Promise<void>} @throws {RangeError} For read-only/reserved registers. */
    async writeRegister(reg, value, verify = false) {
        if (!WRITABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not writable');
        await this._writeReg(reg, value, verify);
    }

    /** @param {number} rmsA - Hold current in A RMS. @returns {Promise<void>} @throws {RangeError} If above the run current. */
    async setHoldCurrent(rmsA) {
        const gs = this._shadow.get(R.GLOBALSCALER) || 256;
        const full = gs / 256 * V_FS / (this._rSense * Math.SQRT2);
        const ihold = Math.round(rmsA / full * 32) - 1;
        if (!(rmsA > 0) || ihold > this._irun) throw new RangeError('hold current above run current');
        this._ihold = Math.max(0, ihold);
        await this._writeIholdIrun();
    }

    /** @param {number} value - 0 or 32-256 (256 is written as 0). @returns {Promise<void>} @throws {RangeError} For 1-31 or above 256. */
    async setGlobalScaler(value) {
        if (!(value === 0 || (value >= 32 && value <= 256))) throw new RangeError('GLOBALSCALER must be 0 or 32..256');
        await this._writeReg(R.GLOBALSCALER, value === 256 ? 0 : value);
    }

    /** @param {number} irun 0-31 @param {number} ihold 0-31 @param {number} iholdDelay 0-15 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setCurrentScale(irun, ihold, iholdDelay) {
        if (!(irun >= 0 && irun <= 31 && ihold >= 0 && ihold <= 31 && iholdDelay >= 0 && iholdDelay <= 15)) throw new RangeError('IHOLD_IRUN field out of range');
        this._irun = irun; this._ihold = ihold; this._iholdDelay = iholdDelay;
        await this._writeIholdIrun();
    }

    /** @param {number} tpowerdown - 0-255 (values below 2 break StealthChop auto-tuning). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setPowerDownDelay(tpowerdown) {
        if (!(tpowerdown >= 0 && tpowerdown <= 255)) throw new RangeError('tpowerdown 0-255');
        await this._writeReg(R.TPOWERDOWN, tpowerdown);
    }

    /**
     * Program the full SixPoint ramp in physical units (full steps/s, full steps/s^2).
     * @param {number} vstart @param {number} a1 @param {number} v1 (0 disables A1/D1) @param {number} amax @param {number} vmax @param {number} dmax @param {number} d1 (> 0) @param {number} vstop (> 0)
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range, d1 or vstop is 0, or vstop < vstart.
     */
    async configureRamp(vstart, a1, v1, amax, vmax, dmax, d1, vstop) {
        await this.setRampRaw(this._vReg(vstart), this._aReg(a1), this._vReg(v1), this._aReg(amax),
            this._vReg(vmax), this._aReg(dmax), this._aReg(d1), this._vReg(vstop));
        this._speed = vmax;
        this._accel = amax;
    }

    /**
     * Program the full SixPoint ramp in raw register units.
     * @param {number} vstart @param {number} a1 @param {number} v1 @param {number} amax @param {number} vmax @param {number} dmax @param {number} d1 @param {number} vstop
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range, d1 or vstop is 0, or vstop < vstart.
     */
    async setRampRaw(vstart, a1, v1, amax, vmax, dmax, d1, vstop) {
        this._check('VSTART', vstart, (1 << 18) - 1);
        this._check('A1', a1, 0xFFFF);
        this._check('V1', v1, (1 << 20) - 1);
        this._check('AMAX', amax, 0xFFFF);
        this._check('VMAX', vmax, (1 << 23) - 512);
        this._check('DMAX', dmax, 0xFFFF);
        this._check('D1', d1, 0xFFFF);
        this._check('VSTOP', vstop, (1 << 18) - 1);
        if (d1 < 1 || vstop < 1) throw new RangeError('D1 and VSTOP must be at least 1');
        if (vstop < vstart) throw new RangeError('VSTOP must be >= VSTART');
        const vals = { VSTART: vstart, A1: a1, V1: v1, AMAX: amax, VMAX: vmax, DMAX: dmax, D1: d1, VSTOP: vstop };
        for (const [n, v] of Object.entries(vals)) await this._writeReg(R[n], v);
        this._speedReg = vmax;
    }

    /** @param {number} seconds - TZEROWAIT (0 to about 2.8 s at 12 MHz). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setZeroWait(seconds) {
        await this._writeReg(R.TZEROWAIT, this._check('TZEROWAIT', Math.round(seconds * this._fclk / 512), 0xFFFF));
    }

    /** @param {number} mode - RAMPMODE 0 position, 1 velocity +, 2 velocity -, 3 hold. @returns {Promise<void>} @throws {RangeError} If not 0-3. */
    async setRampMode(mode) {
        if (!(mode >= 0 && mode <= 3)) throw new RangeError('mode 0-3');
        await this._writeReg(R.RAMPMODE, mode);
        this._rampmode = mode;
    }

    /** Hold mode: keep the present velocity until a stop event. @returns {Promise<void>} */
    async hold() { await this.setRampMode(3); }

    /** @param {number} xtarget - Signed 32-bit XTARGET in microsteps. @returns {Promise<void>} */
    async setTargetRaw(xtarget) { await this._writeReg(R.XTARGET, xtarget >>> 0); }

    /** @returns {Promise<object>} Decoded RAMP_STAT flags. */
    async readRampStatus() {
        const s = await this._readReg(R.RAMP_STAT);
        const names = ['status_stop_l', 'status_stop_r', 'status_latch_l', 'status_latch_r', 'event_stop_l',
            'event_stop_r', 'event_stop_sg', 'event_pos_reached', 'velocity_reached', 'position_reached',
            'vzero', 't_zerowait_active', 'second_move', 'status_sg'];
        const out = {};
        names.forEach((n, i) => { out[n] = !!(s & (1 << i)); });
        return out;
    }

    /** Write 1 to the clear-on-write bits of RAMP_STAT. @returns {Promise<void>} */
    async clearRampEvents() { await this._writeReg(R.RAMP_STAT, (1 << 2) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 12)); }

    /** @param {number} position - X_COMPARE in full steps (DIAG1 high while XACTUAL equals it). @returns {Promise<void>} */
    async setPositionCompare(position) { await this._writeReg(R.X_COMPARE, Math.round(position * this._usc) >>> 0); }

    /**
     * Write SW_MODE bits 0-4 and 11 (latch and sg_stop bits are kept).
     * @param {object} [o] - stopLeft, stopRight, leftActiveLow, rightActiveLow, swap, softStop (booleans).
     * @returns {Promise<void>}
     * @throws {RangeError} If soft stop is requested while sg_stop is enabled.
     */
    async configureSwitches(o = {}) {
        const sw = this._shadow.get(R.SW_MODE) ?? 0;
        if (o.softStop && (sw & SW_SG_STOP)) throw new RangeError('soft stop cannot be combined with sg_stop');
        const v = (o.stopLeft ? 1 : 0) | (o.stopRight ? 2 : 0) | (o.leftActiveLow ? 4 : 0) |
            (o.rightActiveLow ? 8 : 0) | (o.swap ? 16 : 0) | (o.softStop ? SW_SOFTSTOP : 0);
        await this._modify(R.SW_MODE, 0x1F | SW_SOFTSTOP, v);
    }

    /** @param {object} [o] - leftActive, leftInactive, rightActive, rightInactive, latchEncoder (booleans). @returns {Promise<void>} */
    async configureLatch(o = {}) {
        const v = (o.leftActive ? 1 << 5 : 0) | (o.leftInactive ? 1 << 6 : 0) | (o.rightActive ? 1 << 7 : 0) |
            (o.rightInactive ? 1 << 8 : 0) | (o.latchEncoder ? 1 << 9 : 0);
        await this._modify(R.SW_MODE, 0x1F << 5, v);
    }

    /** @returns {Promise<number>} XLATCH in full steps. */
    async readLatchedPosition() { return s32(await this._readReg(R.XLATCH)) / this._usc; }

    /** @returns {Promise<boolean[]>} [status_stop_l, status_stop_r]. */
    async readSwitches() {
        const s = await this._readReg(R.RAMP_STAT);
        return [!!(s & 1), !!(s & 2)];
    }

    /** @param {number} n - 1, 2, 4, ... 256 (the ramp registers are not rewritten). @returns {Promise<void>} @throws {RangeError} For any other value. */
    async setMicrosteps(n) {
        if (![1, 2, 4, 8, 16, 32, 64, 128, 256].includes(n)) throw new RangeError('microsteps must be a power of two, 1-256');
        await this._modify(R.CHOPCONF, 0xF << 24, (8 - Math.log2(n)) << 24);
        this._usc = n;
    }

    /** @param {boolean} enabled - CHOPCONF.intpol. @returns {Promise<void>} */
    async setInterpolation(enabled) { await this._modify(R.CHOPCONF, 1 << 28, enabled ? 1 << 28 : 0); }

    /** @param {boolean} spreadcycle - True for SpreadCycle only (switch at standstill). @returns {Promise<void>} */
    async setChopperMode(spreadcycle) { await this._modify(R.GCONF, GCONF_EN_PWM_MODE, spreadcycle ? 0 : GCONF_EN_PWM_MODE); }

    async _threshold(name, v) {
        if (!(v >= 0 && v <= 0xFFFFF)) throw new RangeError(name + ' 0-1048575');
        await this._writeReg(R[name], v);
    }

    /** @param {number} tpwmthrs - TPWMTHRS (20 bit), 0 disables. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setStealthchopThreshold(tpwmthrs) { await this._threshold('TPWMTHRS', tpwmthrs); }

    /** @param {number} thigh - THIGH (20 bit). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setHighVelocityThreshold(thigh) { await this._threshold('THIGH', thigh); }

    /** SpreadCycle (chm = 0) fields. @param {number} toff 0-15 @param {number} tbl 0-3 @param {number} hstrt 0-7 @param {number} hend raw 0-15 @param {number} [tpfd=0] 0-15 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureChopper(toff, tbl, hstrt, hend, tpfd = 0) {
        if (!(toff >= 0 && toff <= 15 && tbl >= 0 && tbl <= 3 && hstrt >= 0 && hstrt <= 7 && hend >= 0 && hend <= 15 && tpfd >= 0 && tpfd <= 15)) throw new RangeError('chopper field out of range');
        if (toff) this._toff = toff;
        const mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 11);
        await this._modify(R.CHOPCONF, mask, toff | (tbl << 15) | (hstrt << 4) | (hend << 7) | (tpfd << 20));
    }

    /** Constant-off-time (chm = 1) fields. @param {number} toff @param {number} tbl @param {number} tfd 0-15 @param {number} offset -3..12 @param {boolean} disfdcc @param {number} [tpfd=0] @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureConstantOffTime(toff, tbl, tfd, offset, disfdcc, tpfd = 0) {
        if (!(toff >= 0 && toff <= 15 && tbl >= 0 && tbl <= 3 && tfd >= 0 && tfd <= 15 && offset >= -3 && offset <= 12 && tpfd >= 0 && tpfd <= 15)) throw new RangeError('chopper field out of range');
        if (toff) this._toff = toff;
        const mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 20) | (1 << 14) | (1 << 12) | (1 << 11);
        const v = toff | (tbl << 15) | ((tfd & 7) << 4) | ((offset + 3) << 7) | (tpfd << 20) | (1 << 14) |
            (disfdcc ? 1 << 12 : 0) | (((tfd >> 3) & 1) << 11);
        await this._modify(R.CHOPCONF, mask, v);
    }

    /** @param {boolean} vhighchm @param {boolean} vhighfs @returns {Promise<void>} */
    async configureHighVelocity(vhighchm, vhighfs) {
        await this._modify(R.CHOPCONF, (1 << 19) | (1 << 18), (vhighchm ? 1 << 19 : 0) | (vhighfs ? 1 << 18 : 0));
    }

    /** PWMCONF fields (freewheel kept). @param {boolean} autoscale @param {boolean} autograd @param {number} pwmOfs 0-255 @param {number} pwmGrad 0-255 @param {number} pwmFreq 0-3 @param {number} pwmReg 1-15 @param {number} pwmLim 0-15 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureStealthchop(autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) {
        if (!(pwmOfs >= 0 && pwmOfs <= 255 && pwmGrad >= 0 && pwmGrad <= 255 && pwmFreq >= 0 && pwmFreq <= 3 && pwmReg >= 1 && pwmReg <= 15 && pwmLim >= 0 && pwmLim <= 15)) throw new RangeError('stealthchop field out of range');
        if (!this._shadow.has(R.PWMCONF)) this._shadow.set(R.PWMCONF, 0xC40C001E);
        const mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF;
        const v = (pwmLim << 28) | (pwmReg << 24) | (autograd ? 1 << 19 : 0) | (autoscale ? 1 << 18 : 0) |
            (pwmFreq << 16) | (pwmGrad << 8) | pwmOfs;
        await this._modify(R.PWMCONF, mask, v);
    }

    /** @param {number} mode - PWMCONF.freewheel 0-3. @returns {Promise<void>} @throws {RangeError} If not 0-3. */
    async setFreewheel(mode) {
        if (!(mode >= 0 && mode <= 3)) throw new RangeError('freewheel mode 0-3');
        if (!this._shadow.has(R.PWMCONF)) this._shadow.set(R.PWMCONF, 0xC40C001E);
        await this._modify(R.PWMCONF, 3 << 20, mode << 20);
    }

    /** @param {boolean} shortGnd - Keep short-to-GND protection. @param {boolean} shortSupply - Keep short-to-supply protection. @returns {Promise<void>} */
    async setShortProtection(shortGnd, shortSupply) {
        await this._modify(R.CHOPCONF, (1 << 30) | (1 << 31), (shortGnd ? 0 : 1 << 30) | (shortSupply ? 0 : 1 << 31));
    }

    /** @param {boolean} enabled - CHOPCONF.dedge (external STEP/DIR only). @returns {Promise<void>} */
    async setDoubleEdge(enabled) { await this._modify(R.CHOPCONF, 1 << 29, enabled ? 1 << 29 : 0); }

    /** @param {boolean} inverted - GCONF.shaft. @returns {Promise<void>} */
    async setDirectionInverted(inverted) { await this._modify(R.GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0); }

    /** @param {boolean} enabled - GCONF.faststandstill. @returns {Promise<void>} */
    async setFastStandstill(enabled) { await this._modify(R.GCONF, 1 << 1, enabled ? 1 << 1 : 0); }

    /** @param {boolean} enabled - GCONF.recalibrate. @returns {Promise<void>} */
    async setRecalibrate(enabled) { await this._modify(R.GCONF, 1, enabled ? 1 : 0); }

    /** @param {boolean} enabled - GCONF.small_hysteresis. @returns {Promise<void>} */
    async setSmallHysteresis(enabled) { await this._modify(R.GCONF, GCONF_SMALL_HYSTERESIS, enabled ? GCONF_SMALL_HYSTERESIS : 0); }

    /** @param {boolean} enabled - GCONF.stop_enable. @returns {Promise<void>} */
    async setEmergencyStop(enabled) { await this._modify(R.GCONF, GCONF_STOP_ENABLE, enabled ? GCONF_STOP_ENABLE : 0); }

    /** GCONF bits 7, 8, 12, 13. @param {object} [o] - diag0StepOutput, diag1DirOutput, diag0Pushpull, diag1Pushpull. @returns {Promise<void>} */
    async configureDiag(o = {}) {
        const v = (o.diag0StepOutput ? 1 << 7 : 0) | (o.diag1DirOutput ? 1 << 8 : 0) |
            (o.diag0Pushpull ? 1 << 12 : 0) | (o.diag1Pushpull ? 1 << 13 : 0);
        await this._modify(R.GCONF, (1 << 7) | (1 << 8) | (1 << 12) | (1 << 13), v);
    }

    /** @param {number} sgt - COOLCONF.sgt -64..63 (lower is more sensitive). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setStallThreshold(sgt) {
        if (!(sgt >= -64 && sgt <= 63)) throw new RangeError('sgt -64..63');
        await this._modify(R.COOLCONF, 0x7F << 16, (sgt & 0x7F) << 16);
    }

    /** @param {boolean} enabled - COOLCONF.sfilt. @returns {Promise<void>} */
    async setStallguardFilter(enabled) { await this._modify(R.COOLCONF, 1 << 24, enabled ? 1 << 24 : 0); }

    /** @param {number} tcoolthrs - TCOOLTHRS (20 bit), 0 disables. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setCoolstepThreshold(tcoolthrs) { await this._threshold('TCOOLTHRS', tcoolthrs); }

    /** CoolStep fields (sgt / sfilt kept). @param {number} semin 0-15 @param {number} semax 0-15 @param {number} seup 0-3 @param {number} sedn 0-3 @param {number} seimin 0|1 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureCoolstep(semin, semax, seup, sedn, seimin) {
        if (!(semin >= 0 && semin <= 15 && semax >= 0 && semax <= 15 && seup >= 0 && seup <= 3 && sedn >= 0 && sedn <= 3 && (seimin === 0 || seimin === 1))) throw new RangeError('COOLCONF field out of range');
        await this._modify(R.COOLCONF, 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15),
            semin | (seup << 5) | (semax << 8) | (sedn << 13) | (seimin << 15));
    }

    /** @param {boolean} enabled - SW_MODE.sg_stop (needs TCOOLTHRS; not during spin-up). @returns {Promise<void>} @throws {RangeError} With soft stop enabled. */
    async enableStallStop(enabled) {
        if (enabled && ((this._shadow.get(R.SW_MODE) ?? 0) & SW_SOFTSTOP)) throw new RangeError('sg_stop cannot be combined with soft stop');
        await this._modify(R.SW_MODE, SW_SG_STOP, enabled ? SW_SG_STOP : 0);
    }

    /** @returns {Promise<number>} SG_RESULT 0-1023. */
    async readStallguard() { return (await this._readReg(R.DRV_STATUS)) & 0x3FF; }

    /** @returns {Promise<boolean>} DRV_STATUS.StallGuard. */
    async isStalled() { return (((await this._readReg(R.DRV_STATUS)) >>> 24) & 1) === 1; }

    /** @param {number} n - 0 for DIAG0, 1 for DIAG1. @returns {boolean} Pin level. @throws {RangeError} If n is not 0/1. @throws {Error} If not wired (or UART). */
    readDiag(n) {
        if (n !== 0 && n !== 1) throw new RangeError('n must be 0 or 1');
        const pin = this._diagPins[n];
        if (!pin) throw new Error('readDiag() requires diag' + n + 'Pin on the SPI bus');
        return !!pin.isHigh();
    }

    /** @param {number} vdcmin 0..2^23-1 @param {number} dcTime 0-1023 @param {number} dcSg 0-255 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setDcstep(vdcmin, dcTime, dcSg) {
        if (!(vdcmin >= 0 && vdcmin <= 0x7FFFFF && dcTime >= 0 && dcTime <= 1023 && dcSg >= 0 && dcSg <= 255)) throw new RangeError('DcStep field out of range');
        await this._writeReg(R.VDCMIN, vdcmin);
        await this._writeReg(R.DCCTRL, (dcTime | (dcSg << 16)) >>> 0);
    }

    /** @param {boolean} enabled - GCONF.direct_mode (bypasses the ramp generator). @returns {Promise<void>} */
    async setDirectMode(enabled) {
        await this._modify(R.GCONF, GCONF_DIRECT_MODE, enabled ? GCONF_DIRECT_MODE : 0);
        if (!enabled) await this._writeReg(R.XTARGET, this._shadow.get(R.XTARGET) ?? 0);
    }

    /** XDIRECT (shares the XTARGET address). @param {number} coilA -255..255 @param {number} coilB -255..255 @returns {Promise<void>} @throws {Error} If direct mode is off. @throws {RangeError} If out of range. */
    async setCoilCurrents(coilA, coilB) {
        if (!((this._shadow.get(R.GCONF) ?? 0) & GCONF_DIRECT_MODE)) throw new Error('setCoilCurrents() requires direct mode');
        if (!(coilA >= -255 && coilA <= 255 && coilB >= -255 && coilB <= 255)) throw new RangeError('coil currents -255..255');
        await this._writeReg(R.XTARGET, ((coilA & 0x1FF) | ((coilB & 0x1FF) << 16)) >>> 0);
    }

    /** Program the microstep table (call at MSCNT = 0). @param {number[]} mslut - 8 words @param {number} mslutsel @param {number} mslutstart @returns {Promise<void>} @throws {RangeError} If mslut does not hold 8 words. */
    async writeMicrostepTable(mslut, mslutsel, mslutstart) {
        if (mslut.length !== 8) throw new RangeError('mslut needs 8 words');
        for (let i = 0; i < 8; i++) await this._writeReg(R.MSLUT0 + i, mslut[i]);
        await this._writeReg(R.MSLUTSEL, mslutsel);
        await this._writeReg(R.MSLUTSTART, mslutstart);
    }

    /** DRV_CONF -- board-specific, wrong values can destroy the MOSFETs. @param {number} bbmtime 0-24 @param {number} bbmclks 0-15 @param {number} otselect 0-3 @param {number} drvstrength 0-3 @param {number} filtIsense 0-3 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureGateDriver(bbmtime, bbmclks, otselect, drvstrength, filtIsense) {
        if (!(bbmtime >= 0 && bbmtime <= 24 && bbmclks >= 0 && bbmclks <= 15 && otselect >= 0 && otselect <= 3 && drvstrength >= 0 && drvstrength <= 3 && filtIsense >= 0 && filtIsense <= 3)) throw new RangeError('DRV_CONF field out of range');
        await this._writeReg(R.DRV_CONF, bbmtime | (bbmclks << 8) | (otselect << 16) | (drvstrength << 18) | (filtIsense << 20));
    }

    /** SHORT_CONF -- board-specific. @param {number} s2vsLevel 4-15 @param {number} s2gLevel 2-15 @param {number} shortfilter 0-3 @param {number} shortdelay 0|1 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async configureShortDetection(s2vsLevel, s2gLevel, shortfilter, shortdelay) {
        if (!(s2vsLevel >= 4 && s2vsLevel <= 15 && s2gLevel >= 2 && s2gLevel <= 15 && shortfilter >= 0 && shortfilter <= 3 && (shortdelay === 0 || shortdelay === 1))) throw new RangeError('SHORT_CONF field out of range');
        await this._writeReg(R.SHORT_CONF, s2vsLevel | (s2gLevel << 8) | (shortfilter << 16) | (shortdelay << 18));
    }

    /** ENCMODE (all booleans, in register bit order). @returns {Promise<void>} */
    async configureEncoder(polA, polB, polN, ignoreAb, clrCont, clrOnce, posEdge, negEdge, clrEncX, latchXAct, decimal) {
        const flags = [polA, polB, polN, ignoreAb, clrCont, clrOnce, posEdge, negEdge, clrEncX, latchXAct, decimal];
        let v = 0;
        flags.forEach((f, i) => { if (f) v |= 1 << i; });
        await this._writeReg(R.ENCMODE, v);
    }

    /** @param {number} value - ENC_CONST: signed 16.16 (or integer + 4-digit decimal fraction when ENCMODE.enc_sel_decimal is set). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setEncoderConstant(value) {
        if (Math.abs(value) > 32767.9999) throw new RangeError('encoder constant out of range');
        let raw;
        if ((this._shadow.get(R.ENCMODE) ?? 0) & (1 << 10)) {
            const whole = Math.floor(Math.abs(value));
            raw = ((whole << 16) | Math.round((Math.abs(value) - whole) * 10000)) >>> 0;
            if (value < 0) raw = (-raw) >>> 0;
        } else {
            raw = Math.round(value * 65536) >>> 0;
        }
        await this._writeReg(R.ENC_CONST, raw);
    }

    /** @param {number} counts - X_ENC. @returns {Promise<void>} */
    async setEncoderPosition(counts) { await this._writeReg(R.X_ENC, counts >>> 0); }

    /** @returns {Promise<number>} X_ENC signed. */
    async readEncoderPosition() { return s32(await this._readReg(R.X_ENC)); }

    /** @returns {Promise<number>} ENC_LATCH signed. */
    async readEncoderLatch() { return s32(await this._readReg(R.ENC_LATCH)); }

    /** @param {number} steps - ENC_DEVIATION 0-2^20-1 (0 = off). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setEncoderDeviation(steps) {
        if (!(steps >= 0 && steps <= 0xFFFFF)) throw new RangeError('steps 0-1048575');
        await this._writeReg(R.ENC_DEVIATION, steps);
    }

    /** @returns {Promise<object>} {n_event, deviation_warn}. */
    async readEncoderStatus() {
        const s = await this._readReg(R.ENC_STATUS);
        return { n_event: !!(s & 1), deviation_warn: !!(s & 2) };
    }

    /** Write 1 to the ENC_STATUS event bits. @returns {Promise<void>} */
    async clearEncoderStatus() { await this._writeReg(R.ENC_STATUS, 0x03); }

    _requireUart() { if (!this._uart) throw new Error('only available on the UART bus'); }

    /** @param {number} slaveaddr - SLAVEADDR 0-253 (UART only). @returns {Promise<void>} @throws {Error} On SPI. @throws {RangeError} If out of range. */
    async setSlaveAddress(slaveaddr) {
        this._requireUart();
        if (!(slaveaddr >= 0 && slaveaddr <= 253)) throw new RangeError('slaveaddr 0-253');
        await this._writeReg(R.SLAVECONF, slaveaddr | (this._senddelay << 8));
        this._address = slaveaddr;
    }

    /** @param {number} sd - SENDDELAY 0-15 (UART only; at least 2 on multi-node buses). @returns {Promise<void>} @throws {Error} On SPI. @throws {RangeError} If out of range. */
    async setSendDelay(sd) {
        this._requireUart();
        if (!(sd >= 0 && sd <= 15)) throw new RangeError('send delay 0-15');
        this._senddelay = sd;
        await this._writeReg(R.SLAVECONF, ((this._shadow.get(R.SLAVECONF) ?? 0) & 0xFF) | (sd << 8));
    }

    /** @param {boolean} level - OUTPUT bit 0, the NAO polarity (UART only). @returns {Promise<void>} @throws {Error} On SPI. */
    async setNaoPolarity(level) {
        this._requireUart();
        await this._writeReg(R.OUTPUT, level ? 1 : 0);
    }

    /** @returns {Promise<number>} IFCNT (UART only). @throws {Error} On SPI. */
    async readIfcnt() {
        this._requireUart();
        return (await this._readReg(R.IFCNT)) & 0xFF;
    }

    /** @returns {Promise<number>} MSCNT 0-1023. */
    async microstepPosition() { return (await this._readReg(R.MSCNT)) & 0x3FF; }

    /** @returns {Promise<number>} DRV_STATUS.CS_ACTUAL 0-31. */
    async readCurrentScale() { return ((await this._readReg(R.DRV_STATUS)) >>> 16) & 0x1F; }

    /** @returns {Promise<number[]>} Signed [CUR_A, CUR_B] from MSCURACT. */
    async readCoilCurrents() {
        const raw = await this._readReg(R.MSCURACT);
        return [signed(raw >>> 16, 9), signed(raw, 9)];
    }

    /** @returns {Promise<number>} TSTEP (20 bit). */
    async readTstep() { return (await this._readReg(R.TSTEP)) & 0xFFFFF; }

    /** @returns {Promise<number[]>} [PWM_SCALE_SUM, PWM_SCALE_AUTO signed]. */
    async readPwmScale() {
        const raw = await this._readReg(R.PWM_SCALE);
        return [raw & 0xFF, signed(raw >>> 16, 9)];
    }

    /** @returns {Promise<number[]>} [PWM_OFS_AUTO, PWM_GRAD_AUTO]. */
    async readPwmAuto() {
        const raw = await this._readReg(R.PWM_AUTO);
        return [raw & 0xFF, (raw >>> 16) & 0xFF];
    }

    /** @returns {Promise<number[]>} Signed [phase A, phase B] from OFFSET_READ. */
    async readOffset() {
        const raw = await this._readReg(R.OFFSET_READ);
        return [signed(raw >>> 8, 8), signed(raw, 8)];
    }

    /** @returns {Promise<object>} Decoded DRV_STATUS. */
    async readStatus() {
        const s = await this._readReg(R.DRV_STATUS);
        return {
            stst: !!(s & 0x80000000), stealth: !!(s & (1 << 14)), fsactive: !!(s & (1 << 15)),
            cs_actual: (s >>> 16) & 0x1F, sg_result: s & 0x3FF, stallguard: !!(s & (1 << 24)),
            otpw: !!(s & (1 << 26)), ot: !!(s & (1 << 25)), short_gnd: !!(s & (3 << 27)),
            short_supply: !!(s & (3 << 12)), open_load: !!(s & (3 << 29)),
        };
    }

    /** @returns {Promise<object>} {reset, drv_err, uv_cp} (GSTAT; not cleared by the read). */
    async readGlobalStatus() {
        const g = await this._readReg(R.GSTAT);
        return { reset: !!(g & 1), drv_err: !!(g & 2), uv_cp: !!(g & 4) };
    }

    /** Write 0x07 to GSTAT. @returns {Promise<void>} */
    async clearGlobalStatus() { await this._writeReg(R.GSTAT, 0x07); }

    /** @returns {Promise<object>} {raw, refl, refr, encb, enca, drv_enn, encn, sd_mode, swcomp_in, version}. */
    async readIoin() {
        const raw = await this._readReg(R.IOIN);
        return {
            raw, refl: !!(raw & 1), refr: !!(raw & 2), encb: !!(raw & 4), enca: !!(raw & 8),
            drv_enn: !!(raw & 16), encn: !!(raw & 32), sd_mode: !!(raw & 64), swcomp_in: !!(raw & 128),
            version: (raw >>> 24) & 0xFF,
        };
    }
}

module.exports = { TMC5160Minimal, TMC5160Full, crc8 };
