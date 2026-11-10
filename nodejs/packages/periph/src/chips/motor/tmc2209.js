'use strict';

const _SYNC = 0x05;
const _MASTER_ADDR = 0xFF;

const _REG_GCONF = 0x00;
const _REG_GSTAT = 0x01;
const _REG_IFCNT = 0x02;
const _REG_NODECONF = 0x03;
const _REG_OTP_READ = 0x05;
const _REG_IOIN = 0x06;
const _REG_IHOLD_IRUN = 0x10;
const _REG_TPOWERDOWN = 0x11;
const _REG_TSTEP = 0x12;
const _REG_TPWMTHRS = 0x13;
const _REG_TCOOLTHRS = 0x14;
const _REG_VACTUAL = 0x22;
const _REG_SGTHRS = 0x40;
const _REG_SG_RESULT = 0x41;
const _REG_COOLCONF = 0x42;
const _REG_MSCNT = 0x6A;
const _REG_MSCURACT = 0x6B;
const _REG_CHOPCONF = 0x6C;
const _REG_DRV_STATUS = 0x6F;
const _REG_PWMCONF = 0x70;
const _REG_PWM_SCALE = 0x71;
const _REG_PWM_AUTO = 0x72;

const _READABLE = new Set([0x00, 0x01, 0x02, 0x05, 0x06, 0x07, 0x12, 0x41, 0x6A, 0x6B, 0x6C, 0x6F, 0x70, 0x71, 0x72]);
const _WRITABLE = new Set([0x00, 0x01, 0x03, 0x10, 0x11, 0x13, 0x14, 0x22, 0x40, 0x42, 0x6C, 0x70]);

const _GCONF_I_SCALE_ANALOG = 1 << 0;
const _GCONF_INTERNAL_RSENSE = 1 << 1;
const _GCONF_EN_SPREADCYCLE = 1 << 2;
const _GCONF_SHAFT = 1 << 3;
const _GCONF_INDEX_OTPW = 1 << 4;
const _GCONF_INDEX_STEP = 1 << 5;
const _GCONF_PDN_DISABLE = 1 << 6;
const _GCONF_MSTEP_REG_SELECT = 1 << 7;
const _GCONF_MULTISTEP_FILT = 1 << 8;

const _VERSION = 0x21;
const _PWMCONF_DEFAULT = 0xC10D0024;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

function toSigned(value, bits) {
    const v = value & ((1 << bits) - 1);
    return (v & (1 << (bits - 1))) ? v - (1 << bits) : v;
}

/**
 * Trinamic TMC2209 stepper driver over single-wire UART
 * — minimal interface.
 *
 * Brings the driver up in UART mode (pdn_disable, microstep resolution from
 * CHOPCONF.MRES), StealthChop, 16 microsteps with 256-step interpolation and
 * 0.5 A RMS run current, then lets the internal step generator (VACTUAL)
 * spin the motor -- no STEP pulses required. Datagram framing (sync byte,
 * CRC8, host echo) is handled here; the connection only needs plain
 * write / read / writeRead.
 *
 * The constructor performs no I/O; call `await init()` before use.
 */
class TMC2209Minimal {
    /**
     * @param {object} connection - UART connection to the chip's PDN_UART pin.
     * @param {object} [options]
     * @param {number} [options.rSenseOhm=0.11] - External sense resistor in ohm.
     * @param {number} [options.address=0] - UART node address 0-3 (MS1_AD0 / MS2_AD1 pins).
     * @param {object|null} [options.enPin=null] - OutputPin wired to ENN (active low).
     * @param {boolean} [options.echo=true] - True when TX and RX share PDN_UART.
     * @throws {RangeError} If address is not 0-3.
     */
    constructor(connection, options = {}) {
        this._address = options.address ?? 0;
        if (!Number.isInteger(this._address) || this._address < 0 || this._address > 3) {
            throw new RangeError('address must be 0-3');
        }
        this._multiNode = options.multiNode ?? false;
        this._conn = connection;
        this._rSense = options.rSenseOhm ?? 0.11;
        this._enPin = options.enPin ?? null;
        this._echo = options.echo ?? true;
        this._fclk = options.fclkHz ?? 12000000;
        this._shadow = new Map();
        this._microsteps = 16;
        this._vsense = false;
        this._internalRsense = false;
        this._toff = 3;
        this._runCs = 0;
        this._holdCs = 0;
        this._iholdDelay = 6;
    }

