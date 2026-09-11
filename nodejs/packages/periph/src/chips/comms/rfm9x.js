'use strict';

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver driver.
 *
 * All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * The driver is built around an internal `_RFM9xBase` that owns all
 * register-level logic. Four thin variant subclasses — `RFM95Minimal`,
 * `RFM96Minimal`, `RFM97Minimal`, `RFM98Minimal` — supply the
 * variant-specific frequency limits, maximum SF, and band flag via static
 * class fields.
 *
 * `_RFM9xBase` exposes only the Minimal-stage public API (`init`, `send`,
 * `receive`) plus underscore-prefixed private helpers. Full-stage
 * functionality (reset, configure, setFrequency, setTxPower, interrupt
 * receive, continuous receive, rssi/snr readouts, sleep/standby, version)
 * is implemented ONLY in `_RFM9xFullMixin`, a mixin function applied to a
 * `*Minimal` subclass to produce the corresponding `*Full` class:
 *
 *     class RFM95Full extends _RFM9xFullMixin(RFM95Minimal) {}
 *
 * This is a structural (not just naming-convention) separation: `_RFM9xBase`
 * literally does not define the Full-only methods, so a `*Minimal` instance
 * cannot reach them, matching the reference Python driver
 * (python/periph/chips/comms/rfm9x.py).
 */

// Register addresses (module-scoped constants, not class members — a class
// field accessed via `this.X` only resolves for *instance* fields; a
// `static X` field is only reachable via `ClassName.X` or
// `this.constructor.X`, never bare `this.X`).
const _REG_FIFO            = 0x00;
const _REG_OP_MODE         = 0x01;
const _REG_FRF_MSB         = 0x06;
const _REG_FRF_MID         = 0x07;
const _REG_FRF_LSB         = 0x08;
const _REG_PA_CONFIG       = 0x09;
const _REG_OCP             = 0x0B;
const _REG_LNA             = 0x0C;
const _REG_FIFO_ADDR_PTR   = 0x0D;
const _REG_FIFO_TX_BASE    = 0x0E;
const _REG_FIFO_RX_BASE    = 0x0F;
const _REG_FIFO_RX_CURRENT = 0x10;
const _REG_IRQ_FLAGS       = 0x12;
const _REG_RX_NB_BYTES     = 0x13;
const _REG_PKT_SNR         = 0x19;
const _REG_PKT_RSSI        = 0x1A;
const _REG_RSSI            = 0x1B;
const _REG_MODEM_CONFIG_1  = 0x1D;
const _REG_MODEM_CONFIG_2  = 0x1E;
const _REG_PREAMBLE_LSB    = 0x21;
const _REG_PAYLOAD_LENGTH  = 0x22;
const _REG_MODEM_CONFIG_3  = 0x26;
const _REG_DETECTION_OPT   = 0x31;
const _REG_DETECTION_THR   = 0x37;
const _REG_DIO_MAPPING_1   = 0x40;
const _REG_VERSION         = 0x42;
const _REG_PA_DAC          = 0x4D;

const _MODE_LONG_RANGE = 0x80;
const _MODE_SLEEP      = 0x00;
const _MODE_STANDBY    = 0x01;
const _MODE_TX         = 0x03;
const _MODE_RX_CONT    = 0x05;
const _MODE_RX_SINGLE  = 0x06;

const _IRQ_TX_DONE    = 0x08;
const _IRQ_RX_DONE    = 0x40;
const _IRQ_RX_TIMEOUT = 0x80;

const _PA_BOOST          = 0x80;
const _PA_DAC_HIGH_POWER = 0x87;
const _PA_DAC_DEFAULT    = 0x84;
const _OCP_240MA         = 0x3B;
const _OCP_DEFAULT       = 0x2B;

const _DIO0_RX_DONE = 0x00;
const _DIO0_TX_DONE = 0x40;

