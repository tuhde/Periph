'use strict';

/*
 * RRH46410 digital gas sensor module for indoor air quality (Renesas).
 *
 * The host sends a request `[command, params…, checksum]` in one I²C write and reads the
 * response `[status, params…, checksum]` in a separate read; `checksum = ~(sum of the other
 * bytes) & 0xFF`. The module stretches the I²C clock (address 0x38).
 */

const CMD_GET_PRODUCT_ID = 0x00;
const CMD_GET_TRACKING_NUMBER = 0x01;
const CMD_GET_OPERATION_MODE = 0x10;
const CMD_SET_OPERATION_MODE = 0x11;
const CMD_SET_HUMIDITY = 0x12;
const CMD_GET_MEASUREMENT_RESULTS = 0x18;
const CMD_CONFIG_GPIO = 0x20;
const CMD_GET_GPIO = 0x21;
const CMD_SET_GPIO = 0x22;
const CMD_CLEAR_GPIO = 0x23;
const CMD_RESET = 0x8F;

const MODE_SUSPEND = 0x00;
const MODE_IAQ_2ND_GEN = 0x01;
const MODE_ULP = 0x02;
const MODE_PBAQ = 0x05;
const MODE_CLEANING = 0x80;

const STATUS_NOT_COMPLETE = 0x10;
const PRODUCT_ID = 0x2310;

const _INIT_TIMEOUT_MS = 1000;
const _POLL_MS = 200;
const _CLEAN_TIMEOUT_MS = 90000;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _checksum(bytes) {
    let sum = 0;
    for (const b of bytes) sum += b;
    return (~sum) & 0xFF;
}

function _period(mode) {
    if (mode === MODE_IAQ_2ND_GEN) return 3.0;
    if (mode === MODE_ULP) return 90.0;
    if (mode === MODE_PBAQ) return 5.0;
    return 0.0;
}

function _warmup(mode) {
    if (mode === MODE_IAQ_2ND_GEN) return 100;
    if (mode === MODE_ULP) return 10;
    if (mode === MODE_PBAQ) return 60;
    return 0;
}

/**
 * RRH46410 protocol or status error.
 * @property {number|null} status - Status byte of the failing response, or null for a
 *   checksum / timeout / identity error.
 */
class RRH46410Error extends Error {
    constructor(message, status = null) {
        super(message);
        this.name = 'RRH46410Error';
        this.status = status;
    }
}

/**
 * RRH46410 indoor air quality sensor (Renesas) — minimal interface.
 *
 * One call returns the IAQ index, TVOC, ethanol equivalent, estimated CO₂ and relative IAQ of
 * the IAQ 2nd Gen algorithm. `init()` retries Get Product ID for up to 1 s, verifies 0x2310
 * and selects the IAQ 2nd Gen mode (one sample every 3 s; the first 100 samples, about
 * 5 minutes, are the warm-up). The module's default humidity (50 %RH) is kept; GPIOs are
 * unused. The constructor does not touch the bus.
 *
 * @param {import('../../connection/base').Connection} connection - Plain I²C connection bound to 0x38.
 */
class RRH46410Minimal {
    constructor(connection) {
        this._conn = connection;
        this._mode = MODE_SUSPEND;
        this._lastStatus = 0;
        this._lastCounter = null;
        this._samples = 0;
        this._lastResult = null;
        this._busy = Promise.resolve();
    }

    /**
     * Wait for the module, verify the product ID and start IAQ 2nd Gen mode.
     * @returns {Promise<void>}
     * @throws {RRH46410Error} If the module does not answer within 1 s or the product ID is not 0x2310.
     */
    async init() {
        await this._waitProductId();
        await this._setMode(MODE_IAQ_2ND_GEN);
    }

    /**
     * Wait for the next sample and return it.
     * @returns {Promise<{iaq: number, tvoc_mg_m3: number, etoh_ppm: number, eco2_ppm: number,
     *   relative_iaq: number, sample: number, warmed_up: boolean}>} IAQ index (UBA level
     *   1.0–5.0), TVOC in mg/m³, ethanol equivalent in ppm, estimated CO₂ in ppm, relative IAQ,
     *   the sample counter (0–255) and whether the warm-up is over.
     * @throws {RRH46410Error} If no new sample arrives within the sample period plus 2 s, or the
     *   module reports an error.
     */
    async read() {
        if (!(await this._waitSample(_period(this._mode) + 2.0))) throw new RRH46410Error('RRH46410 sample timeout');
        return this._lastResult;
    }

    // --- protocol ----------------------------------------------------------