    async _recover() {
        if (typeof this._conn.flush === 'function') await this._conn.flush();
        await sleep(2);
    }

    async _writeDatagram(reg, value) {
        const body = Buffer.from([_SYNC, this._address, (reg | 0x80) & 0xFF,
            (value >>> 24) & 0xFF, (value >>> 16) & 0xFF, (value >>> 8) & 0xFF, value & 0xFF]);
        const datagram = Buffer.concat([body, Buffer.from([crc8(body)])]);
        await this._conn.write(datagram);
        if (this._echo) {
            const echoed = Buffer.from(await this._conn.read(8));
            if (!echoed.equals(datagram)) throw new Error('TMC2209: write echo mismatch');
        }
    }

    async _readDatagram(reg) {
        const head = Buffer.from([_SYNC, this._address, reg & 0x7F]);
        const request = Buffer.concat([head, Buffer.from([crc8(head)])]);
        let reply;
        if (this._echo) {
            const raw = Buffer.from(await this._conn.writeRead(request, 12));
            if (raw.length !== 12) throw new Error('TMC2209: short read');
            if (!raw.subarray(0, 4).equals(request)) throw new Error('TMC2209: read echo mismatch');
            reply = raw.subarray(4);
        } else {
            reply = Buffer.from(await this._conn.writeRead(request, 8));
            if (reply.length !== 8) throw new Error('TMC2209: short read');
        }
        if (reply[0] !== _SYNC || reply[1] !== _MASTER_ADDR || reply[2] !== (reg & 0x7F)) {
            throw new Error('TMC2209: malformed reply');
        }
        if (crc8(reply.subarray(0, 7)) !== reply[7]) throw new Error('TMC2209: reply CRC error');
        return reply.readUInt32BE(3);
    }