const _FXOSC = 32000000;
const _EXPECTED_VERSION = 0x12;

/**
 * Base class for RFM95/96/97/98W LoRa transceivers (LoRa mode only).
 *
 * Owns all register-level logic. Only the Minimal-stage public API
 * (`init`, `send`, `receive`) is exposed here; Full-stage functionality is
 * implemented as underscore-prefixed private helpers and re-exposed
 * publicly by `_RFM9xFullMixin`, so `*Minimal` instances never see it.
 *
 * Not exported — construct one of the `*Minimal` / `*Full` variant classes.
 */
class _RFM9xBase {
    /**
     * @param {object} connection - SPI connection (write, read, writeRead).
     * @param {number} frequencyHz - Carrier frequency in Hz; must lie in the variant's range.
     * @param {import('../../connection/output_pin').OutputPin|null} [resetPin=null] -
     *   Optional NRESET OutputPin. When omitted, `init()` waits 10 ms for POR instead.
     * @param {import('../../connection/input_pin').InputPin|null} [dio0Pin=null] -
     *   Optional DIO0 InputPin, used only by Full's `receive(timeoutMs, useInterrupt=true)`;
     *   Minimal ignores it.
     */
    constructor(connection, frequencyHz, resetPin = null, dio0Pin = null) {
        this._connection = connection;
        this._resetPin = resetPin;
        this._dio0Pin = dio0Pin;
        this.freqMinHz = this.constructor.FREQ_MIN_HZ;
        this.freqMaxHz = this.constructor.FREQ_MAX_HZ;
        this.maxSF = this.constructor.MAX_SF;
        this._lfBand = this.constructor.LF_BAND;
        if (frequencyHz < this.freqMinHz || frequencyHz > this.freqMaxHz) {
            throw new RangeError(`frequencyHz ${frequencyHz} out of range [${this.freqMinHz}, ${this.freqMaxHz}]`);
        }
        this._frequencyHz = frequencyHz;
    }

    /**
     * Initialise the chip: hardware reset (if a `resetPin` was passed to the
     * constructor) or a 10 ms POR wait, then the full register init sequence
     * (FSK SLEEP → LoRa SLEEP → STDBY; FIFO bases; frequency; modem
     * defaults SF7/125 kHz/4-5; +17 dBm PA_BOOST).
     * @returns {Promise<void>}
     */
    async init() {
        if (this._resetPin) {
            await this._resetViaPin(this._resetPin);
        } else {
            await this._sleep(10);
        }
        await this._initRegisters();
    }

    /** Run the register-level init sequence. Also used by Full's `reset()`. */
    async _initRegisters() {
        const version = await this._readReg(_REG_VERSION);
        if (version !== _EXPECTED_VERSION) {
            throw new Error(`RFM9x version mismatch: expected 0x${_EXPECTED_VERSION.toString(16).padStart(2, '0')}, got 0x${version.toString(16).padStart(2, '0')}`);
        }

        await this._writeReg(_REG_OP_MODE, 0x00);
        await this._sleep(1);
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_SLEEP);
        await this._sleep(1);

        if (this._lfBand) {
            const lna = await this._readReg(_REG_LNA);
            await this._writeReg(_REG_LNA, lna & 0x3F);
        } else {
            await this._writeReg(_REG_LNA, 0x23);
        }
        await this._writeReg(_REG_MODEM_CONFIG_3, (await this._readReg(_REG_MODEM_CONFIG_3)) | 0x04);

        await this._writeReg(_REG_FIFO_TX_BASE, 0x80);
        await this._writeReg(_REG_FIFO_RX_BASE, 0x00);

        await this._setFrequency(this._frequencyHz);

        await this._writeReg(_REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00);
        await this._writeReg(_REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03);
        await this._writeReg(_REG_PREAMBLE_LSB, 0x08);

