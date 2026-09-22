'use strict';

const _REG_SECONDS          = 0x00;
const _REG_MINUTES          = 0x01;
const _REG_HOURS            = 0x02;
const _REG_DAY              = 0x03;
const _REG_DATE             = 0x04;
const _REG_MONTH_CENTURY    = 0x05;
const _REG_YEAR              = 0x06;
const _REG_ALARM1_SECONDS   = 0x07;
const _REG_ALARM1_MINUTES   = 0x08;
const _REG_ALARM1_HOURS     = 0x09;
const _REG_ALARM1_DAY_DATE  = 0x0A;
const _REG_ALARM2_MINUTES   = 0x0B;
const _REG_ALARM2_HOURS     = 0x0C;
const _REG_ALARM2_DAY_DATE  = 0x0D;
const _REG_CONTROL          = 0x0E;
const _REG_CONTROL_STATUS   = 0x0F;
const _REG_AGING_OFFSET     = 0x10;
const _REG_TEMP_MSB         = 0x11;
const _REG_TEMP_LSB         = 0x12;

const _CTRL_EOSC   = 0x80;
const _CTRL_BBSQW  = 0x40;
const _CTRL_CONV   = 0x20;
const _CTRL_RS2    = 0x10;
const _CTRL_RS1    = 0x08;
const _CTRL_INTCN  = 0x04;
const _CTRL_A2IE   = 0x02;
const _CTRL_A1IE   = 0x01;

const _STATUS_OSF     = 0x80;
const _STATUS_EN32KHZ = 0x08;
const _STATUS_BSY     = 0x04;
const _STATUS_A2F     = 0x02;
const _STATUS_A1F     = 0x01;

const _RATE_CODES = { 1: 0x00, 1024: 0x08, 4096: 0x10, 8192: 0x18 }; // pre-shifted into RS2:RS1 (bits 4:3)

function _bcdToInt(b) {
    return ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
}

function _intToBcd(v) {
    return (Math.floor(v / 10) << 4) | (v % 10);
}