    async _writeReg(reg, value) {
        value = value >>> 0;
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                await this._writeDatagram(reg, value);
                break;
            } catch (e) {
                if (attempt) throw e;
                await this._recover();
            }
        }
        this._txCount = ((this._txCount ?? 0) + 1);
        this._shadow.set(reg, value);
    }

    async _readReg(reg) {
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                return await this._readDatagram(reg);
            } catch (e) {
                if (attempt) throw e;
                await this._recover();
            }
        }
    }

    async _modify(reg, mask, value) {
        const current = this._shadow.get(reg) ?? 0;
        await this._writeReg(reg, ((current & ~mask) | (value & mask)) >>> 0);
    }

    _currentToCs(rmsA) {
        if (this._internalRsense) throw new Error('set_current is unsupported with internal Rsense');
        const vfs = this._vsense ? 0.180 : 0.325;
        const cs = Math.round(rmsA * Math.SQRT2 * (this._rSense + 0.020) * 32 / vfs) - 1;
        return Math.max(0, Math.min(31, cs));
    }

    async _writeIholdIrun() {
        await this._writeReg(_REG_IHOLD_IRUN,
            (this._holdCs & 0x1F) | ((this._runCs & 0x1F) << 8) | ((this._iholdDelay & 0xF) << 16));
    }

    /**
     * Probe the chip, apply the default configuration and verify IFCNT.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not answer, has an unexpected identity
     *   or does not acknowledge the writes.
     */
    async init() {
        if (this._enPin) await this._enPin.set(true);
        await sleep(10);
        const baseline = (await this._readReg(_REG_IFCNT)) & 0xFF;
        const ioin = await this._readReg(_REG_IOIN);
        const version = (ioin >>> 24) & 0xFF;
        if (version !== _VERSION) {
            throw new Error('TMC2209 not found (IOIN=0x' + ioin.toString(16) + ')');
        }
        let count = 6;
        if (this._multiNode) {
            await this._writeReg(_REG_NODECONF, 2 << 8);
            count = 7;
        }
        await this._writeReg(_REG_GCONF, _GCONF_I_SCALE_ANALOG | _GCONF_PDN_DISABLE |
            _GCONF_MSTEP_REG_SELECT | _GCONF_MULTISTEP_FILT);
        await this._writeReg(_REG_GSTAT, 0x07);
        this._runCs = this._currentToCs(0.5);
        this._holdCs = this._currentToCs(0.5 / 3);
        await this._writeIholdIrun();
        await this._writeReg(_REG_TPOWERDOWN, 20);
        let chop = await this._readReg(_REG_CHOPCONF);
        chop &= ~(0xF | (3 << 15) | (7 << 4) | (0xF << 7) | (0xF << 24) | (1 << 17));
        chop |= 3 | (2 << 15) | (5 << 4) | (4 << 24) | (1 << 28);
        await this._writeReg(_REG_CHOPCONF, chop);
        await this._writeReg(_REG_PWMCONF, _PWMCONF_DEFAULT);
        const ifcnt = (await this._readReg(_REG_IFCNT)) & 0xFF;
        if (ifcnt !== ((baseline + count) & 0xFF)) {
            throw new Error('TMC2209: IFCNT mismatch (writes not acknowledged)');
        }
    }

    /** Switch the power stage on (ENN low, or TOFF restored without a pin). @returns {Promise<void>} */
    async enable() {
        if (this._enPin) await this._enPin.set(false);
        else await this._writeReg(_REG_CHOPCONF, (((this._shadow.get(_REG_CHOPCONF) & ~0xF) | this._toff) >>> 0));
    }

    /** Switch the power stage off; the motor freewheels. @returns {Promise<void>} */
    async disable() {
        if (this._enPin) { await this._enPin.set(true); return; }
        const chop = this._shadow.get(_REG_CHOPCONF);
        if (chop & 0xF) this._toff = chop & 0xF;
        await this._writeReg(_REG_CHOPCONF, (chop & ~0xF) >>> 0);
    }

    /**
     * Set the run current; hold current becomes one third of it.
     * @param {number} rmsA - Run current in A RMS (at most 1.4).
     * @returns {Promise<void>}
     */
    async setCurrent(rmsA) {
        this._runCs = this._currentToCs(rmsA);
        this._holdCs = this._currentToCs(rmsA / 3);
        await this._writeIholdIrun();
    }

    /**
     * Move with the internal step generator (VACTUAL).
     * @param {number} stepsPerS - Full steps per second at the configured microstepping; sign = direction, 0 stops.
     * @returns {Promise<void>}
     */
    async run(stepsPerS) {
        let v = Math.round(stepsPerS * this._microsteps * 16777216 / this._fclk);
        v = Math.max(-8388607, Math.min(8388607, v));
        await this._writeReg(_REG_VACTUAL, (v & 0xFFFFFF) >>> 0);
    }

    /** Stop the internal step generator (VACTUAL = 0). @returns {Promise<void>} */
    async stop() { await this._writeReg(_REG_VACTUAL, 0); }

    /** @returns {Promise<boolean>} True when DRV_STATUS.stst is set. */
    async isStandstill() { return ((await this._readReg(_REG_DRV_STATUS)) >>> 31) === 1; }

    /** @returns {Promise<number>} Bitmask of FAULT_* constants, 0 when none. */
    async readFault() {
        const s = await this._readReg(_REG_DRV_STATUS);
        let f = 0;
        if (s & 0x01) f |= TMC2209Minimal.FAULT_OVERTEMP_WARNING;
        if (s & 0x02) f |= TMC2209Minimal.FAULT_OVERTEMP;
        if (s & 0x0C) f |= TMC2209Minimal.FAULT_SHORT_GND;
        if (s & 0x30) f |= TMC2209Minimal.FAULT_SHORT_SUPPLY;
        if (s & 0xC0) f |= TMC2209Minimal.FAULT_OPEN_LOAD;
        return f;
    }
}

