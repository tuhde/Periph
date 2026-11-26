'use strict';

const _REG_STATUS          = 0x00;
const _REG_MEAS_MODE       = 0x01;
const _REG_ALG_RESULT_DATA = 0x02;
const _REG_RAW_DATA        = 0x03;
const _REG_ENV_DATA        = 0x05;
const _REG_NTC             = 0x06;
const _REG_THRESHOLDS      = 0x10;
const _REG_BASELINE        = 0x11;
const _REG_HW_ID           = 0x20;
const _REG_HW_VERSION      = 0x21;
const _REG_FW_BOOT_VERSION = 0x23;
const _REG_FW_APP_VERSION  = 0x24;
const _REG_ERROR_ID        = 0xE0;
const _REG_APP_START       = 0xF4;
const _REG_SW_RESET        = 0xFF;

const _HW_ID       = 0x81;
const _MODE_1      = 0x10;
const _INT_DATARDY = 0x08;
const _INT_THRESH  = 0x04;
const _RESET_KEY   = [0x11, 0xE5, 0x72, 0x8A];
// Sample interval in ms per drive mode (0 = idle)
const _INTERVAL_MS = [0, 1000, 10000, 60000, 250];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CCS811 indoor air quality gas sensor (ScioSense / ams) — minimal interface.
 *
 * One call returns the equivalent CO₂ (eCO₂) and total VOC (TVOC) values of the chip's internal
 * algorithm. `init()` verifies HW_ID = 0x81, writes APP_START if the chip is still in boot mode
 * and selects drive mode 1 (MEAS_MODE = 0x10: one sample per second, interrupts off). No
 * environment data is written (the chip assumes 50 %RH, 25 °C).
 *
 * The chip stretches the I²C clock; tie nWAKE low. New sensors need a 48 h burn-in and values
 * settle about 20 min after each power-up. I²C address 0x5A (ADDR low) or 0x5B (ADDR high).
 * The constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class CCS811Minimal {
    constructor(connection) {
        this._conn = connection;
        this._measMode = _MODE_1;
    }

    /**
     * Verify the chip, start the application firmware and select drive mode 1.
     * @returns {Promise<void>}
     * @throws {Error} If HW_ID is not 0x81, no valid application is loaded, or the chip does not
     *   enter application mode.
     */
    async init() {
        const id = (await this._conn.readReg(_REG_HW_ID, 1))[0];
        if (id !== _HW_ID) throw new Error(`Unexpected HW_ID 0x${id.toString(16)}, expected 0x81`);
        await this._startApp();
        this._measMode = _MODE_1;
        await this._conn.writeReg(_REG_MEAS_MODE, [this._measMode]);
    }

    async _startApp() {
        let status = (await this._conn.readReg(_REG_STATUS, 1))[0];
        if (!(status & CCS811Minimal.STATUS_APP_VALID)) throw new Error('CCS811 has no valid application firmware');
        if (status & CCS811Minimal.STATUS_FW_MODE) return;
        await this._conn.writeReg(_REG_APP_START, []);
        await _sleep(2);
        status = (await this._conn.readReg(_REG_STATUS, 1))[0];
        if (!(status & CCS811Minimal.STATUS_FW_MODE)) throw new Error('CCS811 did not enter application mode');
    }

    async _waitDataReady() {
        const mode = (this._measMode >> 4) & 0x07;
        const limit = Math.max(1500, (_INTERVAL_MS[mode] || 0) * 1.5);
        const deadline = Date.now() + limit;
        for (;;) {
            const status = (await this._conn.readReg(_REG_STATUS, 1))[0];
            if (status & CCS811Minimal.STATUS_DATA_READY) return;
            if (Date.now() > deadline) throw new Error('CCS811 data ready timeout');
            await _sleep(20);
        }
    }

    async _readResult() {
        const b = await this._conn.readReg(_REG_ALG_RESULT_DATA, 5);
        if (b[4] & CCS811Minimal.STATUS_ERROR) {
            const errorId = (await this._conn.readReg(_REG_ERROR_ID, 1))[0];
            throw new Error(`CCS811 error, ERROR_ID 0x${errorId.toString(16).padStart(2, '0')}`);
        }
        return { eco2_ppm: (b[0] << 8) | b[1], tvoc_ppb: (b[2] << 8) | b[3] };
    }

    /**
     * Wait for the next sample and return it.
     * @returns {Promise<{eco2_ppm: number, tvoc_ppb: number}>} eCO₂ in ppm (400–8192) and TVOC in ppb (0–1187).
     * @throws {Error} On timeout or when the chip reports an error (the message carries ERROR_ID).
     */
    async read() {
        await this._waitDataReady();
        return this._readResult();
    }
}

/** STATUS.FW_MODE: application mode. */
CCS811Minimal.STATUS_FW_MODE = 0x80;
/** STATUS.APP_VALID: valid application firmware loaded. */
CCS811Minimal.STATUS_APP_VALID = 0x10;
/** STATUS.DATA_READY: new sample in ALG_RESULT_DATA. */
CCS811Minimal.STATUS_DATA_READY = 0x08;
/** STATUS.ERROR: see ERROR_ID. */
CCS811Minimal.STATUS_ERROR = 0x01;