    async _transact(command, params = [], responseLen = 0, check = true) {
        const req = [command, ...params];
        req.push(_checksum(req));
        // Serialize request/response pairs (the interrupt poller shares the bus).
        const run = this._busy.then(async () => {
            await this._conn.write(Buffer.from(req));
            return Buffer.from(await this._conn.read(responseLen + 2));
        });
        this._busy = run.catch(() => {});
        const resp = await run;
        let sum = 0;
        for (const b of resp) sum += b;
        if ((sum & 0xFF) !== 0xFF) throw new RRH46410Error(`RRH46410 response checksum error (command 0x${command.toString(16)})`);
        const status = resp[0];
        this._lastStatus = status;
        if (check && status) throw new RRH46410Error(`RRH46410 status 0x${status.toString(16)} (command 0x${command.toString(16)})`, status);
        return { status, data: resp.subarray(1, 1 + responseLen) };
    }

    async _waitProductId() {
        const deadline = Date.now() + _INIT_TIMEOUT_MS;
        let data;
        for (;;) {
            try {
                data = (await this._transact(CMD_GET_PRODUCT_ID, [], 5)).data;
                break;
            } catch (e) {
                if (Date.now() > deadline) throw new RRH46410Error('RRH46410 not responding');
                await _sleep(50);
            }
        }
        const pid = (data[0] << 8) | data[1];
        if (pid !== PRODUCT_ID) throw new RRH46410Error(`Unexpected RRH46410 product ID 0x${pid.toString(16)}, expected 0x2310`);
        return data;
    }

    async _setMode(mode) {
        await this._transact(CMD_SET_OPERATION_MODE, [mode]);
        this._mode = mode;
        this._lastCounter = null;
        this._samples = 0;
    }

    /** Read the raw result; returns the parameter bytes or null before the first sample. */
    async _fetch() {
        const n = this._mode === MODE_PBAQ ? 5 : 9;
        const { status, data } = await this._transact(CMD_GET_MEASUREMENT_RESULTS, [], n, false);
        if (status === STATUS_NOT_COMPLETE) return null;
        if (status) throw new RRH46410Error(`RRH46410 status 0x${status.toString(16)} (command 0x18)`, status);
        return data;
    }

    _decode(d) {
        const counter = d[0];
        if (this._lastCounter === null) this._samples = counter;
        else this._samples += (counter - this._lastCounter) & 0xFF;
        this._lastCounter = counter;
        const warmed_up = this._samples >= _warmup(this._mode);
        if (this._mode === MODE_PBAQ) {
            return {
                tvoc_mg_m3: ((d[1] << 8) | d[2]) / 1000,
                etoh_ppm: ((d[3] << 8) | d[4]) / 1000,
                sample: counter,
                warmed_up,
            };
        }
        return {
            iaq: d[1] / 10,
            tvoc_mg_m3: ((d[2] << 8) | d[3]) / 100,
            etoh_ppm: ((d[4] << 8) | d[5]) / 100,
            eco2_ppm: (d[6] << 8) | d[7],
            relative_iaq: d[8] * 10,
            sample: counter,
            warmed_up,
        };
    }

    /** Poll until the sample counter changes; stores the decoded result. Timeout in s. */
    async _waitSample(timeout) {
        const deadline = Date.now() + timeout * 1000;
        const pin = this._conn.intPin ?? null;
        const last = this._lastCounter;
        for (;;) {
            if (!pin || pin.isHigh()) {
                const data = await this._fetch();
                if (data && (last === null || data[0] !== last)) {
                    this._lastResult = this._decode(data);
                    return true;
                }
            }
            if (Date.now() > deadline) return false;
            await _sleep(this._pollMs ?? _POLL_MS);
        }
    }
}

/**
 * RRH46410 — full interface. Extends RRH46410Minimal with identity, operation modes (IAQ 2nd
 * Gen, ULP, PBAQ, cleaning), humidity compensation, GPIOs, status, reset, raw commands and the
 * INT interrupt. INT is active high, push-pull: high when a new result is available, low after
 * Get Measurement Results or Set Operation Mode.
 *
 * @param {import('../../connection/base').Connection} connection - Plain I²C connection bound to 0x38.
 */