        await this._setTxPower(17, true);
        await this._standby();
    }

    /** Pulse the NRESET pin low then high, per datasheet timing. */
    async _resetViaPin(pin) {
        await pin.set(false);
        await this._sleep(1);
        await pin.set(true);
        await this._sleep(5);
    }

    _sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    async _writeReg(reg, value) {
        await this._connection.write(Buffer.from([reg | 0x80, value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._connection.writeRead(Buffer.from([reg & 0x7F]), 1))[0];
    }

    async _burstWrite(reg, data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        await this._connection.write(Buffer.concat([Buffer.from([reg | 0x80]), buf]));
    }

    async _burstRead(reg, len) {
        return this._connection.writeRead(Buffer.from([reg & 0x7F]), len);
    }

    async _setFrequency(frequencyHz) {
        if (frequencyHz < this.freqMinHz || frequencyHz > this.freqMaxHz) {
            throw new RangeError(`frequencyHz ${frequencyHz} out of range [${this.freqMinHz}, ${this.freqMaxHz}]`);
        }
        const frf = Math.floor((frequencyHz * (1 << 19)) / _FXOSC);
        await this._writeReg(_REG_FRF_MSB, (frf >> 16) & 0xFF);
        await this._writeReg(_REG_FRF_MID, (frf >> 8) & 0xFF);
        await this._writeReg(_REG_FRF_LSB, frf & 0xFF);
        this._frequencyHz = frequencyHz;
    }

    async _setTxPower(powerDbm, usePaBoost = true) {
        if (usePaBoost) {
            if (powerDbm > 17) {
                if (powerDbm > 20) powerDbm = 20;
                await this._writeReg(_REG_PA_DAC, _PA_DAC_HIGH_POWER);
                await this._writeReg(_REG_OCP, _OCP_240MA);
                await this._writeReg(_REG_PA_CONFIG, _PA_BOOST | 0x0F);
            } else {
                if (powerDbm < 2) powerDbm = 2;
                await this._writeReg(_REG_PA_DAC, _PA_DAC_DEFAULT);
                await this._writeReg(_REG_OCP, _OCP_DEFAULT);
                await this._writeReg(_REG_PA_CONFIG, _PA_BOOST | (powerDbm - 2));
            }
        } else {
            await this._writeReg(_REG_PA_DAC, _PA_DAC_DEFAULT);
            await this._writeReg(_REG_OCP, _OCP_DEFAULT);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let op = Math.floor(powerDbm - pmax + 15);
            if (op < 0) op = 0;
            if (op > 15) op = 15;
            await this._writeReg(_REG_PA_CONFIG, (maxPower << 4) | op);
        }
    }

    async _configure(sf, bandwidthKhz, codingRate, crc = true) {
        const bwTable = [7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0];
        let bwCode = 0x07;
        for (let i = 0; i < 10; i++) {
            if (bandwidthKhz === bwTable[i]) { bwCode = i; break; }
        }
        if (sf < 6 || sf > this.maxSF) sf = Math.min(Math.max(sf, 6), this.maxSF);

        if (sf === 6) {
            await this._writeReg(_REG_DETECTION_OPT, 0x05);
            await this._writeReg(_REG_DETECTION_THR, 0x0C);
        } else {
            await this._writeReg(_REG_DETECTION_OPT, 0x03);
            await this._writeReg(_REG_DETECTION_THR, 0x0A);
        }

        const implicitHeader = (sf === 6);
        const cr = (codingRate >= 5 && codingRate <= 8) ? (codingRate - 4) : 0x01;
        await this._writeReg(_REG_MODEM_CONFIG_1, (bwCode << 4) | (cr << 1) | (implicitHeader ? 1 : 0));
        await this._writeReg(_REG_MODEM_CONFIG_2, (sf << 4) | ((crc ? 1 : 0) << 2) | 0x03);
    }

    /**
     * Send a packet.
     * @param {Buffer|Uint8Array|string} data - Payload; max 255 bytes.
     * @returns {Promise<void>}
     */
    async send(data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length > 255) throw new RangeError(`payload length ${buf.length} exceeds 255`);
        await this._standby();
        await this._writeReg(_REG_FIFO_ADDR_PTR, 0x80);
        await this._burstWrite(_REG_FIFO, buf);
        await this._writeReg(_REG_PAYLOAD_LENGTH, buf.length);
        await this._writeReg(_REG_DIO_MAPPING_1, _DIO0_TX_DONE);
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_TX);

        while (true) {
            const irq = await this._readReg(_REG_IRQ_FLAGS);
            if (irq & _IRQ_TX_DONE) break;
            await this._sleep(2);
        }
        await this._writeReg(_REG_IRQ_FLAGS, _IRQ_TX_DONE);
        await this._standby();
    }

    /**
     * Receive a single packet (polling).
     * @param {number} timeoutMs - Receive timeout in milliseconds (default 2000).
     * @returns {Promise<Buffer | null>} Received payload bytes, or null on timeout.
     */
    async receive(timeoutMs = 2000) {
        return this._receivePolling(timeoutMs);
    }

    async _receivePolling(timeoutMs) {
        await this._standby();
        await this._writeReg(_REG_DIO_MAPPING_1, _DIO0_RX_DONE);
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_RX_SINGLE);

        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const irq = await this._readReg(_REG_IRQ_FLAGS);
            if (irq & _IRQ_RX_DONE) {
                await this._writeReg(_REG_IRQ_FLAGS, _IRQ_RX_DONE);
                return await this._readPayload();
            }
            if (irq & _IRQ_RX_TIMEOUT) {
                await this._writeReg(_REG_IRQ_FLAGS, _IRQ_RX_TIMEOUT);
                return null;
            }
            await this._sleep(5);
        }
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_STANDBY);
        return null;
    }

    async _readPayload() {
        const current = await this._readReg(_REG_FIFO_RX_CURRENT);
        await this._writeReg(_REG_FIFO_ADDR_PTR, current);
        const n = await this._readReg(_REG_RX_NB_BYTES);
        return await this._burstRead(_REG_FIFO, n);
    }

    async _standby() {
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_STANDBY);
    }

    async _sleepMode() {
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_SLEEP);
    }
}