/**
 * CCS811 full interface — extends CCS811Minimal with drive modes, raw sensor data, environment
 * compensation, the NTC input, thresholds, baseline save/restore, versions, soft reset and the
 * nINT interrupt (active low, open-drain, released when ALG_RESULT_DATA is read).
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class CCS811Full extends CCS811Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    static _intBits(source) {
        let bits = 0;
        if (source & CCS811Full.SOURCE_DATA_READY) bits |= _INT_DATARDY;
        if (source & CCS811Full.SOURCE_THRESHOLD) bits |= _INT_DATARDY | _INT_THRESH;
        return bits;
    }

    async _writeMeasMode() {
        await this._conn.writeReg(_REG_MEAS_MODE, [this._measMode]);
    }

    async _read16(reg) {
        const b = await this._conn.readReg(reg, 2);
        return (b[0] << 8) | b[1];
    }

    /**
     * Wait for the next sample and return the equivalent CO₂.
     * @returns {Promise<number>} eCO₂ in ppm.
     */
    async readEco2() {
        return (await this.read()).eco2_ppm;
    }

    /**
     * Wait for the next sample and return the total VOC.
     * @returns {Promise<number>} TVOC in ppb.
     */
    async readTvoc() {
        return (await this.read()).tvoc_ppb;
    }

    /**
     * Read RAW_DATA.
     * @returns {Promise<[number, number]>} [current_ua (0–63 µA), voltage_v (ADC × 1.65 / 1023)].
     */
    async readRaw() {
        const raw = await this._read16(_REG_RAW_DATA);
        return [raw >> 10, (raw & 0x3FF) * 1.65 / 1023];
    }

    /**
     * Whether a new sample is waiting (STATUS.DATA_READY).
     * @returns {Promise<boolean>}
     */
    async dataReady() {
        return ((await this.status()) & CCS811Minimal.STATUS_DATA_READY) !== 0;
    }

    /**
     * Select the drive mode, keeping the interrupt bits. 0 idle, 1 every second, 2 every 10 s,
     * 3 every 60 s, 4 every 250 ms (raw data only). When moving to a slower mode, idle (mode 0)
     * first; the first sample at the new rate may be stale.
     * @param {number} mode - Drive mode 0–4.
     * @returns {Promise<void>}
     * @throws {RangeError} If mode is not 0–4.
     */
    async setMode(mode) {
        if (!Number.isInteger(mode) || mode < 0 || mode > 4) throw new RangeError('mode must be 0..4');
        this._measMode = (this._measMode & 0x0C) | (mode << 4);
        await this._writeMeasMode();
    }

    /**
     * Write ambient humidity and temperature (ENV_DATA) for the compensation.
     * @param {number} humidityRh - Relative humidity in % (0–100).
     * @param {number} temperatureC - Temperature in °C (-25 to 102).
     * @returns {Promise<void>}
     * @throws {RangeError} If a value is out of range.
     */
    async setEnvironment(humidityRh, temperatureC) {
        if (!(humidityRh >= 0 && humidityRh <= 100)) throw new RangeError('humidityRh must be 0..100');
        if (!(temperatureC >= -25 && temperatureC <= 102)) throw new RangeError('temperatureC must be -25..102');
        const h = Math.round(humidityRh * 512);
        const t = Math.round((temperatureC + 25) * 512);
        await this._conn.writeReg(_REG_ENV_DATA, [h >> 8, h & 0xFF, t >> 8, t & 0xFF]);
    }

    /**
     * Read the NTC circuit voltages.
     * @returns {Promise<[number, number]>} [v_ref_mv, v_ntc_mv] in mV.
     */
    async readNtc() {
        const b = await this._conn.readReg(_REG_NTC, 4);
        return [(b[0] << 8) | b[1], (b[2] << 8) | b[3]];
    }

    /**
     * NTC resistance V_NTC × R_REF / V_REF.
     * @param {number} rRefOhm - Reference resistor in ohms.
     * @returns {Promise<number>} NTC resistance in ohms.
     * @throws {Error} If V_REF reads 0 (no NTC circuit).
     */
    async ntcResistance(rRefOhm) {
        const [vRef, vNtc] = await this.readNtc();
        if (vRef === 0) throw new Error('CCS811 NTC reference voltage is 0');
        return vNtc * rRefOhm / vRef;
    }

    /**
     * Set the eCO₂ boundaries for the threshold interrupt.
     * @param {number} [lowMediumPpm=1500] - Low-to-medium boundary in ppm.
     * @param {number} [mediumHighPpm=2500] - Medium-to-high boundary in ppm.
     * @param {number} [hysteresisPpm=50] - Hysteresis in ppm (0–255).
     * @returns {Promise<void>}
     * @throws {RangeError} If a value is out of range or low ≥ high.
     */
    async setThresholds(lowMediumPpm = 1500, mediumHighPpm = 2500, hysteresisPpm = 50) {
        if (!(lowMediumPpm >= 0 && lowMediumPpm < mediumHighPpm && mediumHighPpm <= 0xFFFF)) {
            throw new RangeError('thresholds must satisfy 0 <= lowMedium < mediumHigh <= 65535');
        }
        if (!(hysteresisPpm >= 0 && hysteresisPpm <= 0xFF)) throw new RangeError('hysteresisPpm must be 0..255');
        await this._conn.writeReg(_REG_THRESHOLDS, [lowMediumPpm >> 8, lowMediumPpm & 0xFF,
            mediumHighPpm >> 8, mediumHighPpm & 0xFF, hysteresisPpm]);
    }

    /**
     * Read the encoded algorithm baseline (save it to non-volatile storage).
     * @returns {Promise<number>} 16-bit baseline.
     */
    async getBaseline() {
        return this._read16(_REG_BASELINE);
    }

    /**
     * Restore a saved baseline (only after about 20 min of operation).
     * @param {number} baseline - Value from getBaseline().
     * @returns {Promise<void>}
     * @throws {RangeError} If baseline is not 0–65535.
     */
    async setBaseline(baseline) {
        if (!(Number.isInteger(baseline) && baseline >= 0 && baseline <= 0xFFFF)) {
            throw new RangeError('baseline must be 0..65535');
        }
        await this._conn.writeReg(_REG_BASELINE, [baseline >> 8, baseline & 0xFF]);
    }

    /**
     * Read STATUS.
     * @returns {Promise<number>} STATUS byte (STATUS_* bits).
     */
    async status() {
        return (await this._conn.readReg(_REG_STATUS, 1))[0];
    }

    /**
     * Read ERROR_ID (bit 0 write-invalid … bit 5 heater supply).
     * @returns {Promise<number>}
     */
    async error() {
        return (await this._conn.readReg(_REG_ERROR_ID, 1))[0];
    }

    /**
     * Read the hardware ID and version.
     * @returns {Promise<[number, number]>} [hw_id (0x81), hw_version (0x1X)].
     */
    async hardwareVersion() {
        const id = (await this._conn.readReg(_REG_HW_ID, 1))[0];
        const version = (await this._conn.readReg(_REG_HW_VERSION, 1))[0];
        return [id, version];
    }

    async _version(reg) {
        const b = await this._conn.readReg(reg, 2);
        return [b[0] >> 4, b[0] & 0x0F, b[1]];
    }

    /**
     * Read the bootloader and application firmware versions.
     * @returns {Promise<{boot: number[], app: number[]}>} Each [major, minor, trivial].
     */
    async firmwareVersion() {
        return { boot: await this._version(_REG_FW_BOOT_VERSION), app: await this._version(_REG_FW_APP_VERSION) };
    }

    /**
     * SW_RESET into boot mode, then start the application and mode 1 again (init()). Drive mode,
     * interrupt bits, thresholds and environment data return to their defaults.
     * @returns {Promise<void>}
     */
    async softReset() {
        try {
            await this._conn.writeReg(_REG_SW_RESET, _RESET_KEY);
        } catch (e) {
            /* the chip may not acknowledge while it resets */
        }
        await _sleep(20);
        await this.init();
    }

    /**
     * Enable nINT for a source, keeping the drive mode.
     * @param {number} source - SOURCE_DATA_READY and/or SOURCE_THRESHOLD (which also sets INT_DATARDY).
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        this._measMode |= CCS811Full._intBits(source);
        await this._writeMeasMode();
    }

    /**
     * Disable nINT for a source, keeping the drive mode.
     * @param {number} source - SOURCE_DATA_READY and/or SOURCE_THRESHOLD.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        this._measMode &= ~CCS811Full._intBits(source) & 0xFF;
        await this._writeMeasMode();
    }

    /**
     * Read STATUS. Does not release nINT (reading ALG_RESULT_DATA does).
     * @returns {Promise<number>} STATUS byte; STATUS_DATA_READY marks a new sample.
     */
    async pollInterrupt() {
        return this.status();
    }

    /**
     * Subscribe to the interrupt. Uses `intPin` (or the connection's) on its falling edge, else a
     * 20 ms poll of STATUS.DATA_READY. The callback receives the STATUS byte and should read the
     * sample to release nINT.
     * @param {function(number): void} callback - Called with STATUS when DATA_READY is set.
     * @param {object} [intPin=null] - Optional InputPin wired to nINT.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = ipin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (ipin) {
            await ipin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 20);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe and stop delivery. */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if ((status & CCS811Minimal.STATUS_DATA_READY) && this._callback) this._callback(status);
        } catch (e) {
            /* a failed poll is retried on the next edge / tick */
        } finally {
            this._polling = false;
        }
    }
}

/** Interrupt source: new sample (MEAS_MODE.INT_DATARDY). */
CCS811Full.SOURCE_DATA_READY = 0x01;
/** Interrupt source: eCO₂ threshold crossing (INT_DATARDY + INT_THRESH). */
CCS811Full.SOURCE_THRESHOLD = 0x02;

module.exports = { CCS811Minimal, CCS811Full };