function _delayMs(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * DS3231 extremely accurate I²C-integrated RTC/TCXO/crystal (Analog
 * Devices / Maxim Integrated) — minimal interface.
 *
 * Reads/writes the calendar clock and the free on-chip temperature reading,
 * with no configuration beyond the connection. Fixed I²C address 0x68.
 *
 * This driver always operates the HOURS registers in 24-hour mode; the
 * chip's native 12-hour/AM-PM encoding is never written or exposed.
 * `weekday` follows ISO 8601 (1=Monday .. 7=Sunday) — the chip itself only
 * requires the DAY register to be a sequential, user-defined 1-7 counter.
 *
 * Time/date register contents are undefined the first time VCC/VBAT is
 * ever applied with no prior battery. Use `oscillatorStopped()` (Full) to
 * detect this rather than trusting `getDatetime()` before the first
 * `setDatetime()` call.
 */
class DS3231Minimal {
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

    /**
     * Confirm the device answers on the bus. The DS3231 has no
     * WHO_AM_I/identity register, so this is a plain presence read; it
     * makes no register writes.
     * @returns {Promise<void>}
     */
    async init() {
        await this._readReg(_REG_CONTROL);
    }

    /**
     * Read the calendar clock.
     * @returns {Promise<{year: number, month: number, day: number, weekday: number, hour: number, minute: number, second: number}>}
     *   `year` is 2000-2099, `hour` is always 0-23, `weekday` is 1-7 (ISO 8601, see class doc).
     */
    async getDatetime() {
        const raw = await this._readRegs(_REG_SECONDS, 7);
        const second  = _bcdToInt(raw[0] & 0x7F);
        const minute  = _bcdToInt(raw[1] & 0x7F);
        const hour    = _bcdToInt(raw[2] & 0x3F); // bit 6 (12/24) assumed 0; bits 5:0 = 24-hour BCD
        const weekday = raw[3] & 0x07;
        const day     = _bcdToInt(raw[4] & 0x3F);
        const month   = _bcdToInt(raw[5] & 0x1F);
        const year    = 2000 + _bcdToInt(raw[6]);
        return { year, month, day, weekday, hour, minute, second };
    }

    /**
     * Set the calendar clock. Writes all seven clock/calendar registers,
     * forces 24-hour mode, and clears OSF (the time is now known-good).
     * @param {number} year   - 2000-2099.
     * @param {number} month  - 1-12.
     * @param {number} day    - 1-31.
     * @param {number} weekday - 1-7 (ISO 8601: 1=Monday..7=Sunday).
     * @param {number} hour   - 0-23.
     * @param {number} minute - 0-59.
     * @param {number} second - 0-59.
     * @returns {Promise<void>}
     */
    async setDatetime(year, month, day, weekday, hour, minute, second) {
        const buf = Buffer.from([
            _REG_SECONDS,
            _intToBcd(second),
            _intToBcd(minute),
            _intToBcd(hour), // bit 6 = 0 -> 24-hour mode
            weekday & 0x07,
            _intToBcd(day),
            _intToBcd(month), // bit 7 (century) left at 0
            _intToBcd(year - 2000),
        ]);
        await this._conn.write(buf);
        const status = await this._readReg(_REG_CONTROL_STATUS);
        await this._writeReg(_REG_CONTROL_STATUS, status & ~_STATUS_OSF);
    }

    /**
     * Read the last completed temperature conversion. No wait: the chip
     * converts autonomously every 64 s and on power-up, so the value may
     * be up to 64 s stale. Use `forceTemperatureConversion()` (Full) for
     * a fresh reading.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        const raw = await this._readRegs(_REG_TEMP_MSB, 2);
        const msbSigned = raw[0] > 127 ? raw[0] - 256 : raw[0];
        return msbSigned + (raw[1] >> 6) * 0.25;
    }
}

/**
 * DS3231 full interface — extends DS3231Minimal with alarms, square-wave
 * output, 32kHz output, oscillator control, forced temperature conversion,
 * aging trim, and the Level-2 selectable-source interrupt API.
 *
 * `INT/SQW` is one physical pin multiplexed by the CONTROL register's
 * INTCN bit — enabling alarm interrupts (`enableInterrupt`) and enabling
 * the square wave (`enableSquareWave`) are mutually exclusive; whichever
 * call happens last wins. `pollInterrupt()` still reports alarm matches
 * correctly regardless of INTCN, since A1F/A2F latch independently of the
 * pin's mode.
 */
class DS3231Full extends DS3231Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection at address 0x68.
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    // -- Alarms ---------------------------------------------------------

    /**
     * Read Alarm 1's configured match target and granularity.
     * @returns {Promise<{second: number, minute: number, hour: number, dayOrDate: number, isDayOfWeek: boolean, matchMode: number}>}
     */
    async getAlarm1() {
        const raw = await this._readRegs(_REG_ALARM1_SECONDS, 4);
        const a1m1 = (raw[0] >> 7) & 1;
        const a1m2 = (raw[1] >> 7) & 1;
        const a1m3 = (raw[2] >> 7) & 1;
        const a1m4 = (raw[3] >> 7) & 1;
        const isDayOfWeek = ((raw[3] >> 6) & 1) === 1;
        return {
            second: _bcdToInt(raw[0] & 0x7F),
            minute: _bcdToInt(raw[1] & 0x7F),
            hour: _bcdToInt(raw[2] & 0x3F),
            dayOrDate: _bcdToInt(raw[3] & 0x3F),
            isDayOfWeek,
            matchMode: (a1m4 << 3) | (a1m3 << 2) | (a1m2 << 1) | a1m1,
        };
    }

    /**
     * Configure and arm Alarm 1's match target and granularity (does not
     * enable the interrupt — call `enableInterrupt(DS3231Full.SOURCE_ALARM1)` too).
     *
     * `matchMode` is one of the `DS3231Full.ALARM1_*` constants, each a
     * direct bit-packing of the A1M4:A1M1 mask nibble (bit 3 = A1M4 .. bit
     * 0 = A1M1) from the datasheet's Alarm Mask Bits table. Every mode
     * except `ALARM1_MATCH_DATE_OR_DAY_HOURS_MINUTES_SECONDS` ignores
     * `dayOrDate`/`isDayOfWeek`; that one mode uses `isDayOfWeek` to pick
     * between a date-of-month match (`false`) and a day-of-week match
     * (`true`), covering both DY/DT rows of the datasheet table with one
     * mask code.
     *
     * @param {number} second
     * @param {number} minute
     * @param {number} hour - 0-23.
     * @param {number} dayOrDate - Day-of-week (1-7) or day-of-month (1-31), per `isDayOfWeek`.
     * @param {boolean} isDayOfWeek
     * @param {number} matchMode - One of `DS3231Full.ALARM1_*`.
     * @returns {Promise<void>}
     */
    async setAlarm1(second, minute, hour, dayOrDate, isDayOfWeek, matchMode) {
        const a1m1 = matchMode & 0x01;
        const a1m2 = (matchMode >> 1) & 0x01;
        const a1m3 = (matchMode >> 2) & 0x01;
        const a1m4 = (matchMode >> 3) & 0x01;
        const buf = Buffer.from([
            _REG_ALARM1_SECONDS,
            (a1m1 << 7) | _intToBcd(second),
            (a1m2 << 7) | _intToBcd(minute),
            (a1m3 << 7) | _intToBcd(hour),
            (a1m4 << 7) | (isDayOfWeek ? 0x40 : 0x00) | _intToBcd(dayOrDate),
        ]);
        await this._conn.write(buf);
    }

    /**
     * Read Alarm 2's configured match target and granularity.
     * @returns {Promise<{minute: number, hour: number, dayOrDate: number, isDayOfWeek: boolean, matchMode: number}>}
     */
    async getAlarm2() {
        const raw = await this._readRegs(_REG_ALARM2_MINUTES, 3);
        const a2m2 = (raw[0] >> 7) & 1;
        const a2m3 = (raw[1] >> 7) & 1;
        const a2m4 = (raw[2] >> 7) & 1;
        const isDayOfWeek = ((raw[2] >> 6) & 1) === 1;
        return {
            minute: _bcdToInt(raw[0] & 0x7F),
            hour: _bcdToInt(raw[1] & 0x3F),
            dayOrDate: _bcdToInt(raw[2] & 0x3F),
            isDayOfWeek,
            matchMode: (a2m4 << 2) | (a2m3 << 1) | a2m2,
        };
    }

    /**
     * Configure and arm Alarm 2's match target and granularity (does not
     * enable the interrupt — call `enableInterrupt(DS3231Full.SOURCE_ALARM2)` too).
     *
     * `matchMode` is one of the `DS3231Full.ALARM2_*` constants (A2M4:A2M2
     * mask nibble, bit 2 = A2M4 .. bit 0 = A2M2); see `setAlarm1`'s doc for
     * how `isDayOfWeek` disambiguates the finest-granularity mode.
     *
     * @param {number} minute
     * @param {number} hour - 0-23.
     * @param {number} dayOrDate - Day-of-week (1-7) or day-of-month (1-31), per `isDayOfWeek`.
     * @param {boolean} isDayOfWeek
     * @param {number} matchMode - One of `DS3231Full.ALARM2_*`.
     * @returns {Promise<void>}
     */
    async setAlarm2(minute, hour, dayOrDate, isDayOfWeek, matchMode) {
        const a2m2 = matchMode & 0x01;
        const a2m3 = (matchMode >> 1) & 0x01;
        const a2m4 = (matchMode >> 2) & 0x01;
        const buf = Buffer.from([
            _REG_ALARM2_MINUTES,
            (a2m2 << 7) | _intToBcd(minute),
            (a2m3 << 7) | _intToBcd(hour),
            (a2m4 << 7) | (isDayOfWeek ? 0x40 : 0x00) | _intToBcd(dayOrDate),
        ]);
        await this._conn.write(buf);
    }

    // -- Square wave / 32kHz --------------------------------------------

    /**
     * Drive INT/SQW as a square wave. Mutually exclusive with alarm
     * interrupts (shares INTCN — see class doc).
     * @param {number} [rateHz=8192] - One of 1, 1024, 4096, 8192.
     * @param {boolean} [batteryBacked=false] - Keep the output driven on VBAT (BBSQW).
     * @returns {Promise<void>}
     */
    async enableSquareWave(rateHz = 8192, batteryBacked = false) {
        const rsBits = _RATE_CODES[rateHz];
        if (rsBits === undefined) {
            throw new Error('rateHz must be one of 1, 1024, 4096, 8192');
        }
        let ctrl = await this._readReg(_REG_CONTROL);
        ctrl &= ~(_CTRL_INTCN | _CTRL_RS2 | _CTRL_RS1 | _CTRL_BBSQW);
        ctrl |= rsBits;
        if (batteryBacked) ctrl |= _CTRL_BBSQW;
        await this._writeReg(_REG_CONTROL, ctrl);
    }

    /**
     * Return INT/SQW to interrupt mode (INTCN=1), stopping the square wave.
     * @returns {Promise<void>}
     */
    async disableSquareWave() {
        const ctrl = await this._readReg(_REG_CONTROL);
        await this._writeReg(_REG_CONTROL, ctrl | _CTRL_INTCN);
    }

    /**
     * @returns {Promise<boolean>} Whether the separate 32kHz output pin is enabled.
     */
    async is32khzEnabled() {
        return ((await this._readReg(_REG_CONTROL_STATUS)) & _STATUS_EN32KHZ) !== 0;
    }

    /** @returns {Promise<void>} */
    async enable32khzOutput() {
        const status = await this._readReg(_REG_CONTROL_STATUS);
        await this._writeReg(_REG_CONTROL_STATUS, status | _STATUS_EN32KHZ);
    }

    /** @returns {Promise<void>} */
    async disable32khzOutput() {
        const status = await this._readReg(_REG_CONTROL_STATUS);
        await this._writeReg(_REG_CONTROL_STATUS, status & ~_STATUS_EN32KHZ);
    }

    // -- Oscillator -------------------------------------------------------

    /**
     * @returns {Promise<boolean>} True if the oscillator has stopped since OSF was last cleared —
     *   timekeeping data may be invalid.
     */
    async oscillatorStopped() {
        return ((await this._readReg(_REG_CONTROL_STATUS)) & _STATUS_OSF) !== 0;
    }

    /** Clear OSF only, preserving EN32kHz. @returns {Promise<void>} */
    async clearOscillatorStopped() {
        const status = await this._readReg(_REG_CONTROL_STATUS);
        await this._writeReg(_REG_CONTROL_STATUS, status & ~_STATUS_OSF);
    }

    /** Keep the oscillator running on VBAT (power-on default). @returns {Promise<void>} */
    async enableBatteryOscillator() {
        const ctrl = await this._readReg(_REG_CONTROL);
        await this._writeReg(_REG_CONTROL, ctrl & ~_CTRL_EOSC);
    }

    /** Stop the oscillator when switched to VBAT, saving battery current. @returns {Promise<void>} */
    async disableBatteryOscillator() {
        const ctrl = await this._readReg(_REG_CONTROL);
        await this._writeReg(_REG_CONTROL, ctrl | _CTRL_EOSC);
    }

    /**
     * Force an immediate temperature conversion and wait for it to finish
     * (max 200 ms) rather than returning the last autonomous reading.
     * @returns {Promise<number>} Temperature in °C.
     */
    async forceTemperatureConversion() {
        const ctrl = await this._readReg(_REG_CONTROL);
        await this._writeReg(_REG_CONTROL, ctrl | _CTRL_CONV);
        const deadline = Date.now() + 200;
        // eslint-disable-next-line no-constant-condition
        while (true) {
            const status = await this._readReg(_REG_CONTROL_STATUS);
            if ((status & _STATUS_BSY) === 0) break;
            if (Date.now() >= deadline) break;
            _delayMs(1);
        }
        return this.readTemperature();
    }

    // -- Aging offset -----------------------------------------------------

    /**
     * @returns {Promise<number>} Raw signed 8-bit oscillator trim code (-128 to 127).
     *   Not a physically-scaled unit — see the spec's Implementation Notes.
     */
    async getAgingOffset() {
        const raw = await this._readReg(_REG_AGING_OFFSET);
        return raw > 127 ? raw - 256 : raw;
    }

    /**
     * @param {number} offset - Raw signed 8-bit trim code, -128 to 127.
     * @returns {Promise<void>}
     */
    async setAgingOffset(offset) {
        await this._writeReg(_REG_AGING_OFFSET, offset & 0xFF);
    }

    // -- Interrupt API (Level 2: SOURCE_ALARM1 / SOURCE_ALARM2) ----------

    /**
     * Read CONTROL_STATUS, clear A1F/A2F (leaving OSF/EN32kHz/BSY
     * untouched), and return the pre-clear byte. Mask against
     * `DS3231Full.SOURCE_ALARM1`/`DS3231Full.SOURCE_ALARM2` to test each
     * source. Works regardless of INTCN — A1F/A2F latch on a match
     * whether or not the pin is wired as an interrupt.
     * @returns {Promise<number>}
     */
    async pollInterrupt() {
        const status = await this._readReg(_REG_CONTROL_STATUS);
        const clearMask = status & (_STATUS_A1F | _STATUS_A2F);
        if (clearMask) {
            await this._writeReg(_REG_CONTROL_STATUS, status & ~clearMask);
        }
        return status & (DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2);
    }

    /**
     * Enable the given alarm's interrupt-enable bit and set INTCN=1 so
     * INT/SQW carries alarm interrupts instead of the square wave.
     * @param {number} source - `DS3231Full.SOURCE_ALARM1` or `SOURCE_ALARM2`.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        let ctrl = await this._readReg(_REG_CONTROL);
        ctrl |= _CTRL_INTCN;
        if (source & DS3231Full.SOURCE_ALARM1) ctrl |= _CTRL_A1IE;
        if (source & DS3231Full.SOURCE_ALARM2) ctrl |= _CTRL_A2IE;
        await this._writeReg(_REG_CONTROL, ctrl);
    }

    /**
     * Clear the given alarm's interrupt-enable bit.
     * @param {number} source - `DS3231Full.SOURCE_ALARM1` or `SOURCE_ALARM2`.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        let ctrl = await this._readReg(_REG_CONTROL);
        if (source & DS3231Full.SOURCE_ALARM1) ctrl &= ~_CTRL_A1IE;
        if (source & DS3231Full.SOURCE_ALARM2) ctrl &= ~_CTRL_A2IE;
        await this._writeReg(_REG_CONTROL, ctrl);
    }

    /**
     * Subscribe to alarm interrupts. Uses `connection.intPin.onEdge()`
     * when an INT-line InputPin is wired; falls back to a 5 ms polling
     * loop otherwise.
     * @param {function(number): void} callback - Called with the status byte (mask against SOURCE_*).
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
     * Unsubscribe from alarm interrupts.
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

/** Alarm 1 fired (bit 0 of CONTROL_STATUS). */
DS3231Full.SOURCE_ALARM1 = 0x01;
/** Alarm 2 fired (bit 1 of CONTROL_STATUS). */
DS3231Full.SOURCE_ALARM2 = 0x02;

/** Alarm 1 match-mode constants — see `setAlarm1` doc. */
DS3231Full.ALARM1_EVERY_SECOND = 0x0F;
DS3231Full.ALARM1_MATCH_SECONDS = 0x0E;
DS3231Full.ALARM1_MATCH_MINUTES_SECONDS = 0x0C;
DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS = 0x08;
DS3231Full.ALARM1_MATCH_DATE_OR_DAY_HOURS_MINUTES_SECONDS = 0x00;

/** Alarm 2 match-mode constants — see `setAlarm2` doc. */
DS3231Full.ALARM2_EVERY_MINUTE = 0x07;
DS3231Full.ALARM2_MATCH_MINUTES = 0x06;
DS3231Full.ALARM2_MATCH_HOURS_MINUTES = 0x04;
DS3231Full.ALARM2_MATCH_DATE_OR_DAY_HOURS_MINUTES = 0x00;

module.exports = { DS3231Minimal, DS3231Full };
