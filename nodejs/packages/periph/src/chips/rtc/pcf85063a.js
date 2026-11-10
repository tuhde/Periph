'use strict';

const _REG_CONTROL_1     = 0x00;
const _REG_CONTROL_2     = 0x01;
const _REG_OFFSET        = 0x02;
const _REG_RAM           = 0x03;
const _REG_SECONDS       = 0x04;
const _REG_SECOND_ALARM  = 0x0B;
const _REG_TIMER_VALUE   = 0x10;
const _REG_TIMER_MODE    = 0x11;

// Control_1 (0x00) bits.
const _C1_STOP    = 0x20;
const _C1_SR      = 0x10;
const _C1_CIE     = 0x04;
const _C1_12_24   = 0x02;
const _C1_CAP_SEL = 0x01;

// Control_2 (0x01) bits.
const _C2_AIE   = 0x80;
const _C2_AF    = 0x40;
const _C2_MI    = 0x20;
const _C2_HMI   = 0x10;
const _C2_TF    = 0x08;
const _C2_COF   = 0x07;
const _C2_FLAGS = 0x48; // AF|TF: write 0 clears, 1 keeps

// Timer_mode (0x11) bits.
const _TM_TCF_MASK = 0x18;
const _TM_TE       = 0x04;
const _TM_TIE      = 0x02;
const _TM_TI_TP    = 0x01;

const _SECONDS_OS = 0x80;

const _SOURCE_CLOCKS = { '4096hz': 0x00, '64hz': 0x01, '1hz': 0x02, '1_60hz': 0x03 };

const _CLKOUT_COF = { 32768: 0, 16384: 1, 8192: 2, 4096: 3, 2048: 4, 1024: 5, 1: 6 };

function _bcdToInt(b) {
    return ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
}

function _intToBcd(v) {
    return (Math.floor(v / 10) << 4) | (v % 10);
}

/**
 * PCF85063A low-power I²C real-time clock and calendar (NXP) — minimal
 * interface.
 *
 * Reads/writes the calendar clock with no configuration beyond the
 * connection. `init()` forces 24-hour mode with the clock running (STOP=0);
 * it writes no time register and does not reset the chip. Fixed I²C address
 * 0x51. There is no battery switch-over — the chip is fed from a single VDD
 * supply.
 *
 * `weekday` follows the datasheet's suggested assignment, 0=Sunday ..
 * 6=Saturday — the Weekdays register has no hardware-enforced meaning.
 */
class PCF85063AMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection at address 0x51.
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg & 0xFF, value & 0xFF);
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg & 0xFF, 1))[0];
    }

    async _readRegs(reg, n) {
        return this._conn.readReg(reg & 0xFF, n);
    }

    async _readControl1() {
        // SR always reads 0; mask it so a read-modify-write never resets.
        return (await this._readReg(_REG_CONTROL_1)) & ~_C1_SR & 0xFF;
    }

    /**
     * Confirm the device answers on the bus (plain Control_1 read — the
     * PCF85063A has no identity register) and force 24-hour mode with
     * STOP=0.
     * @returns {Promise<void>}
     */
    async init() {
        await this._readReg(_REG_CONTROL_1);
        const ctrl1 = await this._readControl1();
        await this._writeReg(_REG_CONTROL_1, ctrl1 & ~(_C1_STOP | _C1_12_24) & 0xFF);
    }

    /**
     * Read the calendar clock (one 7-byte burst).
     * @returns {Promise<{year: number, month: number, day: number, weekday: number, hour: number, minute: number, second: number}>}
     *   `year` is 2000-2099, `hour` is 0-23, `weekday` is 0=Sunday..6=Saturday.
     */
    async getDatetime() {
        const raw = await this._readRegs(_REG_SECONDS, 7);
        return {
            year:    2000 + _bcdToInt(raw[6]),
            month:   _bcdToInt(raw[5] & 0x1F),
            day:     _bcdToInt(raw[3] & 0x3F),
            weekday: raw[4] & 0x07,
            hour:    _bcdToInt(raw[2] & 0x3F),
            minute:  _bcdToInt(raw[1] & 0x7F),
            second:  _bcdToInt(raw[0] & 0x7F),
        };
    }

    /**
     * Set the calendar clock using the STOP-bit precision start: freeze the
     * divider chain (STOP=1), write all seven time/date registers in one
     * transaction, then release STOP. Forces 24-hour mode and clears the OS
     * flag (the time is now known-good).
     * @param {number} year    - 2000-2099 (a RangeError is thrown otherwise).
     * @param {number} month   - 1-12.
     * @param {number} day     - 1-31.
     * @param {number} weekday - 0=Sunday..6=Saturday.
     * @param {number} hour    - 0-23.
     * @param {number} minute  - 0-59.
     * @param {number} second  - 0-59.
     * @returns {Promise<void>}
     */
    async setDatetime(year, month, day, weekday, hour, minute, second) {
        if (year < 2000 || year > 2099) throw new RangeError('year must be 2000-2099');
        const ctrl1 = (await this._readControl1()) & ~_C1_12_24 & 0xFF;
        await this._writeReg(_REG_CONTROL_1, ctrl1 | _C1_STOP);
        await this._conn.writeReg(_REG_SECONDS, Buffer.from([_intToBcd(second) & 0x7F, // OS = 0
            _intToBcd(minute),
            _intToBcd(hour) & 0x3F,
            _intToBcd(day),
            weekday & 0x07,
            _intToBcd(month),
            _intToBcd(year - 2000),]));
        await this._writeReg(_REG_CONTROL_1, ctrl1 & ~_C1_STOP & 0xFF);
    }
}

