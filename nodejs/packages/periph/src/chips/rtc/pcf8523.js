'use strict';

const _REG_CONTROL_1       = 0x00;
const _REG_CONTROL_2       = 0x01;
const _REG_CONTROL_3       = 0x02;
const _REG_SECONDS         = 0x03;
const _REG_MINUTE_ALARM    = 0x0A;
const _REG_OFFSET          = 0x0E;
const _REG_TMR_CLKOUT_CTRL = 0x0F;
const _REG_TMR_A_FREQ_CTRL = 0x10;
const _REG_TMR_A_REG       = 0x11;
const _REG_TMR_B_FREQ_CTRL = 0x12;
const _REG_TMR_B_REG       = 0x13;

// CONTROL_1 (0x00) bits.
const _C1_T     = 0x40;
const _C1_STOP  = 0x20;
const _C1_SR    = 0x10;
const _C1_12_24 = 0x08;
const _C1_SIE   = 0x04;
const _C1_AIE   = 0x02;

// CONTROL_2 (0x01) bits.
const _C2_WTAF      = 0x80;
const _C2_CTAF      = 0x40;
const _C2_CTBF      = 0x20;
const _C2_SF        = 0x10;
const _C2_AF        = 0x08;
const _C2_WTAIE     = 0x04;
const _C2_CTAIE     = 0x02;
const _C2_CTBIE     = 0x01;
const _C2_CLEARABLE = 0x78; // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
const _C2_ENABLES   = 0x07;

// CONTROL_3 (0x02) bits.
const _C3_PM_MASK = 0xE0;
const _C3_BSF     = 0x08;
const _C3_BLF     = 0x04;
const _C3_BSIE    = 0x02;
const _C3_BLIE    = 0x01;

const _SECONDS_OS = 0x80;

// TMR_CLKOUT_CTRL (0x0F) fields.
const _TMR_TAM           = 0x80;
const _TMR_TBM           = 0x40;
const _TMR_COF_MASK      = 0x38;
const _TMR_TAC_MASK      = 0x06;
const _TMR_TAC_COUNTDOWN = 0x02;
const _TMR_TAC_WATCHDOG  = 0x04;
const _TMR_TBC           = 0x01;

const _SOURCE_CLOCKS = { '4096hz': 0x00, '64hz': 0x01, '1hz': 0x02, '1_60hz': 0x03, '1_3600hz': 0x07 };

// TBW[2:0] -> low-pulse width in ms (datasheet Table 36; not uniformly spaced).
const _TBW_WIDTHS_MS = [46.875, 62.5, 78.125, 93.75, 125, 156.25, 187.5, 218.75];

const _CLKOUT_COF = { 32768: 0, 16384: 1, 8192: 2, 4096: 3, 1024: 4, 32: 5, 1: 6 };

const _PM_MODES = {
    standard: [0x00, 0x04], // [low detection on, off]
    direct:   [0x01, 0x05],
    disabled: [0x02, 0x07],
};

function _bcdToInt(b) {
    return ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
}

function _intToBcd(v) {
    return (Math.floor(v / 10) << 4) | (v % 10);
}

/**
 * PCF8523 low-power I²C real-time clock and calendar (NXP) — minimal
 * interface.
 *
 * Reads/writes the battery-backed calendar clock with no configuration
 * beyond the connection. `init()` enables battery switch-over in standard
 * mode with battery-low detection (PM[2:0]=000) — a deliberate override of
 * the chip's single-supply POR default. Fixed I²C address 0x68.
 *
 * This driver always operates the HOURS registers in 24-hour mode.
 * `weekday` follows the datasheet's suggested assignment, 0=Sunday ..
 * 6=Saturday — the WEEKDAYS register has no hardware-enforced meaning.
 */
