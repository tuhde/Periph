'use strict';

const REG_GCONF = 0x00;
const REG_GSTAT = 0x01;
const REG_IOIN = 0x04;
const REG_IHOLD_IRUN = 0x10;
const REG_TPOWERDOWN = 0x11;
const REG_TSTEP = 0x12;
const REG_TPWMTHRS = 0x13;
const REG_TCOOLTHRS = 0x14;
const REG_THIGH = 0x15;
const REG_XDIRECT = 0x2D;
const REG_VDCMIN = 0x33;
const REG_MSLUT0 = 0x60;
const REG_MSLUTSEL = 0x68;
const REG_MSLUTSTART = 0x69;
const REG_MSCNT = 0x6A;
const REG_MSCURACT = 0x6B;
const REG_CHOPCONF = 0x6C;
const REG_COOLCONF = 0x6D;
const REG_DCCTRL = 0x6E;
const REG_DRV_STATUS = 0x6F;
const REG_PWMCONF = 0x70;
const REG_PWM_SCALE = 0x71;
const REG_LOST_STEPS = 0x73;

const READABLE = new Set([0x00, 0x01, 0x04, 0x12, 0x2D, 0x6A, 0x6B, 0x6C, 0x6F, 0x71, 0x73]);
const WRITABLE = new Set([0x00, 0x10, 0x11, 0x13, 0x14, 0x15, 0x2D, 0x33, 0x60, 0x61, 0x62, 0x63,
    0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6C, 0x6D, 0x6E, 0x70]);

const GCONF_I_SCALE_ANALOG = 1 << 0;
const GCONF_INTERNAL_RSENSE = 1 << 1;
const GCONF_EN_PWM_MODE = 1 << 2;
const GCONF_SHAFT = 1 << 4;
const GCONF_SMALL_HYSTERESIS = 1 << 14;
const GCONF_STOP_ENABLE = 1 << 15;
const GCONF_DIRECT_MODE = 1 << 16;

const VERSION = 0x11;
const CHOPCONF_DEFAULT = 0x140100C3;
const PWMCONF_DEFAULT = 0x000401C8;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const toSigned = (value, bits) => {
    const v = value & ((1 << bits) - 1);
    return (v & (1 << (bits - 1))) ? v - (1 << bits) : v;
};

/**
 * Trinamic TMC2130 stepper driver over SPI -- minimal interface.
 *
 * Brings the driver up in SPI mode (internal reference, StealthChop below
 * TPWMTHRS = 500 and SpreadCycle above it, 16 microsteps with 256-step
 * interpolation, 0.5 A RMS run current) and moves the motor with STEP/DIR
 * pulses -- the chip has no internal step generator. The 40-bit datagram
 * framing and the pipelined two-datagram read are handled here; the
 * connection only needs `write` / `writeRead` (SPI Mode 3, MSB first).
 *
 * The constructor performs no I/O; call `await init()` before use.
 */
class TMC2130Minimal {
    /**
     * @param {object} connection - SPI connection configured for Mode 3.
     * @param {object} stepPin - OutputPin wired to STEP.
     * @param {object} dirPin - OutputPin wired to DIR.
     * @param {object} [options]
     * @param {number} [options.rSenseOhm=0.11] - External sense resistor in ohm.
     * @param {object|null} [options.enPin=null] - OutputPin wired to ENN (active low).
     */
    constructor(connection, stepPin, dirPin, options = {}) {
        this._conn = connection;
        this._stepPin = stepPin;
        this._dirPin = dirPin;
        this._rSense = options.rSenseOhm ?? 0.11;
        this._enPin = options.enPin ?? null;
        this._fclk = options.fclkHz ?? 12000000;
        this._shadow = new Map();
        this._microsteps = 16;
        this._vsense = false;
        this._autoVsense = true;
        this._internalRsense = false;
        this._toff = 3;
        this._runCs = 0;
        this._holdCs = 0;
        this._iholdDelay = 6;
    }