/**
 * Mixin that adds Full-stage functionality on top of a `*Minimal` subclass.
 *
 * `_RFM9xBase` structurally lacks these methods (they are not defined
 * anywhere on it), so applying this mixin is the only way to obtain them —
 * a `*Minimal` instance cannot reach Full behaviour even by accident.
 *
 * Usage: `class RFM95Full extends _RFM9xFullMixin(RFM95Minimal) {}`.
 *
 * @param {typeof _RFM9xBase} Base - A `*Minimal` class (itself extending `_RFM9xBase`).
 * @returns {typeof _RFM9xBase} A subclass of `Base` with the Full API added.
 */
const _RFM9xFullMixin = (Base) => class extends Base {
    /**
     * Hardware reset via NRESET pin; re-runs the init sequence.
     * @returns {Promise<void>}
     * @throws {Error} If no `resetPin` was passed to the constructor.
     */
    async reset() {
        if (!this._resetPin) throw new Error('reset() requires a resetPin');
        await this._resetViaPin(this._resetPin);
        await this._initRegisters();
    }

    /**
     * Configure LoRa modulation parameters.
     * @param {number} sf - Spreading factor 6-12 (variant-capped; RFM97W max 9).
     * @param {number} bandwidthKhz - Signal bandwidth in kHz.
     * @param {number} codingRate - Coding rate denominator 5-8.
     * @param {boolean} [crc=true] - True to enable CRC on RX payloads (default).
     * @returns {Promise<void>}
     */
    async configure(sf, bandwidthKhz, codingRate, crc = true) {
        await this._configure(sf, bandwidthKhz, codingRate, crc);
    }

    /**
     * Set the carrier frequency.
     * @param {number} frequencyHz - Carrier frequency in Hz; must lie in the variant's range.
     * @returns {Promise<void>}
     */
    async setFrequency(frequencyHz) {
        await this._setFrequency(frequencyHz);
    }

    /**
     * Set TX output power.
     * @param {number} powerDbm - Output power in dBm. -1 to +14 (RFO) or +2 to +20 (PA_BOOST).
     * @param {boolean} [usePaBoost=true] - True to use PA_BOOST pin (default), false for RFO.
     * @returns {Promise<void>}
     */
    async setTxPower(powerDbm, usePaBoost = true) {
        await this._setTxPower(powerDbm, usePaBoost);
    }

    /**
     * Receive a single packet.
     * @param {number} [timeoutMs=2000] - Receive timeout in milliseconds.
     * @param {boolean} [useInterrupt=false] - True to wait on the DIO0 edge instead
     *   of polling the IRQ status register (requires `dio0Pin` passed to the constructor).
     * @returns {Promise<Buffer | null>} Received payload bytes, or null on timeout.
     * @throws {Error} If `useInterrupt` is true but no `dio0Pin` was configured.
     */
    async receive(timeoutMs = 2000, useInterrupt = false) {
        if (useInterrupt) {
            if (!this._dio0Pin) throw new Error('receive(useInterrupt=true) requires dio0Pin');
            return this._receiveInterrupt(timeoutMs);
        }
        return this._receivePolling(timeoutMs);
    }

    /** DIO0-interrupt-driven single receive, used by receive(useInterrupt=true). */
    async _receiveInterrupt(timeoutMs) {
        await this._standby();
        await this._writeReg(_REG_DIO_MAPPING_1, _DIO0_RX_DONE);
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_RX_SINGLE);

        return new Promise((resolve, reject) => {
            let settled = false;
            let timer = null;

            const onEdge = async () => {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                try {
                    await this._dio0Pin.offEdge(onEdge);
                    const irq = await this._readReg(_REG_IRQ_FLAGS);
                    await this._writeReg(_REG_IRQ_FLAGS, irq);
                    resolve((irq & _IRQ_RX_DONE) ? await this._readPayload() : null);
                } catch (err) {
                    reject(err);
                }
            };

            timer = setTimeout(async () => {
                if (settled) return;
                settled = true;
                try {
                    await this._dio0Pin.offEdge(onEdge);
                    await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_STANDBY);
                    resolve(null);
                } catch (err) {
                    reject(err);
                }
            }, timeoutMs);

            this._dio0Pin.onEdge(onEdge, 'rising').catch(reject);
        });
    }

    /**
     * Enter continuous receive mode; subsequent `readPacket()` calls drain the FIFO.
     * @returns {Promise<void>}
     */
    async receiveContinuous() {
        await this._standby();
        await this._writeReg(_REG_DIO_MAPPING_1, _DIO0_RX_DONE);
        await this._writeReg(_REG_OP_MODE, _MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | _MODE_RX_CONT);
    }

    /**
     * Read one packet from the FIFO in continuous receive mode.
     * @returns {Promise<Buffer | null>} Payload bytes, or null if no packet is waiting.
     */
    async readPacket() {
        const irq = await this._readReg(_REG_IRQ_FLAGS);
        if (!(irq & _IRQ_RX_DONE)) return null;
        await this._writeReg(_REG_IRQ_FLAGS, _IRQ_RX_DONE);
        return await this._readPayload();
    }

    /**
     * Return to STDBY from continuous receive mode.
     * @returns {Promise<void>}
     */
    async stopReceive() {
        await this._standby();
    }

    /**
     * Enter STDBY mode (crystal on, RF/PLL off, FIFO accessible).
     * @returns {Promise<void>}
     */
    async standby() {
        await this._standby();
    }

    /**
     * Enter SLEEP mode (lowest power; FIFO inaccessible).
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._sleepMode();
    }

    /**
     * Read RegVersion. Expect 0x12 (SX1276).
     * @returns {Promise<number>}
     */
    async version() {
        return await this._readReg(_REG_VERSION);
    }

    /**
     * Current channel RSSI in dBm (readable in continuous RX mode).
     * @returns {Promise<number>}
     */
    async rssi() {
        return -137 + await this._readReg(_REG_RSSI);
    }

    /**
     * RSSI of last received packet in dBm.
     * @returns {Promise<number>}
     */
    async lastPacketRssi() {
        return -137 + await this._readReg(_REG_PKT_RSSI);
    }

    /**
     * SNR of last received packet in dB. The raw register holds a signed
     * 8-bit value with 0.25 dB resolution; values below zero mean the
     * signal is below the noise floor.
     * @returns {Promise<number>}
     */
    async lastPacketSnr() {
        let raw = await this._readReg(_REG_PKT_SNR);
        if (raw & 0x80) raw = raw - 0x100;
        return raw / 4.0;
    }
};