TMC2209Minimal.FAULT_OVERTEMP_WARNING = 0x01;
TMC2209Minimal.FAULT_OVERTEMP = 0x02;
TMC2209Minimal.FAULT_SHORT_GND = 0x04;
TMC2209Minimal.FAULT_SHORT_SUPPLY = 0x08;
TMC2209Minimal.FAULT_OPEN_LOAD = 0x10;

/**
 * Complete TMC2209 driver. Extends TMC2209Minimal with raw
 * register access, independent hold current, microstep and chopper
 * configuration, StealthChop tuning, GPIO STEP/DIR pulsing and every status
 * read-back.
 */
class TMC2209Full extends TMC2209Minimal {
    /**
     * @param {object} connection - UART connection to PDN_UART.
     * @param {object} [options]
     * @param {number} [options.rSenseOhm=0.11] - External sense resistor in ohm.
     * @param {object|null} [options.enPin=null] - OutputPin wired to ENN.
     * @param {boolean} [options.echo=true] - True when TX and RX share PDN_UART.
     * @param {object|null} [options.stepPin=null] - OutputPin wired to STEP.
     * @param {object|null} [options.dirPin=null] - OutputPin wired to DIR.
     * @param {object|null} [options.diagPin=null] - InputPin wired to DIAG (stall / driver error).
     * @param {number} [options.address=0] - UART node address 0-3.
     * @param {boolean} [options.multiNode=false] - Several chips share the UART; init writes NODECONF.SENDDELAY = 2 first.
     * @param {number} [options.fclkHz=12000000] - Chip clock in Hz.
     */
    constructor(connection, options = {}) {
        super(connection, options);
        this._diagPin = options.diagPin ?? null;
        this._stepPin = options.stepPin ?? null;
        this._dirPin = options.dirPin ?? null;
    }