    async _writeReg(reg, value, verify = false) {
        value = value >>> 0;
        await this._conn.write(Buffer.from([(reg | 0x80) & 0xFF,
            (value >>> 24) & 0xFF, (value >>> 16) & 0xFF, (value >>> 8) & 0xFF, value & 0xFF]));
        this._shadow.set(reg, value);
        if (verify) {
            const mirror = await this._secondDatagram();
            if (mirror !== value) throw new Error('TMC2130: write mirror mismatch (reg 0x' + reg.toString(16) + ')');
        }
    }

    // A harmless GCONF read request whose reply is the previous datagram's
    // answer (pipelined protocol). Never GSTAT, which clears on read.
    async _secondDatagram() {
        const reply = Buffer.from(await this._conn.writeRead(Buffer.from([REG_GCONF]), 4));
        if (reply.length !== 4) throw new Error('TMC2130: short read');
        return reply.readUInt32BE(0);
    }

    async _readReg(reg) {
        await this._conn.writeRead(Buffer.from([reg & 0x7F]), 4);
        return this._secondDatagram();
    }

    async _modify(reg, mask, value) {
        const current = this._shadow.get(reg) ?? 0;
        await this._writeReg(reg, ((current & ~mask) | (value & mask)) >>> 0);
    }

    _csFor(rmsA, vsense) {
        const vfs = vsense ? 0.180 : 0.325;
        const cs = Math.round(rmsA * Math.SQRT2 * (this._rSense + 0.020) * 32 / vfs) - 1;
        return Math.max(0, Math.min(31, cs));
    }

    // Returns [cs, vsense]; picks vsense automatically unless fixed.
    _currentToCs(rmsA, vsense) {
        if (this._internalRsense) throw new RangeError('set_current is unsupported with internal Rsense');
        if (vsense !== undefined) return [this._csFor(rmsA, vsense), vsense];
        if (!this._autoVsense) return [this._csFor(rmsA, this._vsense), this._vsense];
        const cs = this._csFor(rmsA, false);
        if (cs < 16) {
            const high = this._csFor(rmsA, true);
            if (high <= 31) return [high, true];
        }
        return [cs, false];
    }

    async _writeIholdIrun(verify = false) {
        await this._writeReg(REG_IHOLD_IRUN,
            (this._holdCs & 0x1F) | ((this._runCs & 0x1F) << 8) | ((this._iholdDelay & 0xF) << 16), verify);
    }

    /**
     * Probe the chip, apply the default configuration and verify the writes.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not answer, has an unexpected identity
     *   or a write is not mirrored back.
     */
    async init() {
        if (this._enPin) await this._enPin.set(true);
        await sleep(10);
        const ioin = await this._readReg(REG_IOIN);
        if (((ioin >>> 24) & 0xFF) !== VERSION || !(ioin & (1 << 6))) {
            throw new Error('TMC2130 not found (IOIN=0x' + ioin.toString(16) + ')');
        }
        await this._readReg(REG_GSTAT);
        const [cs, vsense] = this._currentToCs(0.5);
        this._runCs = cs;
        this._holdCs = Math.floor(cs / 3);
        this._vsense = vsense;
        await this._writeReg(REG_CHOPCONF, (CHOPCONF_DEFAULT | (vsense ? (1 << 17) : 0)) >>> 0, true);
        await this._writeIholdIrun(true);
        await this._writeReg(REG_TPOWERDOWN, 10, true);
        await this._writeReg(REG_GCONF, GCONF_EN_PWM_MODE, true);
        await this._writeReg(REG_TPWMTHRS, 500, true);
        await this._writeReg(REG_PWMCONF, PWMCONF_DEFAULT, true);
        if ((await this._readReg(REG_GCONF)) !== this._shadow.get(REG_GCONF) ||
            (await this._readReg(REG_CHOPCONF)) !== this._shadow.get(REG_CHOPCONF)) {
            throw new Error('TMC2130: GCONF/CHOPCONF readback mismatch');
        }
    }