class RRH46410Full extends RRH46410Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /**
     * Read the product ID and firmware version.
     * @returns {Promise<{productId: number, firmware: number[]}>} Product ID 0x2310 and
     *   `[major, minor, patch]`.
     */
    async productId() {
        const d = (await this._transact(CMD_GET_PRODUCT_ID, [], 5)).data;
        return { productId: (d[0] << 8) | d[1], firmware: [d[2], d[3], d[4]] };
    }

    /**
     * Read the ASIC tracking number.
     * @returns {Promise<Buffer>} 6 bytes.
     */
    async trackingNumber() {
        return Buffer.from((await this._transact(CMD_GET_TRACKING_NUMBER, [], 6)).data);
    }

    /**
     * Read the current operation mode.
     * @returns {Promise<number>} One of the MODE_* constants (other values behave as SUSPEND).
     */
    async getMode() {
        const mode = (await this._transact(CMD_GET_OPERATION_MODE, [], 1)).data[0];
        this._mode = mode;
        return mode;
    }

    /**
     * Change the operation mode. Stops a running measurement, restarts the warm-up and sets INT
     * low. Use `cleanSensor` for the cleaning mode.
     * @param {number} mode - MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ.
     * @returns {Promise<void>}
     * @throws {RangeError} If mode is not one of these.
     */
    async setMode(mode) {
        if (![MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP, MODE_PBAQ].includes(mode)) {
            throw new RangeError('mode must be MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ');
        }
        await this._setMode(mode);
    }

    /**
     * Stop measuring (operation mode SUSPEND, about 0.7 µA).
     * @returns {Promise<void>}
     */
    async suspend() {
        await this._setMode(MODE_SUSPEND);
    }

    /**
     * Read and decode the latest result without waiting.
     * @returns {Promise<object|null>} The decoded result for the current mode (PBAQ has only
     *   `tvoc_mg_m3`, `etoh_ppm`, `sample` and `warmed_up`), or null before the first sample.
     */
    async readResults() {
        const data = await this._fetch();
        if (!data) return null;
        this._lastResult = this._decode(data);
        return this._lastResult;
    }

    /**
     * Wait until the sample counter changes (or INT goes high).
     * @param {number} timeout - Maximum wait in s.
     * @returns {Promise<boolean>} True when a new sample arrived.
     */
    async waitForSample(timeout) {
        return this._waitSample(timeout);
    }

    /**
     * Supply the ambient relative humidity for compensation; sent as round(percent / 100 × 255).
     * @param {number} percent - Relative humidity in % (0–100).
     * @returns {Promise<void>}
     * @throws {RangeError} If percent is not 0–100.
     */
    async setHumidity(percent) {
        if (!(percent >= 0 && percent <= 100)) throw new RangeError('percent must be 0..100');
        await this._transact(CMD_SET_HUMIDITY, [Math.round(percent / 100 * 255)]);
    }

    /**
     * Sample period of a mode.
     * @param {number|null} [mode=null] - Operation mode, or null for the current mode.
     * @returns {number} Seconds: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise.
     */
    samplePeriod(mode = null) {
        return _period(mode === null ? this._mode : mode);
    }

    /**
     * Number of warm-up samples of a mode.
     * @param {number|null} [mode=null] - Operation mode, or null for the current mode.
     * @returns {number} 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise.
     */
    warmupSamples(mode = null) {
        return _warmup(mode === null ? this._mode : mode);
    }

    /**
     * UBA rating text for an IAQ index.
     * @param {number} iaq - IAQ index (1.0–5.0).
     * @returns {string} "very good" (< 2.0), "good", "medium", "poor" or "bad" (≥ 5.0).
     */
    static airQualityLabel(iaq) {
        if (iaq < 2.0) return 'very good';
        if (iaq < 3.0) return 'good';
        if (iaq < 4.0) return 'medium';
        if (iaq < 5.0) return 'poor';
        return 'bad';
    }

    /** Instance alias of {@link RRH46410Full.airQualityLabel}. */
    airQualityLabel(iaq) {
        return RRH46410Full.airQualityLabel(iaq);
    }

    /**
     * Run the one-time 1-minute sensor cleaning and wait for it to finish (up to 90 s). Execute
     * it at most once in the sensor's lifetime and do not interrupt it.
     * @param {boolean} [confirm=false] - Must be true; guards against accidental use.
     * @returns {Promise<void>}
     * @throws {Error} If confirm is not true or the module does not return to SUSPEND.
     */
    async cleanSensor(confirm = false) {
        if (confirm !== true) throw new Error('cleanSensor runs once per sensor lifetime; pass confirm=true');
        await this._setMode(MODE_CLEANING);
        const deadline = Date.now() + _CLEAN_TIMEOUT_MS;
        while ((await this.getMode()) === MODE_CLEANING) {
            if (Date.now() > deadline) throw new RRH46410Error('RRH46410 cleaning did not finish');
            await _sleep(1000);
        }
        this._mode = MODE_SUSPEND;
    }

    static _mask(value) {
        if (!(value >= 0 && value <= 3)) throw new RangeError('GPIO mask must be 0..3');
        return value;
    }

    /**
     * Configure GPIO1/GPIO2 as outputs.
     * @param {number} directions - Bit 0 = GPIO1 output, bit 1 = GPIO2 output (0–3).
     * @returns {Promise<void>}
     */
    async configGpio(directions) {
        await this._transact(CMD_CONFIG_GPIO, [RRH46410Full._mask(directions)]);
    }

    /**
     * Read the GPIO levels.
     * @returns {Promise<number>} Bits 0–3 = GPIO1–GPIO4.
     */
    async getGpio() {
        return (await this._transact(CMD_GET_GPIO, [], 1)).data[0];
    }

    /**
     * Drive GPIO outputs high.
     * @param {number} mask - Bit 0 = GPIO1, bit 1 = GPIO2 (0–3).
     * @returns {Promise<void>}
     */
    async setGpio(mask) {
        await this._transact(CMD_SET_GPIO, [RRH46410Full._mask(mask)]);
    }

    /**
     * Drive GPIO outputs low.
     * @param {number} mask - Bit 0 = GPIO1, bit 1 = GPIO2 (0–3).
     * @returns {Promise<void>}
     */
    async clearGpio(mask) {
        await this._transact(CMD_CLEAR_GPIO, [RRH46410Full._mask(mask)]);
    }

    /**
     * Decode the status byte of the last response.
     * @returns {{raw: number, invalid_command: boolean, checksum_error: boolean,
     *   sensor_nack: boolean, not_complete: boolean, mode_error: number}} `mode_error` is the
     *   bits 3:0 code (0 OK, 0xA sensor damaged).
     */
    status() {
        const s = this._lastStatus;
        return {
            raw: s,
            invalid_command: !!(s & 0x80),
            checksum_error: !!(s & 0x40),
            sensor_nack: !!(s & 0x20),
            not_complete: !!(s & 0x10),
            mode_error: s & 0x0F,
        };
    }

    /**
     * Reset the module, wait 500 ms and verify the product ID again; it restarts in SUSPEND.
     * @returns {Promise<void>}
     */
    async reset() {
        try {
            await this._conn.write(Buffer.from([CMD_RESET, _checksum([CMD_RESET])]));
        } catch (e) {
            /* the module may not acknowledge while resetting */
        }
        await _sleep(500);
        await this._waitProductId();
        this._mode = MODE_SUSPEND;
        this._lastCounter = null;
        this._samples = 0;
    }

    /**
     * Send a raw request; checksums are added and verified, the status byte is available from
     * `status()`. Reset (0x8F) has no response.
     * @param {number} command - Command byte.
     * @param {number[]|Buffer} [params=[]] - Request parameter bytes.
     * @param {number} [responseLen=0] - Number of response parameter bytes.
     * @returns {Promise<Buffer>} The response parameters (empty for Reset).
     */
    async sendCommand(command, params = [], responseLen = 0) {
        const p = [...params];
        if (command === CMD_RESET) {
            const req = [command, ...p];
            await this._conn.write(Buffer.from([...req, _checksum(req)]));
            return Buffer.alloc(0);
        }
        return Buffer.from((await this._transact(command, p, responseLen, false)).data);
    }

    /**
     * Check for a new sample without consuming it.
     * @returns {Promise<boolean>} True if the INT pin is high (pin available), otherwise if the
     *   sample counter changed since the last decoded result.
     */
    async pollInterrupt() {
        const pin = this._conn.intPin ?? null;
        if (pin) return !!pin.isHigh();
        const data = await this._fetch();
        return data !== null && data[0] !== this._lastCounter;
    }

    /**
     * Call `callback(result)` for every new sample. Uses `intPin` (or the connection's `intPin`)
     * on its rising edge, otherwise a 200 ms poll comparing the sample counter. The driver reads
     * the result (which sets INT low) and passes the decoded object.
     * @param {function(object): void} callback - Called with the decoded result.
     * @param {object|null} [intPin=null] - Optional InputPin wired to INT.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        if (ipin) {
            this._intPinUsed = ipin;
            await ipin.onEdge(this._edgeHandler, 'rising');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, this._pollMs ?? _POLL_MS);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /**
     * Unsubscribe from the interrupt.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service() {
        if (this._polling || !this._callback) return;
        this._polling = true;
        try {
            const data = await this._fetch();
            if (data && data[0] !== this._lastCounter && this._callback) {
                this._lastResult = this._decode(data);
                this._callback(this._lastResult);
            }
        } catch (e) {
            /* a failed poll is retried on the next tick */
        } finally {
            this._polling = false;
        }
    }
}

for (const cls of [RRH46410Minimal, RRH46410Full]) {
    cls.MODE_SUSPEND = MODE_SUSPEND;
    cls.MODE_IAQ_2ND_GEN = MODE_IAQ_2ND_GEN;
    cls.MODE_ULP = MODE_ULP;
    cls.MODE_PBAQ = MODE_PBAQ;
    cls.MODE_CLEANING = MODE_CLEANING;
    cls.PRODUCT_ID = PRODUCT_ID;
}

module.exports = { RRH46410Minimal, RRH46410Full, RRH46410Error };