    /** @param {number} reg - Readable register address. @returns {Promise<number>} Raw 32-bit value. @throws {RangeError} For write-only/reserved registers. */
    async readRegister(reg) {
        if (!_READABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not readable');
        return this._readReg(reg);
    }

    /** @param {number} reg - Writable register address. @param {number} value - 32-bit value. @returns {Promise<void>} @throws {RangeError} For read-only/reserved registers, OTP_PROG and FACTORY_CONF. */
    async writeRegister(reg, value) {
        if (!_WRITABLE.has(reg)) throw new RangeError('register 0x' + reg.toString(16) + ' is not writable');
        await this._writeReg(reg, value);
    }

    /** @param {number} rmsA - Hold current in A RMS. @returns {Promise<void>} */
    async setHoldCurrent(rmsA) {
        this._holdCs = this._currentToCs(rmsA);
        await this._writeIholdIrun();
    }

    /** @param {number} iholdDelay - IHOLDDELAY 0-15. @param {number} tpowerdown - TPOWERDOWN 0-255. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setCurrentDelay(iholdDelay, tpowerdown) {
        if (!(iholdDelay >= 0 && iholdDelay <= 15 && tpowerdown >= 0 && tpowerdown <= 255)) {
            throw new RangeError('ihold_delay 0-15 / tpowerdown 0-255');
        }
        this._iholdDelay = iholdDelay;
        await this._writeIholdIrun();
        await this._writeReg(_REG_TPOWERDOWN, tpowerdown);
    }

    /** @param {number} n - 1, 2, 4, 8, 16, 32, 64, 128 or 256. @returns {Promise<void>} @throws {RangeError} Otherwise. */
    async setMicrosteps(n) {
        const table = { 256: 0, 128: 1, 64: 2, 32: 3, 16: 4, 8: 5, 4: 6, 2: 7, 1: 8 };
        if (!(n in table)) throw new RangeError('microsteps must be a power of two, 1-256');
        await this._modify(_REG_CHOPCONF, 0xF << 24, table[n] << 24);
        this._microsteps = n;
    }

    /** @param {boolean} enabled - CHOPCONF.intpol. @returns {Promise<void>} */
    async setInterpolation(enabled) { await this._modify(_REG_CHOPCONF, 1 << 28, enabled ? (1 << 28) : 0); }

    /** @param {boolean} spreadcycle - true = SpreadCycle, false = StealthChop. @returns {Promise<void>} */
    async setChopperMode(spreadcycle) { await this._modify(_REG_GCONF, _GCONF_EN_SPREADCYCLE, spreadcycle ? _GCONF_EN_SPREADCYCLE : 0); }

    /** @param {number} tpwmthrs - TPWMTHRS (20 bit); 0 disables the threshold. @returns {Promise<void>} */
    async setStealthchopThreshold(tpwmthrs) { await this._writeReg(_REG_TPWMTHRS, tpwmthrs & 0xFFFFF); }

    /** @param {number} toff @param {number} tbl @param {number} hstrt @param {number} hend - Raw HEND field 0-15. @returns {Promise<void>} */
    async configureChopper(toff, tbl, hstrt, hend) {
        const mask = 0xF | (3 << 15) | (7 << 4) | (0xF << 7);
        const value = (toff & 0xF) | ((tbl & 3) << 15) | ((hstrt & 7) << 4) | ((hend & 0xF) << 7);
        if (toff) this._toff = toff & 0xF;
        await this._modify(_REG_CHOPCONF, mask, value);
    }

    /** Write the StealthChop fields of PWMCONF. @returns {Promise<void>} */
    async configureStealthchop(autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) {
        const mask = (0xF << 28) | (0xF << 24) | (1 << 19) | (1 << 18) | (3 << 16) | (0xFF << 8) | 0xFF;
        const value = ((pwmLim & 0xF) << 28) | ((pwmReg & 0xF) << 24) | (autograd ? (1 << 19) : 0) |
            (autoscale ? (1 << 18) : 0) | ((pwmFreq & 3) << 16) | ((pwmGrad & 0xFF) << 8) | (pwmOfs & 0xFF);
        await this._modify(_REG_PWMCONF, mask, value);
    }

    /** @param {number} mode - PWMCONF.freewheel 0-3. @returns {Promise<void>} @throws {RangeError} Otherwise. */
    async setFreewheel(mode) {
        if (!(mode >= 0 && mode <= 3)) throw new RangeError('freewheel mode 0-3');
        await this._modify(_REG_PWMCONF, 3 << 20, mode << 20);
    }

    /** @param {boolean} highSensitivity - true for the low 0.18 V range (changes current scaling). @returns {Promise<void>} */
    async setVsense(highSensitivity) {
        await this._modify(_REG_CHOPCONF, 1 << 17, highSensitivity ? (1 << 17) : 0);
        this._vsense = !!highSensitivity;
    }

    /** @param {boolean} enabled - GCONF.internal_Rsense (setCurrent() then throws). @returns {Promise<void>} */
    async setInternalRsense(enabled) {
        await this._modify(_REG_GCONF, _GCONF_INTERNAL_RSENSE, enabled ? _GCONF_INTERNAL_RSENSE : 0);
        this._internalRsense = !!enabled;
    }

    /** @param {boolean} enabled - GCONF.I_scale_analog. @returns {Promise<void>} */
    async setAnalogScaling(enabled) { await this._modify(_REG_GCONF, _GCONF_I_SCALE_ANALOG, enabled ? _GCONF_I_SCALE_ANALOG : 0); }

    /** @param {boolean} inverted - GCONF.shaft. @returns {Promise<void>} */
    async setDirectionInverted(inverted) { await this._modify(_REG_GCONF, _GCONF_SHAFT, inverted ? _GCONF_SHAFT : 0); }

    /** @param {number} mode - 0 first microstep, 1 otpw, 2 step pulses. @returns {Promise<void>} @throws {RangeError} Otherwise. */
    async setIndexOutput(mode) {
        if (![0, 1, 2].includes(mode)) throw new RangeError('index mode 0-2');
        const bits = [0, _GCONF_INDEX_OTPW, _GCONF_INDEX_STEP][mode];
        await this._modify(_REG_GCONF, _GCONF_INDEX_OTPW | _GCONF_INDEX_STEP, bits);
    }

    /** @param {boolean} shortLs - keep low-side short protection. @param {boolean} shortGnd - keep short-to-GND protection. @returns {Promise<void>} */
    async setDriverProtection(shortLs, shortGnd) {
        const value = (shortLs ? 0 : 2 ** 31) + (shortGnd ? 0 : (1 << 30));
        await this._modify(_REG_CHOPCONF, (2 ** 31) + (1 << 30), value);
    }

    /** @param {boolean} enabled - CHOPCONF.dedge. @returns {Promise<void>} */
    async setDoubleEdge(enabled) { await this._modify(_REG_CHOPCONF, 1 << 29, enabled ? (1 << 29) : 0); }

    /** @param {number} n - NODECONF.SENDDELAY 0-15. @returns {Promise<void>} @throws {RangeError} If out of range. */
    async setSendDelay(n) {
        if (!(n >= 0 && n <= 15)) throw new RangeError('send delay 0-15');
        await this._writeReg(_REG_NODECONF, n << 8);
    }

    /**
     * Set the StallGuard4 threshold (SGTHRS); a stall is signalled when SG_RESULT <= 2 * sgthrs.
     * @param {number} sgthrs - 0-255, higher is more sensitive.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setStallThreshold(sgthrs) {
        if (!Number.isInteger(sgthrs) || sgthrs < 0 || sgthrs > 255) throw new RangeError('sgthrs 0-255');
        await this._writeReg(_REG_SGTHRS, sgthrs);
    }

    /**
     * Set the lower velocity limit for CoolStep and the DIAG stall output.
     * @param {number} tcoolthrs - TCOOLTHRS (20 bit); 0 disables.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setCoolstepThreshold(tcoolthrs) {
        if (!Number.isInteger(tcoolthrs) || tcoolthrs < 0 || tcoolthrs > 0xFFFFF) throw new RangeError('tcoolthrs 0-1048575');
        await this._writeReg(_REG_TCOOLTHRS, tcoolthrs);
    }

    /**
     * Write COOLCONF.
     * @param {number} semin - Lower threshold 0-15; 0 disables CoolStep.
     * @param {number} semax - Upper threshold offset 0-15.
     * @param {number} seup - Current up step width 0-3 (1, 2, 4, 8).
     * @param {number} sedn - Current down step speed 0-3.
     * @param {number} seimin - 0 for half IRUN minimum, 1 for quarter.
     * @returns {Promise<void>}
     * @throws {RangeError} If a field is out of range.
     */
    async configureCoolstep(semin, semax, seup, sedn, seimin) {
        if (!(semin >= 0 && semin <= 15 && semax >= 0 && semax <= 15 && seup >= 0 && seup <= 3 &&
              sedn >= 0 && sedn <= 3 && (seimin === 0 || seimin === 1))) {
            throw new RangeError('COOLCONF field out of range');
        }
        await this._writeReg(_REG_COOLCONF,
            (semin | (seup << 5) | (semax << 8) | (sedn << 13) | (seimin << 15)) >>> 0);
    }

    /** @returns {Promise<number>} SG_RESULT 0-510 (higher means less load). */
    async readStallguard() { return (await this._readReg(_REG_SG_RESULT)) & 0x3FF; }

    /**
     * @returns {boolean} DIAG pin level (stall or driver error).
     * @throws {Error} If no diagPin was given.
     */
    isStalled() {
        if (!this._diagPin) throw new Error('isStalled() requires diagPin');
        return !!this._diagPin.isHigh();
    }

    /**
     * Emit STEP pulses on the GPIO pin.
     * @param {number} count - Pulses; negative reverses (needs dirPin).
     * @param {number} delayS - High and low time of each pulse in seconds.
     * @returns {Promise<void>}
     */
    async step(count, delayS) {
        if (!this._stepPin) throw new Error('step() requires stepPin');
        if (count < 0 && !this._dirPin) throw new RangeError('negative count requires dirPin');
        if (this._dirPin) await this._dirPin.set(count >= 0);
        for (let i = 0; i < Math.abs(count); i++) {
            await this._stepPin.set(true);
            await sleep(delayS * 1000);
            await this._stepPin.set(false);
            await sleep(delayS * 1000);
        }
    }

    /** @returns {Promise<number>} MSCNT 0-1023. */
    async microstepPosition() { return (await this._readReg(_REG_MSCNT)) & 0x3FF; }

    /** @returns {Promise<number>} DRV_STATUS.CS_ACTUAL 0-31. */
    async readCurrentScale() { return ((await this._readReg(_REG_DRV_STATUS)) >>> 16) & 0x1F; }

    /** @returns {Promise<[number, number]>} Signed MSCURACT values (bits 8:0, bits 24:16). */
    async readCoilCurrents() {
        const raw = await this._readReg(_REG_MSCURACT);
        return [toSigned(raw, 9), toSigned(raw >>> 16, 9)];
    }

    /** @returns {Promise<number>} TSTEP in clock periods (20 bit). */
    async readTstep() { return (await this._readReg(_REG_TSTEP)) & 0xFFFFF; }

    /** @returns {Promise<number>} t120..t157 as bits 0..3. */
    async readTemperatureFlags() { return ((await this._readReg(_REG_DRV_STATUS)) >>> 8) & 0xF; }

    /** @returns {Promise<object>} Decoded DRV_STATUS. */
    async readStatus() {
        const s = await this._readReg(_REG_DRV_STATUS);
        return {
            stst: ((s >>> 31) & 1) === 1,
            stealth: ((s >>> 30) & 1) === 1,
            cs_actual: (s >>> 16) & 0x1F,
            otpw: (s & 0x01) !== 0,
            ot: (s & 0x02) !== 0,
            short_gnd: (s & 0x0C) !== 0,
            short_supply: (s & 0x30) !== 0,
            open_load: (s & 0xC0) !== 0,
            t120: (s & (1 << 8)) !== 0,
            t143: (s & (1 << 9)) !== 0,
            t150: (s & (1 << 10)) !== 0,
            t157: (s & (1 << 11)) !== 0,
        };
    }

    /** @param {boolean} [clear=false] - Write the flags back (write-1-to-clear). @returns {Promise<object>} {reset, drv_err, uv_cp}. */
    async readGlobalStatus(clear = false) {
        const g = await this._readReg(_REG_GSTAT);
        if (clear) await this._writeReg(_REG_GSTAT, 0x07);
        return { reset: (g & 1) !== 0, drv_err: (g & 2) !== 0, uv_cp: (g & 4) !== 0 };
    }

    /** @returns {Promise<number>} IFCNT write counter 0-255. */
    async readIfcnt() { return (await this._readReg(_REG_IFCNT)) & 0xFF; }

    /** @returns {Promise<object>} {raw, enn, ms1, ms2, diag, pdn_uart, step, spread, dir, version}. */
    async readIoin() {
        const raw = await this._readReg(_REG_IOIN);
        return {
            raw, enn: (raw & 1) !== 0, ms1: (raw & 4) !== 0, ms2: (raw & 8) !== 0,
            diag: (raw & 0x10) !== 0, pdn_uart: (raw & 0x40) !== 0, step: (raw & 0x80) !== 0,
            spread: (raw & 0x100) !== 0, dir: (raw & 0x200) !== 0, version: (raw >>> 24) & 0xFF,
        };
    }

    /** @returns {Promise<number>} OTP_READ 24-bit word. */
    async readOtp() { return (await this._readReg(_REG_OTP_READ)) & 0xFFFFFF; }

    /** @returns {Promise<object>} {pwm_scale_sum, pwm_scale_auto, pwm_ofs_auto, pwm_grad_auto}. */
    async readPwmAuto() {
        const scale = await this._readReg(_REG_PWM_SCALE);
        const auto = await this._readReg(_REG_PWM_AUTO);
        return {
            pwm_scale_sum: scale & 0xFF,
            pwm_scale_auto: toSigned(scale >>> 16, 9),
            pwm_ofs_auto: auto & 0xFF,
            pwm_grad_auto: (auto >>> 16) & 0xFF,
        };
    }
}

module.exports = { TMC2209Minimal, TMC2209Full, crc8 };