    /** Switch the power stage on and wait 100 ms for StealthChop to settle. @returns {Promise<void>} */
    async enable() {
        if (this._enPin) await this._enPin.set(false);
        else await this._writeReg(REG_CHOPCONF, ((this._shadow.get(REG_CHOPCONF) & ~0xF) | this._toff) >>> 0);
        await sleep(100);
    }

    /** Switch the power stage off; the motor freewheels. @returns {Promise<void>} */
    async disable() {
        if (this._enPin) { await this._enPin.set(true); return; }
        const chop = this._shadow.get(REG_CHOPCONF);
        if (chop & 0xF) this._toff = chop & 0xF;
        await this._writeReg(REG_CHOPCONF, (chop & ~0xF) >>> 0);
    }

    /**
     * Set the run current; hold current becomes one third of it. Selects the
     * sense range (CHOPCONF.vsense) automatically unless setVsense() was called.
     * @param {number} rmsA - Run current in A RMS (at most 1.4).
     * @returns {Promise<void>}
     * @throws {RangeError} If rmsA is above 1.4 or the internal sense resistors are in use.
     */
    async setCurrent(rmsA) {
        if (rmsA > 1.4) throw new RangeError('rmsA must be at most 1.4 A');
        const [cs, vsense] = this._currentToCs(rmsA);
        this._runCs = cs;
        this._holdCs = Math.floor(cs / 3);
        if (vsense !== this._vsense) {
            this._vsense = vsense;
            await this._modify(REG_CHOPCONF, 1 << 17, vsense ? (1 << 17) : 0);
        }
        await this._writeIholdIrun();
    }

    /**
     * Move a number of full steps (software-timed STEP pulses).
     * @param {number} fullSteps - Full steps to move; the sign sets the direction.
     * @param {number} fullStepsPerS - Speed in full steps per second (> 0).
     * @returns {Promise<void>}
     * @throws {RangeError} If fullStepsPerS is not positive.
     */
    async move(fullSteps, fullStepsPerS) {
        if (!(fullStepsPerS > 0)) throw new RangeError('fullStepsPerS must be > 0');
        const pulses = Math.abs(Math.trunc(fullSteps)) * this._microsteps;
        const halfMs = 1000 / (2 * fullStepsPerS * this._microsteps);
        await this._dirPin.set(fullSteps >= 0);
        for (let i = 0; i < pulses; i++) {
            await this._stepPin.set(true);
            await sleep(halfMs);
            await this._stepPin.set(false);
            await sleep(halfMs);
        }
    }

    /** @returns {Promise<boolean>} True when DRV_STATUS.stst is set. */
    async isStandstill() { return ((await this._readReg(REG_DRV_STATUS)) >>> 31) === 1; }

    /** @returns {Promise<number>} Bitmask of FAULT_* constants, 0 when none. */
    async readFault() {
        const s = await this._readReg(REG_DRV_STATUS);
        let f = 0;
        if (s & (1 << 26)) f |= TMC2130Minimal.FAULT_OVERTEMP_WARNING;
        if (s & (1 << 25)) f |= TMC2130Minimal.FAULT_OVERTEMP;
        if (s & (3 << 27)) f |= TMC2130Minimal.FAULT_SHORT_GND;
        if (s & (3 << 29)) f |= TMC2130Minimal.FAULT_OPEN_LOAD;
        return f;
    }
}
TMC2130Minimal.FAULT_OVERTEMP_WARNING = 0x01;
TMC2130Minimal.FAULT_OVERTEMP = 0x02;
TMC2130Minimal.FAULT_SHORT_GND = 0x04;
TMC2130Minimal.FAULT_OPEN_LOAD = 0x10;