/**
 * PCF85063A full interface — extends Minimal with the five-field alarm, the
 * countdown timer, minute / half-minute interrupts, programmable CLKOUT,
 * offset calibration, oscillator-load selection, oscillator-stop detection,
 * clock stop/start, the free RAM byte, software reset and the interrupt API.
 *
 * The INT pin is open-drain and active-low; CLKOUT is a separate pin.
 */
class PCF85063AFull extends PCF85063AMinimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _updateControl1(clearMask, setBits) {
        const ctrl1 = await this._readControl1();
        await this._writeReg(_REG_CONTROL_1, (ctrl1 & ~clearMask) | setBits);
    }

    async _updateControl2(clearMask, setBits) {
        // AF and TF are written 1 (unchanged) unless explicitly cleared
        // through clearMask (AND semantics: write 0 clears, 1 keeps).
        const reg = await this._readReg(_REG_CONTROL_2);
        await this._writeReg(_REG_CONTROL_2, ((reg & ~clearMask) | setBits | (_C2_FLAGS & ~clearMask)) & 0xFF);
    }

    async _updateTimerMode(clearMask, setBits) {
        const reg = await this._readReg(_REG_TIMER_MODE);
        await this._writeReg(_REG_TIMER_MODE, ((reg & ~clearMask) | setBits) & 0x1F);
    }

    // -- Oscillator / clock control ---------------------------------------

    /**
     * @returns {Promise<boolean>} OS flag (bit 7 of Seconds) — the time may be
     *   invalid; cleared by `setDatetime()` or `clearOscillatorStop()`.
     */
    async oscillatorStopped() {
        return ((await this._readReg(_REG_SECONDS)) & _SECONDS_OS) !== 0;
    }

    /**
     * Clear OS, keeping the current seconds value. If `oscillatorStopped()`
     * is still true afterwards the oscillator is not running.
     * @returns {Promise<void>}
     */
    async clearOscillatorStop() {
        const sec = await this._readReg(_REG_SECONDS);
        await this._writeReg(_REG_SECONDS, sec & 0x7F);
    }

    /**
     * Freeze the clock (STOP=1); the divider chain is held in reset.
     * @returns {Promise<void>}
     */
    async stopClock() {
        await this._updateControl1(0, _C1_STOP);
    }

    /**
     * Release the clock (STOP=0).
     * @returns {Promise<void>}
     */
    async startClock() {
        await this._updateControl1(_C1_STOP, 0);
    }

    // -- Alarm ------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0B-0x0F).
     * @returns {Promise<{second: ?number, minute: ?number, hour: ?number, day: ?number, weekday: ?number}>}
     *   Each field is `null` when disabled (AEN_x=1).
     */
    async getAlarm() {
        const raw = await this._readRegs(_REG_SECOND_ALARM, 5);
        return {
            second:  raw[0] & 0x80 ? null : _bcdToInt(raw[0] & 0x7F),
            minute:  raw[1] & 0x80 ? null : _bcdToInt(raw[1] & 0x7F),
            hour:    raw[2] & 0x80 ? null : _bcdToInt(raw[2] & 0x3F),
            day:     raw[3] & 0x80 ? null : _bcdToInt(raw[3] & 0x3F),
            weekday: raw[4] & 0x80 ? null : raw[4] & 0x07,
        };
    }

    /**
     * Write the alarm registers (0x0B-0x0F) and clear AF. A field left
     * `null`/undefined is disabled; the alarm fires when every enabled field
     * matches.
     * @param {{second?: ?number, minute?: ?number, hour?: ?number, day?: ?number, weekday?: ?number}} [alarm={}]
     *   `weekday` is 0=Sunday..6=Saturday.
     * @returns {Promise<void>}
     */
    async setAlarm({ second = null, minute = null, hour = null, day = null, weekday = null } = {}) {
        await this._conn.writeReg(_REG_SECOND_ALARM, Buffer.from([second == null ? 0x80 : _intToBcd(second) & 0x7F,
            minute == null ? 0x80 : _intToBcd(minute) & 0x7F,
            hour == null ? 0x80 : _intToBcd(hour) & 0x3F,
            day == null ? 0x80 : _intToBcd(day) & 0x3F,
            weekday == null ? 0x80 : weekday & 0x07,]));
        await this._updateControl2(_C2_AF, 0);
    }

    // -- Countdown timer --------------------------------------------------

    /**
     * Configure and start the countdown timer. Disables the timer first so
     * `Timer_value` can be changed safely. Does not set TIE — call
     * `enableInterrupt(SOURCE_TIMER)` to route it to INT.
     * @param {number} value - Countdown value, 1-255.
     * @param {'4096hz'|'64hz'|'1hz'|'1_60hz'} sourceClock
     * @param {boolean} [pulsed=false] - Pulsed (true) or level (false, INT follows TF) interrupt.
     * @returns {Promise<void>}
     */
    async configureTimer(value, sourceClock, pulsed = false) {
        await this._updateTimerMode(_TM_TE, 0);
        await this._writeReg(_REG_TIMER_VALUE, value);
        await this._updateTimerMode(_TM_TCF_MASK | _TM_TI_TP,
            (_SOURCE_CLOCKS[sourceClock] << 3) | (pulsed ? _TM_TI_TP : 0) | _TM_TE);
    }

    /**
     * Stop the timer (TE=0, TIE=0) and select the 1/60 Hz clock for power saving.
     * @returns {Promise<void>}
     */
    async disableTimer() {
        await this._updateTimerMode(_TM_TE | _TM_TIE | _TM_TCF_MASK, _TM_TCF_MASK);
    }

    /**
     * Read the live countdown value; reads until two consecutive reads agree.
     * @returns {Promise<number>} Current counter value, 0-255 (not the loaded one).
     */
    async readTimer() {
        let prev = await this._readReg(_REG_TIMER_VALUE);
        for (;;) {
            const cur = await this._readReg(_REG_TIMER_VALUE);
            if (cur === prev) return cur;
            prev = cur;
        }
    }

    /**
     * Select the minute / half-minute interrupt (requires offset mode `normal`).
     * @param {'off'|'minute'|'half_minute'} mode
     * @returns {Promise<void>}
     */
    async setMinuteInterrupt(mode) {
        const bits = mode === 'minute' ? _C2_MI : mode === 'half_minute' ? _C2_HMI : 0;
        await this._updateControl2(_C2_MI | _C2_HMI, bits);
    }

    // -- CLKOUT -----------------------------------------------------------

    /**
     * Drive CLKOUT.
     * @param {number} frequencyHz - One of 32768, 16384, 8192, 4096, 2048, 1024, 1.
     * @returns {Promise<void>}
     */
    async setClockOutput(frequencyHz) {
        const cof = frequencyHz in _CLKOUT_COF ? _CLKOUT_COF[frequencyHz] : 7;
        await this._updateControl2(_C2_COF, cof);
    }

    /**
     * Disable CLKOUT (COF=111, pin low) to save power.
     * @returns {Promise<void>}
     */
    async disableClockOutput() {
        await this._updateControl2(_C2_COF, _C2_COF);
    }

    // -- Offset -----------------------------------------------------------

    /**
     * Read the clock-offset calibration register.
     * @returns {Promise<{offset: number, mode: 'normal'|'course'}>}
     *   `offset` is -64..+63 LSB (4.34 ppm/LSB normal, 4.069 ppm/LSB course).
     */
    async getOffset() {
        const raw = await this._readReg(_REG_OFFSET);
        let offset = raw & 0x7F;
        if (offset & 0x40) offset -= 128;
        return { offset, mode: raw & 0x80 ? 'course' : 'normal' };
    }

    /**
     * Write the clock-offset calibration register.
     * @param {number} offset - Two's-complement correction, -64 to 63 LSB.
     * @param {'normal'|'course'} [mode='normal'] - Correction every two hours / every four minutes.
     * @returns {Promise<void>}
     */
    async setOffset(offset, mode = 'normal') {
        await this._writeReg(_REG_OFFSET, (mode === 'course' ? 0x80 : 0) | (offset & 0x7F));
    }

    /**
     * Select the oscillator load capacitance (CAP_SEL).
     * @param {number} picofarads - 7 or 12.5; match the crystal's specified load.
     * @returns {Promise<void>}
     */
    async setOscillatorLoad(picofarads) {
        if (picofarads !== 7 && picofarads !== 12.5) throw new RangeError('picofarads must be 7 or 12.5');
        await this._updateControl1(_C1_CAP_SEL, picofarads === 12.5 ? _C1_CAP_SEL : 0);
    }

    // -- RAM / reset ------------------------------------------------------

    /**
     * @returns {Promise<number>} The free general-purpose RAM byte, 0-255.
     */
    async readRam() {
        return this._readReg(_REG_RAM);
    }

    /**
     * Write the free general-purpose RAM byte.
     * @param {number} value - 0-255.
     * @returns {Promise<void>}
     */
    async writeRam(value) {
        await this._writeReg(_REG_RAM, value);
    }

    /**
     * Send the software-reset sequence (0x58 to Control_1). Resets ALL
     * registers including the time (2000-01-01 00:00:00, OS=1) — run it
     * before `setDatetime()`, never as a routine.
     * @returns {Promise<void>}
     */
    async softwareReset() {
        await this._writeReg(_REG_CONTROL_1, 0x58);
    }

    // -- Interrupt API ----------------------------------------------------

    /**
     * Read Control_2, clear the set AF/TF flags (enable bits and COF
     * preserved), and return the pre-clear status mask — `SOURCE_ALARM`
     * and/or `SOURCE_TIMER` (timer, minute and half-minute all report as
     * `SOURCE_TIMER`).
     * @returns {Promise<number>}
     */
    async pollInterrupt() {
        const ctrl2 = await this._readReg(_REG_CONTROL_2);
        let status = 0;
        if (ctrl2 & _C2_AF) status |= PCF85063AFull.SOURCE_ALARM;
        if (ctrl2 & _C2_TF) status |= PCF85063AFull.SOURCE_TIMER;
        if (status) {
            // Write 0 only to the flags seen set, 1 to the others, so a flag
            // that sets between the read and this write is not lost.
            await this._writeReg(_REG_CONTROL_2, (ctrl2 & ~_C2_FLAGS) | (_C2_FLAGS & ~ctrl2));
        }
        return status;
    }

    /**
     * Enable one or more interrupt sources (AIE, TIE, MI, HMI, CIE).
     * @param {number} source - Bitwise OR of `PCF85063AFull.SOURCE_*`.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        await this._setInterruptEnables(source, true);
    }

    /**
     * Disable one or more interrupt sources.
     * @param {number} source - Bitwise OR of `PCF85063AFull.SOURCE_*`.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._setInterruptEnables(source, false);
    }

    async _setInterruptEnables(source, enable) {
        const S = PCF85063AFull;
        const bits2 = (source & S.SOURCE_ALARM ? _C2_AIE : 0)
            | (source & S.SOURCE_MINUTE ? _C2_MI : 0)
            | (source & S.SOURCE_HALF_MINUTE ? _C2_HMI : 0);
        if (bits2) await this._updateControl2(enable ? 0 : bits2, enable ? bits2 : 0);
        if (source & S.SOURCE_TIMER) await this._updateTimerMode(enable ? 0 : _TM_TIE, enable ? _TM_TIE : 0);
        if (source & S.SOURCE_CORRECTION) await this._updateControl1(enable ? 0 : _C1_CIE, enable ? _C1_CIE : 0);
    }

    /**
     * Subscribe to interrupts. Uses `connection.intPin.onEdge()` when an
     * INT-line InputPin is wired; falls back to a 5 ms polling loop
     * otherwise.
     * @param {function(number): void} callback - Called with the status mask (SOURCE_ALARM / SOURCE_TIMER).
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        this._callback = callback;
        const dispatch = async () => {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        };
        if (this._conn.intPin) {
            this._edgeHandler = dispatch;
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(dispatch, 5);
        }
    }

    /**
     * Unsubscribe from interrupts.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

/** Alarm matched (AF). */
PCF85063AFull.SOURCE_ALARM = 0x01;
/** Countdown timer expired (TF). */
PCF85063AFull.SOURCE_TIMER = 0x02;
/** Minute interrupt (enable only; reported as SOURCE_TIMER). */
PCF85063AFull.SOURCE_MINUTE = 0x04;
/** Half-minute interrupt (enable only; reported as SOURCE_TIMER). */
PCF85063AFull.SOURCE_HALF_MINUTE = 0x08;
/** Offset-correction pulse (enable only, no flag). */
PCF85063AFull.SOURCE_CORRECTION = 0x10;

/** Fixed 7-bit I²C address. */
PCF85063AMinimal.I2C_ADDRESS = 0x51;

module.exports = { PCF85063AMinimal, PCF85063AFull };