/**
 * RFM95W minimal driver — 868/915 MHz HF band, max SF=12.
 *
 * Constructor: see `_RFM9xBase` — `(connection, frequencyHz, resetPin=null, dio0Pin=null)`.
 * `frequencyHz` must lie in 862-1020 MHz.
 */
class RFM95Minimal extends _RFM9xBase {
    static FREQ_MIN_HZ = 862_000_000;
    static FREQ_MAX_HZ = 1_020_000_000;
    static MAX_SF      = 12;
    static LF_BAND     = false;
}

/**
 * RFM96W minimal driver — 433/470 MHz LF band, max SF=12.
 *
 * Constructor: see `_RFM9xBase`. `frequencyHz` must lie in 410-525 MHz.
 */
class RFM96Minimal extends _RFM9xBase {
    static FREQ_MIN_HZ = 410_000_000;
    static FREQ_MAX_HZ = 525_000_000;
    static MAX_SF      = 12;
    static LF_BAND     = true;
}

/**
 * RFM97W minimal driver — 868/915 MHz HF band, max SF=9.
 *
 * Constructor: see `_RFM9xBase`. `frequencyHz` must lie in 862-1020 MHz.
 */
class RFM97Minimal extends _RFM9xBase {
    static FREQ_MIN_HZ = 862_000_000;
    static FREQ_MAX_HZ = 1_020_000_000;
    static MAX_SF      = 9;
    static LF_BAND     = false;
}