/**
 * Complete TMC2130 driver: raw register access, independent hold current,
 * microstep / chopper / StealthChop tuning, StallGuard2 and CoolStep, DIAG
 * routing, DcStep, direct coil-current mode, the microstep table and every
 * status read-back.
 */
class TMC2130Full extends TMC2130Minimal {
    /**
     * @param {object} connection - SPI connection configured for Mode 3.
     * @param {object} stepPin - OutputPin wired to STEP.
     * @param {object} dirPin - OutputPin wired to DIR.
     * @param {object} [options]
     * @param {number} [options.rSenseOhm=0.11] - External sense resistor in ohm.
     * @param {object|null} [options.enPin=null] - OutputPin wired to ENN.
     * @param {object|null} [options.diag0Pin=null] - InputPin wired to DIAG0.
     * @param {object|null} [options.diag1Pin=null] - InputPin wired to DIAG1.
     * @param {number} [options.fclkHz=12000000] - Chip clock in Hz.
     */
    constructor(connection, stepPin, dirPin, options = {}) {
        super(connection, stepPin, dirPin, options);
        this._diagPins = [options.diag0Pin ?? null, options.diag1Pin ?? null];
    }

    /** @param {number} reg - Readable register. @returns {Promise<number>} Raw 32-bit value. @throws {RangeError} For write-only/reserved registers. */
    async readRegister(reg) {
        if (!READABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not readable');
        return this._readReg(reg);
    }

    /** @param {number} reg - Writable register. @param {number} value - 32-bit value. @param {boolean} [verify=false] - Also check the mirror. @returns {Promise<void>} @throws {RangeError} For read-only/reserved registers. */
    async writeRegister(reg, value, verify = false) {
        if (!WRITABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not writable');
        await this._writeReg(reg, value, verify);
    }

    /** @param {number} rmsA - Hold current in A RMS. @returns {Promise<void>} */
    async setHoldCurrent(rmsA) {
        this._holdCs = this._currentToCs(rmsA, this._vsense)[0];
        await this._writeIholdIrun();
    }

    /** @param {number} iholdDelay - IHOLDDELAY 0-15. @param {number} tpowerdown - TPOWERDOWN 0-255. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setCurrentDelay(iholdDelay, tpowerdown) {
        if (!(iholdDelay >= 0 && iholdDelay <= 15 && tpowerdown >= 0 && tpowerdown <= 255)) {
            throw new RangeError('ihold_delay 0-15 / tpowerdown 0-255');
        }
        this._iholdDelay = iholdDelay;
        await this._writeIholdIrun();
        await this._writeReg(REG_TPOWERDOWN, tpowerdown);
    }

    /** @param {number} n - 1, 2, 4, ... 256. @returns {Promise<void>} @throws {RangeError} For any other value. */
    async setMicrosteps(n) {
        if (![1, 2, 4, 8, 16, 32, 64, 128, 256].includes(n)) throw new RangeError('microsteps must be a power of two, 1-256');
        await this._modify(REG_CHOPCONF, 0xF << 24, (8 - Math.log2(n)) << 24);
        this._microsteps = n;
    }

    /** @param {boolean} enabled - CHOPCONF.intpol. @returns {Promise<void>} */
    async setInterpolation(enabled) { await this._modify(REG_CHOPCONF, 1 << 28, enabled ? (1 << 28) : 0); }

    /** @param {boolean} spreadcycle - True for SpreadCycle only, false for StealthChop (switch at standstill). @returns {Promise<void>} */
    async setChopperMode(spreadcycle) { await this._modify(REG_GCONF, GCONF_EN_PWM_MODE, spreadcycle ? 0 : GCONF_EN_PWM_MODE); }

    /** @param {number} tpwmthrs - TPWMTHRS (20 bit); 0 disables. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setStealthchopThreshold(tpwmthrs) {
        if (!(tpwmthrs >= 0 && tpwmthrs <= 0xFFFFF)) throw new RangeError('tpwmthrs 0-1048575');
        await this._writeReg(REG_TPWMTHRS, tpwmthrs);
    }

    /** @param {number} thigh - THIGH (20 bit). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setHighVelocityThreshold(thigh) {
        if (!(thigh >= 0 && thigh <= 0xFFFFF)) throw new RangeError('thigh 0-1048575');
        await this._writeReg(REG_THIGH, thigh);
    }

    /** SpreadCycle (chm = 0) fields. @param {number} toff 0-15 @param {number} tbl 0-3 @param {number} hstrt 0-7 @param {number} hend raw 0-15 @returns {Promise<void>} @throws {RangeError} If a field is out of range. */
    async configureChopper(toff, tbl, hstrt, hend) {
        if (!(toff >= 0 && toff <= 15 && tbl >= 0 && tbl <= 3 && hstrt >= 0 && hstrt <= 7 && hend >= 0 && hend <= 15)) {
            throw new RangeError('chopper field out of range');
        }
        if (toff) this._toff = toff;
        await this._modify(REG_CHOPCONF, 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 11),
            toff | (tbl << 15) | (hstrt << 4) | (hend << 7));
    }

    /** Constant-off-time (chm = 1) fields. @param {number} toff 0-15 @param {number} tbl 0-3 @param {number} tfd 0-15 @param {number} offset -3..12 @param {boolean} disfdcc @param {boolean} rndtf @returns {Promise<void>} @throws {RangeError} If a field is out of range. */
    async configureConstantOffTime(toff, tbl, tfd, offset, disfdcc, rndtf) {
        if (!(toff >= 0 && toff <= 15 && tbl >= 0 && tbl <= 3 && tfd >= 0 && tfd <= 15 && offset >= -3 && offset <= 12)) {
            throw new RangeError('chopper field out of range');
        }
        if (toff) this._toff = toff;
        const mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (1 << 14) | (1 << 13) | (1 << 12) | (1 << 11);
        const value = toff | (tbl << 15) | ((tfd & 7) << 4) | ((offset + 3) << 7) | (1 << 14) |
            (rndtf ? (1 << 13) : 0) | (disfdcc ? (1 << 12) : 0) | (((tfd >> 3) & 1) << 11);
        await this._modify(REG_CHOPCONF, mask, value);
    }

    /** @param {boolean} vhighchm @param {boolean} vhighfs @returns {Promise<void>} */
    async configureHighVelocity(vhighchm, vhighfs) {
        await this._modify(REG_CHOPCONF, (1 << 19) | (1 << 18), (vhighchm ? (1 << 19) : 0) | (vhighfs ? (1 << 18) : 0));
    }

    /** @param {number} sync - CHOPCONF.SYNC 0-15. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setChopperSync(sync) {
        if (!(sync >= 0 && sync <= 15)) throw new RangeError('sync 0-15');
        await this._modify(REG_CHOPCONF, 0xF << 20, sync << 20);
    }

    /** PWMCONF fields (freewheel kept). @param {boolean} autoscale @param {boolean} symmetric @param {number} pwmAmpl 0-255 @param {number} pwmGrad 0-255 @param {number} pwmFreq 0-3 @returns {Promise<void>} @throws {RangeError} If a field is out of range. */
    async configureStealthchop(autoscale, symmetric, pwmAmpl, pwmGrad, pwmFreq) {
        if (!(pwmAmpl >= 0 && pwmAmpl <= 255 && pwmGrad >= 0 && pwmGrad <= 255 && pwmFreq >= 0 && pwmFreq <= 3)) {
            throw new RangeError('stealthchop field out of range');
        }
        await this._modify(REG_PWMCONF, (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF,
            (symmetric ? (1 << 19) : 0) | (autoscale ? (1 << 18) : 0) | (pwmFreq << 16) | (pwmGrad << 8) | pwmAmpl);
    }

    /** @param {number} mode - 0 normal, 1 freewheel, 2 low-side short, 3 high-side short. @returns {Promise<void>} @throws {RangeError} If mode is not 0-3. */
    async setFreewheel(mode) {
        if (!(mode >= 0 && mode <= 3)) throw new RangeError('freewheel mode 0-3');
        await this._modify(REG_PWMCONF, 3 << 20, mode << 20);
    }

    /** @param {boolean} highSensitivity - True for the 0.18 V range; disables automatic selection. @returns {Promise<void>} */
    async setVsense(highSensitivity) {
        await this._modify(REG_CHOPCONF, 1 << 17, highSensitivity ? (1 << 17) : 0);
        this._vsense = !!highSensitivity;
        this._autoVsense = false;
    }

    /** @param {boolean} enabled - Internal sense resistors (setCurrent then throws). @returns {Promise<void>} */
    async setInternalRsense(enabled) {
        await this._modify(REG_GCONF, GCONF_INTERNAL_RSENSE, enabled ? GCONF_INTERNAL_RSENSE : 0);
        this._internalRsense = !!enabled;
    }

    /** @param {boolean} enabled - Current reference from AIN_IREF. @returns {Promise<void>} */
    async setAnalogScaling(enabled) { await this._modify(REG_GCONF, GCONF_I_SCALE_ANALOG, enabled ? GCONF_I_SCALE_ANALOG : 0); }

    /** @param {boolean} inverted - GCONF.shaft. @returns {Promise<void>} */
    async setDirectionInverted(inverted) { await this._modify(REG_GCONF, GCONF_SHAFT, inverted ? GCONF_SHAFT : 0); }

    /**
     * Route events to DIAG0 / DIAG1 (GCONF bits 5-13).
     * @param {object} [o] - Booleans: diag0Error, diag0Otpw, diag0Stall, diag1Stall, diag1Index, diag1Onstate, diag0Pushpull, diag1Pushpull.
     * @returns {Promise<void>}
     */
    async configureDiag(o = {}) {
        const flags = [o.diag0Error, o.diag0Otpw, o.diag0Stall, o.diag1Stall, o.diag1Index, o.diag1Onstate];
        let value = 0;
        flags.forEach((f, i) => { if (f) value |= 1 << (5 + i); });
        if (o.diag0Pushpull) value |= 1 << 12;
        if (o.diag1Pushpull) value |= 1 << 13;
        await this._modify(REG_GCONF, 0x3FE0 & ~(1 << 11), value);
    }

    /** @param {boolean} enabled - CHOPCONF.dedge. @returns {Promise<void>} */
    async setDoubleEdge(enabled) { await this._modify(REG_CHOPCONF, 1 << 29, enabled ? (1 << 29) : 0); }

    /** @param {boolean} shortGnd - True keeps the short-to-GND protection. @returns {Promise<void>} */
    async setShortProtection(shortGnd) { await this._modify(REG_CHOPCONF, 1 << 30, shortGnd ? 0 : (1 << 30)); }

    /** @param {boolean} enabled - GCONF.small_hysteresis. @returns {Promise<void>} */
    async setSmallHysteresis(enabled) { await this._modify(REG_GCONF, GCONF_SMALL_HYSTERESIS, enabled ? GCONF_SMALL_HYSTERESIS : 0); }

    /** @param {boolean} enabled - GCONF.stop_enable. @returns {Promise<void>} */
    async setEmergencyStop(enabled) { await this._modify(REG_GCONF, GCONF_STOP_ENABLE, enabled ? GCONF_STOP_ENABLE : 0); }

    /** @param {number} sgt - StallGuard2 threshold -64..63 (lower is more sensitive). @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setStallThreshold(sgt) {
        if (!(sgt >= -64 && sgt <= 63)) throw new RangeError('sgt -64..63');
        await this._modify(REG_COOLCONF, 0x7F << 16, (sgt & 0x7F) << 16);
    }

    /** @param {boolean} enabled - COOLCONF.sfilt. @returns {Promise<void>} */
    async setStallguardFilter(enabled) { await this._modify(REG_COOLCONF, 1 << 24, enabled ? (1 << 24) : 0); }

    /** @param {number} tcoolthrs - TCOOLTHRS (20 bit); 0 disables. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setCoolstepThreshold(tcoolthrs) {
        if (!(tcoolthrs >= 0 && tcoolthrs <= 0xFFFFF)) throw new RangeError('tcoolthrs 0-1048575');
        await this._writeReg(REG_TCOOLTHRS, tcoolthrs);
    }

    /** CoolStep fields (sgt / sfilt kept). @param {number} semin 0-15 @param {number} semax 0-15 @param {number} seup 0-3 @param {number} sedn 0-3 @param {number} seimin 0|1 @returns {Promise<void>} @throws {RangeError} If a field is out of range. */
    async configureCoolstep(semin, semax, seup, sedn, seimin) {
        if (!(semin >= 0 && semin <= 15 && semax >= 0 && semax <= 15 && seup >= 0 && seup <= 3 &&
              sedn >= 0 && sedn <= 3 && (seimin === 0 || seimin === 1))) {
            throw new RangeError('COOLCONF field out of range');
        }
        await this._modify(REG_COOLCONF, 0xF | (3 << 5) | (0xF << 8) | (3 << 13) | (1 << 15),
            semin | (seup << 5) | (semax << 8) | (sedn << 13) | (seimin << 15));
    }

    /** @returns {Promise<number>} SG_RESULT 0-1023 (0 = highest load). */
    async readStallguard() { return (await this._readReg(REG_DRV_STATUS)) & 0x3FF; }

    /** @returns {Promise<boolean>} DRV_STATUS.StallGuard. */
    async isStalled() { return (((await this._readReg(REG_DRV_STATUS)) >>> 24) & 1) === 1; }

    /** @param {number} n - 0 for DIAG0, 1 for DIAG1. @returns {boolean} Pin level. @throws {RangeError} If n is not 0/1. @throws {Error} If that pin is not wired. */
    readDiag(n) {
        if (n !== 0 && n !== 1) throw new RangeError('n must be 0 or 1');
        const pin = this._diagPins[n];
        if (!pin) throw new Error('readDiag() requires diag' + n + 'Pin');
        return !!pin.isHigh();
    }

    /** Emit STEP pulses (no microstep conversion). @param {number} count - Pulses; negative reverses. @param {number} delayS - Time between edges in seconds. @returns {Promise<void>} */
    async step(count, delayS) {
        await this._dirPin.set(count >= 0);
        for (let i = 0; i < Math.abs(count); i++) {
            await this._stepPin.set(true);
            await sleep(delayS * 1000);
            await this._stepPin.set(false);
            await sleep(delayS * 1000);
        }
    }

    /** @param {boolean} enabled - GCONF.direct_mode. @returns {Promise<void>} */
    async setDirectMode(enabled) { await this._modify(REG_GCONF, GCONF_DIRECT_MODE, enabled ? GCONF_DIRECT_MODE : 0); }

    /** Write XDIRECT. @param {number} coilA -255..255 @param {number} coilB -255..255 @returns {Promise<void>} @throws {Error} If direct mode is off. @throws {RangeError} If out of range. */
    async setCoilCurrents(coilA, coilB) {
        if (!((this._shadow.get(REG_GCONF) ?? 0) & GCONF_DIRECT_MODE)) throw new Error('setCoilCurrents() requires direct mode');
        if (!(coilA >= -255 && coilA <= 255 && coilB >= -255 && coilB <= 255)) throw new RangeError('coil currents -255..255');
        await this._writeReg(REG_XDIRECT, ((coilA & 0x1FF) | ((coilB & 0x1FF) << 16)) >>> 0);
    }

    /** @param {number} vdcmin 0..2^23-1 @param {number} dcTime 0-1023 @param {number} dcSg 0-255 @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setDcstep(vdcmin, dcTime, dcSg) {
        if (!(vdcmin >= 0 && vdcmin <= 0x7FFFFF && dcTime >= 0 && dcTime <= 1023 && dcSg >= 0 && dcSg <= 255)) {
            throw new RangeError('DcStep field out of range');
        }
        await this._writeReg(REG_VDCMIN, vdcmin);
        await this._writeReg(REG_DCCTRL, (dcTime | (dcSg << 16)) >>> 0);
    }

    /** Program the microstep table (call at MSCNT = 0). @param {number[]} mslut - 8 words @param {number} mslutsel @param {number} mslutstart @returns {Promise<void>} @throws {RangeError} If mslut does not hold 8 words. */
    async writeMicrostepTable(mslut, mslutsel, mslutstart) {
        if (mslut.length !== 8) throw new RangeError('mslut needs 8 words');
        for (let i = 0; i < 8; i++) await this._writeReg(REG_MSLUT0 + i, mslut[i]);
        await this._writeReg(REG_MSLUTSEL, mslutsel);
        await this._writeReg(REG_MSLUTSTART, mslutstart);
    }

    /** @returns {Promise<number>} MSCNT 0-1023. */
    async microstepPosition() { return (await this._readReg(REG_MSCNT)) & 0x3FF; }

    /** @returns {Promise<number>} DRV_STATUS.CS_ACTUAL 0-31. */
    async readCurrentScale() { return ((await this._readReg(REG_DRV_STATUS)) >>> 16) & 0x1F; }

    /** @returns {Promise<number[]>} Signed [CUR_A, CUR_B] from MSCURACT. */
    async readCoilCurrents() {
        const raw = await this._readReg(REG_MSCURACT);
        return [toSigned(raw >>> 16, 9), toSigned(raw, 9)];
    }

    /** @returns {Promise<number>} TSTEP (20 bit). */
    async readTstep() { return (await this._readReg(REG_TSTEP)) & 0xFFFFF; }

    /** @returns {Promise<number>} PWM_SCALE 0-255. */
    async readPwmScale() { return (await this._readReg(REG_PWM_SCALE)) & 0xFF; }

    /** @returns {Promise<number>} LOST_STEPS (20 bit). */
    async readLostSteps() { return (await this._readReg(REG_LOST_STEPS)) & 0xFFFFF; }

    /** @returns {Promise<object>} {stst, fsactive, cs_actual, sg_result, stallguard, otpw, ot, short_gnd, open_load}. */
    async readStatus() {
        const s = await this._readReg(REG_DRV_STATUS);
        return {
            stst: !!(s & 0x80000000), fsactive: !!(s & (1 << 15)), cs_actual: (s >>> 16) & 0x1F,
            sg_result: s & 0x3FF, stallguard: !!(s & (1 << 24)), otpw: !!(s & (1 << 26)),
            ot: !!(s & (1 << 25)), short_gnd: !!(s & (3 << 27)), open_load: !!(s & (3 << 29)),
        };
    }

    /** Reads GSTAT; the chip clears the flags on read. @returns {Promise<object>} {reset, drv_err, uv_cp}. */
    async readGlobalStatus() {
        const g = await this._readReg(REG_GSTAT);
        return { reset: !!(g & 1), drv_err: !!(g & 2), uv_cp: !!(g & 4) };
    }

    /** @returns {Promise<object>} {raw, step, dir, dcen, dcin, enn, dco, version}. */
    async readIoin() {
        const raw = await this._readReg(REG_IOIN);
        return {
            raw, step: !!(raw & 1), dir: !!(raw & 2), dcen: !!(raw & 4), dcin: !!(raw & 8),
            enn: !!(raw & 16), dco: !!(raw & 32), version: (raw >>> 24) & 0xFF,
        };
    }
}

module.exports = { TMC2130Minimal, TMC2130Full };