class PCF8523Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection at address 0x68.
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg & 0xFF, value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._conn.writeRead(Buffer.from([reg & 0xFF]), 1))[0];
    }

    async _readRegs(reg, n) {
        return this._conn.writeRead(Buffer.from([reg & 0xFF]), n);
    }

    async _readControl1() {
        // T must always be written 0 and SR always reads 0; mask both so a
        // read-modify-write never triggers a reset.
        return (await this._readReg(_REG_CONTROL_1)) & ~(_C1_T | _C1_SR) & 0xFF;
    }

    /**
     * Confirm the device answers on the bus (plain CONTROL_1 read — the
     * PCF8523 has no identity register) and enable battery switch-over
     * standard mode with battery-low detection (PM[2:0]=000).
     * @returns {Promise<void>}
     */
    async init() {
        await this._readReg(_REG_CONTROL_1);
        await this._writeReg(_REG_CONTROL_3, 0x00);
    }

    /**
     * Read the calendar clock.
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
     * @param {number} year    - 2000-2099.
     * @param {number} month   - 1-12.
     * @param {number} day     - 1-31.
     * @param {number} weekday - 0=Sunday..6=Saturday.
     * @param {number} hour    - 0-23.
     * @param {number} minute  - 0-59.
     * @param {number} second  - 0-59.
     * @returns {Promise<void>}
     */
    async setDatetime(year, month, day, weekday, hour, minute, second) {
        const ctrl1 = (await this._readControl1()) & ~_C1_12_24 & 0xFF;
        await this._writeReg(_REG_CONTROL_1, ctrl1 | _C1_STOP);
        await this._conn.write(Buffer.from([
            _REG_SECONDS,
            _intToBcd(second) & 0x7F, // OS = 0
            _intToBcd(minute),
            _intToBcd(hour) & 0x3F,
            _intToBcd(day),
            weekday & 0x07,
            _intToBcd(month),
            _intToBcd(year - 2000),
        ]));
        await this._writeReg(_REG_CONTROL_1, ctrl1 & ~_C1_STOP & 0xFF);
    }
}

/**
 * PCF8523 full interface — extends Minimal with the alarm, Timer A
 * (countdown or watchdog), Timer B, programmable CLKOUT, offset
 * calibration, battery backup control/status, oscillator-stop detection,
 * software reset, and the Level-3 interrupt API.
 *
 * INT1 is shared with CLKOUT: interrupts only reach INT1 once CLKOUT is
 * disabled (`disableClockOutput()`); the driver never does that
 * implicitly. Timer B additionally drives the dedicated INT2 pin.
 */