/**
 * RFM98W minimal driver — 433/470 MHz LF band, max SF=12.
 *
 * Constructor: see `_RFM9xBase`. `frequencyHz` must lie in 410-525 MHz.
 */
class RFM98Minimal extends _RFM9xBase {
    static FREQ_MIN_HZ = 410_000_000;
    static FREQ_MAX_HZ = 525_000_000;
    static MAX_SF      = 12;
    static LF_BAND     = true;
}

/**
 * RFM95W full driver — adds hardware reset and DIO0 interrupt support.
 */
class RFM95Full extends _RFM9xFullMixin(RFM95Minimal) {}

/**
 * RFM96W full driver — adds hardware reset and DIO0 interrupt support.
 */
class RFM96Full extends _RFM9xFullMixin(RFM96Minimal) {}

/**
 * RFM97W full driver — adds hardware reset and DIO0 interrupt support.
 */
class RFM97Full extends _RFM9xFullMixin(RFM97Minimal) {}

/**
 * RFM98W full driver — adds hardware reset and DIO0 interrupt support.
 */
class RFM98Full extends _RFM9xFullMixin(RFM98Minimal) {}

module.exports = { RFM95Minimal, RFM96Minimal, RFM97Minimal, RFM98Minimal,
                   RFM95Full, RFM96Full, RFM97Full, RFM98Full };