class PCF8523Full extends PCF8523Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _updateTmrClkout(clearMask, setBits) {
        const reg = await this._readReg(_REG_TMR_CLKOUT_CTRL);
        await this._writeReg(_REG_TMR_CLKOUT_CTRL, (reg & ~clearMask) | setBits);
    }

    async _writeControl2(enables) {
        // Re-supply the enable bits; write 1 to every clearable flag so none
        // is cleared by accident (AND semantics). WTAF is read-only.
        await this._writeReg(_REG_CONTROL_2, _C2_CLEARABLE | (enables & _C2_ENABLES));
    }

    async _writeControl3(value) {
        // Write 1 to BSF so it is left unchanged; BLF is read-only.
        await this._writeReg(_REG_CONTROL_3, (value & (_C3_PM_MASK | _C3_BSIE | _C3_BLIE)) | _C3_BSF);
    }

    // -- Alarm ------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0A-0x0D).
     * @returns {Promise<{minute: ?number, hour: ?number, day: ?number, weekday: ?number}>}
     *   Each field is `null` when disabled (AEN_x=1).
     */
    async getAlarm() {
        const raw = await this._readRegs(_REG_MINUTE_ALARM, 4);
        return {
            minute:  raw[0] & 0x80 ? null : _bcdToInt(raw[0] & 0x7F),
            hour:    raw[1] & 0x80 ? null : _bcdToInt(raw[1] & 0x3F),
            day:     raw[2] & 0x80 ? null : _bcdToInt(raw[2] & 0x3F),
            weekday: raw[3] & 0x80 ? null : raw[3] & 0x07,
        };
    }

    /**
     * Write the alarm registers (0x0A-0x0D). A field left `null`/undefined
     * is disabled; the alarm fires when every enabled field matches.
     * @param {{minute?: ?number, hour?: ?number, day?: ?number, weekday?: ?number}} [alarm={}]
     *   `weekday` is 0=Sunday..6=Saturday.
     * @returns {Promise<void>}
     */
    async setAlarm({ minute = null, hour = null, day = null, weekday = null } = {}) {
        await this._conn.write(Buffer.from([
            _REG_MINUTE_ALARM,
            minute == null ? 0x80 : _intToBcd(minute) & 0x7F,
            hour == null ? 0x80 : _intToBcd(hour) & 0x3F,
            day == null ? 0x80 : _intToBcd(day) & 0x3F,
            weekday == null ? 0x80 : weekday & 0x07,
        ]));
    }

    // -- Timers -----------------------------------------------------------

    /**
     * Configure and start Timer A.
     * @param {'countdown'|'watchdog'} mode
     * @param {number} value - Countdown value, 0-255.
     * @param {'4096hz'|'64hz'|'1hz'|'1_60hz'|'1_3600hz'} sourceClock
     * @param {boolean} [pulsed=false] - Pulsed (true) or permanently-active interrupt.
     * @returns {Promise<void>}
     */
    async configureTimerA(mode, value, sourceClock, pulsed = false) {
        const tac = mode === 'watchdog' ? _TMR_TAC_WATCHDOG : _TMR_TAC_COUNTDOWN;
        await this._writeReg(_REG_TMR_A_FREQ_CTRL, _SOURCE_CLOCKS[sourceClock]);
        await this._writeReg(_REG_TMR_A_REG, value);
        await this._updateTmrClkout(_TMR_TAM | _TMR_TAC_MASK, (pulsed ? _TMR_TAM : 0) | tac);
    }

    /**
     * Stop Timer A (TAC=00).
     * @returns {Promise<void>}
     */
    async disableTimerA() {
        await this._updateTmrClkout(_TMR_TAC_MASK, 0);
    }

    /**
     * @returns {Promise<number>} Timer A's live countdown value, 0-255 (not the loaded one).
     */
    async readTimerA() {
        return this._readReg(_REG_TMR_A_REG);
    }

    /**
     * Configure and start Timer B (also drives INT2).
     * @param {number} value - Countdown value, 0-255.
     * @param {'4096hz'|'64hz'|'1hz'|'1_60hz'|'1_3600hz'} sourceClock
     * @param {number} [pulseWidthMs=46.875] - Pulsed-mode low-pulse width in ms; the
     *   nearest of the eight hardware widths (46.875-218.75 ms) is used.
     * @param {boolean} [pulsed=false] - Pulsed (true) or permanently-active interrupt.
     * @returns {Promise<void>}
     */
    async configureTimerB(value, sourceClock, pulseWidthMs = 46.875, pulsed = false) {
        let tbw = 0;
        _TBW_WIDTHS_MS.forEach((w, i) => {
            if (Math.abs(w - pulseWidthMs) < Math.abs(_TBW_WIDTHS_MS[tbw] - pulseWidthMs)) tbw = i;
        });
        await this._writeReg(_REG_TMR_B_FREQ_CTRL, (tbw << 4) | _SOURCE_CLOCKS[sourceClock]);
        await this._writeReg(_REG_TMR_B_REG, value);
        await this._updateTmrClkout(_TMR_TBM | _TMR_TBC, (pulsed ? _TMR_TBM : 0) | _TMR_TBC);
    }

    /**
     * Stop Timer B (TBC=0).
     * @returns {Promise<void>}
     */
    async disableTimerB() {
        await this._updateTmrClkout(_TMR_TBC, 0);
    }

    /**
     * @returns {Promise<number>} Timer B's live countdown value, 0-255 (not the loaded one).
     */
    async readTimerB() {
        return this._readReg(_REG_TMR_B_REG);
    }

    // -- CLKOUT -----------------------------------------------------------

    /**
     * Drive CLKOUT on the shared INT1/CLKOUT pin.
     * @param {number} frequencyHz - One of 32768, 16384, 8192, 4096, 1024, 32, 1.
     * @returns {Promise<void>}
     */
    async setClockOutput(frequencyHz) {
        const cof = frequencyHz in _CLKOUT_COF ? _CLKOUT_COF[frequencyHz] : 7;
        await this._updateTmrClkout(_TMR_COF_MASK, cof << 3);
    }

    /**
     * Disable CLKOUT (COF=111), freeing INT1 for interrupts.
     * @returns {Promise<void>}
     */
    async disableClockOutput() {
        await this._updateTmrClkout(_TMR_COF_MASK, _TMR_COF_MASK);
    }

    // -- Offset -----------------------------------------------------------

    /**
     * Read the clock-offset calibration register.
     * @returns {Promise<{offset: number, mode: 'every_two_hours'|'every_minute'}>}
     *   `offset` is -64..+63 LSB (4.34 ppm/LSB every two hours, 4.069 ppm/LSB every minute).
     */
    async getOffset() {
        const raw = await this._readReg(_REG_OFFSET);
        let offset = raw & 0x7F;
        if (offset & 0x40) offset -= 128;
        return { offset, mode: raw & 0x80 ? 'every_minute' : 'every_two_hours' };
    }

    /**
     * Write the clock-offset calibration register.
     * @param {number} offset - Two's-complement correction, -64 to 63 LSB.
     * @param {'every_two_hours'|'every_minute'} [mode='every_two_hours']
     * @returns {Promise<void>}
     */
    async setOffset(offset, mode = 'every_two_hours') {
        await this._writeReg(_REG_OFFSET, (mode === 'every_minute' ? 0x80 : 0) | (offset & 0x7F));
    }

    // -- Battery backup ---------------------------------------------------

    /**
     * Select the battery switch-over mode (PM[2:0]).
     * @param {'standard'|'direct'|'disabled'} mode - `disabled` means VDD only (tie VBAT to VDD).
     * @param {boolean} [lowDetection=true] - Enable battery-low detection.
     * @returns {Promise<void>}
     */
    async configureBatteryBackup(mode, lowDetection = true) {
        const pm = _PM_MODES[mode][lowDetection ? 0 : 1];
        const ctrl3 = await this._readReg(_REG_CONTROL_3);
        await this._writeControl3((ctrl3 & ~_C3_PM_MASK) | (pm << 5));
    }

    /**
     * @returns {Promise<boolean>} BSF — a switch-over to VBAT occurred since it was last cleared.
     */
    async isBatterySwitchedOver() {
        return ((await this._readReg(_REG_CONTROL_3)) & _C3_BSF) !== 0;
    }

    /**
     * Clear BSF only, leaving PM and the enable bits unchanged.
     * @returns {Promise<void>}
     */
    async clearBatterySwitchover() {
        const ctrl3 = await this._readReg(_REG_CONTROL_3);
        await this._writeReg(_REG_CONTROL_3, ctrl3 & (_C3_PM_MASK | _C3_BSIE | _C3_BLIE));
    }

    /**
     * @returns {Promise<boolean>} BLF (read-only) — VBAT is below the detection threshold.
     */
    async isBatteryLow() {
        return ((await this._readReg(_REG_CONTROL_3)) & _C3_BLF) !== 0;
    }

    /**
     * @returns {Promise<boolean>} OS flag (bit 7 of SECONDS) — the time may be
     *   invalid; cleared by `setDatetime()`.
     */
    async oscillatorStopped() {
        return ((await this._readReg(_REG_SECONDS)) & _SECONDS_OS) !== 0;
    }

    /**
     * Send the software-reset sequence (0x58 to CONTROL_1). Resets all
     * control/configuration registers to POR defaults — including PM=111
     * (battery backup disabled) — but keeps the time/date/alarm/timer values.
     * @returns {Promise<void>}
     */
    async softwareReset() {
        await this._writeReg(_REG_CONTROL_1, 0x58);
    }

    // -- Interrupt API (Level 3) ------------------------------------------

    /**
     * Read CONTROL_2/CONTROL_3, clear the set CTAF/CTBF/SF/AF/BSF flags
     * (WTAF/BLF are read-only; enable bits untouched), and return the
     * pre-clear status mask — test with the `PCF8523Full.SOURCE_*` constants.
     * @returns {Promise<number>}
     */
    async pollInterrupt() {
        const raw = await this._readRegs(_REG_CONTROL_2, 2);
        const ctrl2 = raw[0];
        const ctrl3 = raw[1];
        let status = 0;
        if (ctrl2 & _C2_SF) status |= PCF8523Full.SOURCE_SECOND;
        if (ctrl2 & (_C2_CTAF | _C2_WTAF)) status |= PCF8523Full.SOURCE_TIMER_A;
        if (ctrl2 & _C2_CTBF) status |= PCF8523Full.SOURCE_TIMER_B;
        if (ctrl2 & _C2_AF) status |= PCF8523Full.SOURCE_ALARM;
        if (ctrl3 & _C3_BSF) status |= PCF8523Full.SOURCE_BATTERY_SWITCH;
        if (ctrl3 & _C3_BLF) status |= PCF8523Full.SOURCE_BATTERY_LOW;
        // Write 0 only to the flags seen set, 1 to the rest, so a flag that
        // sets between the read and this write is not lost.
        if (ctrl2 & _C2_CLEARABLE) {
            await this._writeReg(_REG_CONTROL_2, (_C2_CLEARABLE & ~ctrl2) | (ctrl2 & _C2_ENABLES));
        }
        if (ctrl3 & _C3_BSF) {
            await this._writeReg(_REG_CONTROL_3, ctrl3 & (_C3_PM_MASK | _C3_BSIE | _C3_BLIE));
        }
        return status;
    }

    /**
     * Enable one or more interrupt sources. `SOURCE_TIMER_A` sets WTAIE or
     * CTAIE depending on Timer A's configured mode — call
     * `configureTimerA()` first.
     * @param {number} source - Bitwise OR of `PCF8523Full.SOURCE_*`.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        await this._setInterruptEnables(source, true);
    }

    /**
     * Disable one or more interrupt sources.
     * @param {number} source - Bitwise OR of `PCF8523Full.SOURCE_*`.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._setInterruptEnables(source, false);
    }

    async _setInterruptEnables(source, enable) {
        const S = PCF8523Full;
        if (source & (S.SOURCE_SECOND | S.SOURCE_ALARM)) {
            const bits = (source & S.SOURCE_SECOND ? _C1_SIE : 0) | (source & S.SOURCE_ALARM ? _C1_AIE : 0);
            const ctrl1 = await this._readControl1();
            await this._writeReg(_REG_CONTROL_1, enable ? ctrl1 | bits : ctrl1 & ~bits);
        }
        if (source & (S.SOURCE_TIMER_A | S.SOURCE_TIMER_B)) {
            let bits = 0;
            if (source & S.SOURCE_TIMER_A) {
                if (enable) {
                    const tac = (await this._readReg(_REG_TMR_CLKOUT_CTRL)) & _TMR_TAC_MASK;
                    bits |= tac === _TMR_TAC_WATCHDOG ? _C2_WTAIE : _C2_CTAIE;
                } else {
                    bits |= _C2_WTAIE | _C2_CTAIE;
                }
            }
            if (source & S.SOURCE_TIMER_B) bits |= _C2_CTBIE;
            const enables = (await this._readReg(_REG_CONTROL_2)) & _C2_ENABLES;
            await this._writeControl2(enable ? enables | bits : enables & ~bits);
        }
        if (source & (S.SOURCE_BATTERY_SWITCH | S.SOURCE_BATTERY_LOW)) {
            const bits = (source & S.SOURCE_BATTERY_SWITCH ? _C3_BSIE : 0) | (source & S.SOURCE_BATTERY_LOW ? _C3_BLIE : 0);
            const ctrl3 = await this._readReg(_REG_CONTROL_3);
            await this._writeControl3(enable ? ctrl3 | bits : ctrl3 & ~bits);
        }
    }

    /**
     * Subscribe to interrupts. Uses `connection.intPin.onEdge()` when an
     * INT-line InputPin is wired; falls back to a 5 ms polling loop
     * otherwise. Call `disableClockOutput()` first if INT1 still carries
     * CLKOUT.
     * @param {function(number): void} callback - Called with the status mask (test against SOURCE_*).
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

/** Second tick (SF). */
PCF8523Full.SOURCE_SECOND = 0x01;
/** Timer A timed out (CTAF or WTAF). */
PCF8523Full.SOURCE_TIMER_A = 0x02;
/** Timer B timed out (CTBF). */
PCF8523Full.SOURCE_TIMER_B = 0x04;
/** All enabled alarm fields matched (AF). */
PCF8523Full.SOURCE_ALARM = 0x08;
/** Battery switch-over occurred (BSF). */
PCF8523Full.SOURCE_BATTERY_SWITCH = 0x10;
/** Battery low (BLF). */
PCF8523Full.SOURCE_BATTERY_LOW = 0x20;

/** Fixed 7-bit I²C address. */
PCF8523Minimal.I2C_ADDRESS = 0x68;

module.exports = { PCF8523Minimal, PCF8523Full };
