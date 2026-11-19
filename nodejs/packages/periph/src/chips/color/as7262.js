'use strict';

const _HW_STATUS = 0x00;
const _HW_WRITE = 0x01;
const _HW_READ = 0x02;
const _TX_VALID = 0x02;
const _RX_VALID = 0x01;

const _REG_HW_VERSION = 0x00;
const _REG_FW_VERSION = 0x02;
const _REG_CONTROL = 0x04;
const _REG_INT_T = 0x05;
const _REG_TEMP = 0x06;
const _REG_LED = 0x07;
const _REG_RAW = 0x08;
const _REG_CAL = 0x14;

const _CTRL_RST = 0x80;
const _CTRL_INT = 0x40;
const _CTRL_DATA_RDY = 0x02;

const _INT_T_DEFAULT = 0x3B;
const _GAIN_DEFAULT = 2;
const _STEP_MS = 2.8;
const _TIMEOUT_MS = 1000;
const _CHANNELS = ['violet', 'blue', 'green', 'yellow', 'orange', 'red'];
const _GAINS = [1.0, 3.7, 16.0, 64.0];
const _DRV_CURRENTS = [12.5, 25.0, 50.0, 100.0];
const _IND_CURRENTS = [1.0, 2.0, 4.0, 8.0];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _nearest(options, value) {
    if (value > options[options.length - 1]) {
        throw new RangeError('current above maximum ' + options[options.length - 1] + ' mA');
    }
    let best = 0;
    options.forEach((o, i) => { if (Math.abs(o - value) < Math.abs(options[best] - value)) best = i; });
    return best;
}

/**
 * AS7262 6-channel visible spectral sensor — minimal interface.
 *
 * The AS7262 (ams OSRAM) measures violet (450 nm), blue (500 nm), green (550 nm), yellow (570 nm),
 * orange (600 nm) and red (650 nm). Every sensor register is a *virtual register* reached through
 * three hardware registers (STATUS 0x00, WRITE 0x01, READ 0x02); this class implements the handshake.
 *
 * `init()` soft-resets the chip and configures the Minimal defaults: gain 16x, integration time 0x3B
 * (165.2 ms), one-shot bank mode 3, both LED outputs off, INT pin disabled. `read()` runs one one-shot
 * conversion. Call `await sensor.init()` once before use.
 */
class AS7262Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Register-capable I²C connection pointing at the device (address 0x49).
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _status() {
        return (await this._conn.readReg(_HW_STATUS, 1))[0];
    }

    async _waitStatus(mask, want, timeoutMs = 2000) {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            if (((await this._status()) & mask) === want) return;
            if (Date.now() >= deadline) throw new Error('AS7262 virtual-register handshake time-out');
        }
    }

    /**
     * Read one virtual register through the STATUS/WRITE/READ handshake.
     * @param {number} reg - Virtual register address (0x00-0x2B).
     * @returns {Promise<number>} The register byte.
     */
    async readVirtual(reg) {
        await this._waitStatus(_TX_VALID, 0);
        if ((await this._status()) & _RX_VALID) await this._conn.readReg(_HW_READ, 1);
        await this._conn.writeReg(_HW_WRITE, reg & 0x7F);
        await this._waitStatus(_RX_VALID, _RX_VALID);
        return (await this._conn.readReg(_HW_READ, 1))[0];
    }

    /**
     * Write one virtual register through the STATUS/WRITE handshake.
     * @param {number} reg - Virtual register address (0x04, 0x05 or 0x07 are writable).
     * @param {number} value - Byte to write (0-255).
     * @returns {Promise<void>}
     */
    async writeVirtual(reg, value) {
        await this._waitStatus(_TX_VALID, 0);
        await this._conn.writeReg(_HW_WRITE, (reg & 0x7F) | 0x80);
        await this._waitStatus(_TX_VALID, 0);
        await this._conn.writeReg(_HW_WRITE, value & 0xFF);
    }

    async _waitBoot() {
        const deadline = Date.now() + 2000;
        for (;;) {
            try {
                if ((await this.readVirtual(_REG_HW_VERSION)) === 0x40) return;
            } catch (e) { /* retry */ }
            if (Date.now() >= deadline) throw new Error('AS7262 not found (HW_VERSION != 0x40)');
            await _sleep(10);
        }
    }

    async _reset() {
        await this.writeVirtual(_REG_CONTROL, _CTRL_RST);
        const deadline = Date.now() + _TIMEOUT_MS;
        for (;;) {
            await _sleep(10);
            try {
                if (!((await this.readVirtual(_REG_CONTROL)) & _CTRL_RST)) return;
            } catch (e) { /* retry */ }
            if (Date.now() >= deadline) throw new Error('AS7262 reset time-out');
        }
    }

    /**
     * Wait for the chip to boot, soft-reset it and write the Minimal defaults.
     * @returns {Promise<void>}
     */
    async init() {
        await this._waitBoot();
        await this._reset();
        await this.writeVirtual(_REG_INT_T, _INT_T_DEFAULT);
        await this.writeVirtual(_REG_LED, 0x00);
        await this.writeVirtual(_REG_CONTROL, _GAIN_DEFAULT << 4);
    }

    async _startOneShot() {
        const ctrl = await this.readVirtual(_REG_CONTROL);
        await this.writeVirtual(_REG_CONTROL, (ctrl & 0x70) | (3 << 2));
    }

    async _waitData() {
        const intMs = (await this.readVirtual(_REG_INT_T)) * _STEP_MS;
        const deadline = Date.now() + 2 * intMs + _TIMEOUT_MS;
        for (;;) {
            if ((await this.readVirtual(_REG_CONTROL)) & _CTRL_DATA_RDY) return;
            if (Date.now() >= deadline) throw new Error('AS7262 conversion time-out');
            await _sleep(5);
        }
    }

    async _readCalibrated() {
        const data = Buffer.alloc(24);
        for (let i = 0; i < 24; i++) data[i] = await this.readVirtual(_REG_CAL + i);
        const out = {};
        _CHANNELS.forEach((name, i) => { out[name] = data.readFloatBE(4 * i); });
        return out;
    }

    /**
     * Run one one-shot conversion and return the six calibrated channels.
     * @returns {Promise<{violet: number, blue: number, green: number, yellow: number, orange: number, red: number}>}
     */
    async read() {
        await this._startOneShot();
        await this._waitData();
        return this._readCalibrated();
    }

    /**
     * Read the device temperature.
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        return this.readVirtual(_REG_TEMP);
    }
}

/**
 * AS7262 full interface — raw counts, gain, integration time, bank modes, LEDs, interrupt.
 * Extends AS7262Minimal; call `await sensor.init()` once before use.
 */
class AS7262Full extends AS7262Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Register-capable I²C connection pointing at the device (address 0x49).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPin = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _readRawRegs() {
        const data = [];
        for (let i = 0; i < 12; i++) data.push(await this.readVirtual(_REG_RAW + i));
        const out = {};
        _CHANNELS.forEach((name, i) => { out[name] = (data[2 * i] << 8) | data[2 * i + 1]; });
        return out;
    }

    /**
     * Run one one-shot conversion and return the raw 16-bit counts.
     * @returns {Promise<{violet: number, blue: number, green: number, yellow: number, orange: number, red: number}>}
     */
    async readRaw() {
        await this._startOneShot();
        await this._waitData();
        return this._readRawRegs();
    }

    /** Start a one-shot conversion without waiting for it (BANK = 3). */
    async measure() {
        await this._startOneShot();
    }

    /**
     * @returns {Promise<boolean>} True if a conversion result is waiting (CONTROL_SETUP.DATA_RDY).
     */
    async dataReady() {
        return Boolean((await this.readVirtual(_REG_CONTROL)) & _CTRL_DATA_RDY);
    }

    /**
     * Read the last calibrated results without starting a conversion.
     * @returns {Promise<object>} `{violet, ..., red}` floats.
     */
    async readCalibrated() {
        return this._readCalibrated();
    }

    /**
     * Read the last raw counts without starting a conversion.
     * @returns {Promise<object>} `{violet, ..., red}` integers.
     */
    async readRawLast() {
        return this._readRawRegs();
    }

    /**
     * Read one channel of the last conversion.
     * @param {number} index - Channel 0-5 (V, B, G, Y, O, R).
     * @param {boolean} [calibrated=true] - True for the calibrated float, false for the raw count.
     * @returns {Promise<number>}
     */
    async readChannel(index, calibrated = true) {
        if (!(index >= 0 && index <= 5)) throw new RangeError('channel index must be 0-5');
        if (calibrated) {
            const buf = Buffer.alloc(4);
            for (let i = 0; i < 4; i++) buf[i] = await this.readVirtual(_REG_CAL + 4 * index + i);
            return buf.readFloatBE(0);
        }
        const base = _REG_RAW + 2 * index;
        return ((await this.readVirtual(base)) << 8) | (await this.readVirtual(base + 1));
    }

    async _updateControl(mask, bits) {
        const ctrl = await this.readVirtual(_REG_CONTROL);
        await this.writeVirtual(_REG_CONTROL, (ctrl & 0x7C & ~mask) | bits);
    }

    /**
     * Set the analog gain.
     * @param {number} gain - 0-3 (index), or the gain 1, 3.7, 16 or 64 (1 means 1x).
     */
    async setGain(gain) {
        let index;
        if (_GAINS.includes(gain)) index = _GAINS.indexOf(gain);
        else if (gain === 0 || gain === 2 || gain === 3) index = gain;
        else throw new RangeError('gain must be 0-3 or one of 1, 3.7, 16, 64');
        await this._updateControl(0x30, index << 4);
    }

    /** @returns {Promise<number>} The analog gain multiplier (1, 3.7, 16 or 64). */
    async getGain() {
        return _GAINS[((await this.readVirtual(_REG_CONTROL)) >> 4) & 0x03];
    }

    /**
     * Set the integration time per bank.
     * @param {number} ms - 2.8-714 ms (rounded to a multiple of 2.8 ms).
     */
    async setIntegrationTime(ms) {
        const n = Math.round(ms / _STEP_MS);
        if (n < 1 || n > 255) throw new RangeError('integration time must be 2.8-714 ms');
        await this.writeVirtual(_REG_INT_T, n);
    }

    /** @returns {Promise<number>} The integration time per bank in milliseconds. */
    async getIntegrationTime() {
        return (await this.readVirtual(_REG_INT_T)) * _STEP_MS;
    }

    /**
     * Set the bank mode.
     * @param {number} mode - 0 (V,B,G,Y continuous), 1 (G,Y,O,R continuous), 2 (all six continuous) or 3 (all six, one-shot).
     */
    async setMode(mode) {
        if (!(mode >= 0 && mode <= 3)) throw new RangeError('mode must be 0-3');
        await this._updateControl(0x0C, mode << 2);
    }

    /** @returns {Promise<number>} The bank mode (0-3). */
    async getMode() {
        return ((await this.readVirtual(_REG_CONTROL)) >> 2) & 0x03;
    }

    /**
     * Switch to continuous conversions.
     * @param {number} [mode=2] - Bank mode 0, 1 or 2.
     */
    async setContinuous(mode = 2) {
        if (![0, 1, 2].includes(mode)) throw new RangeError('continuous mode must be 0, 1 or 2');
        await this.setMode(mode);
    }

    /** @param {boolean} enabled - Enable or disable the INT pin output (CONTROL_SETUP.INT). */
    async setInterrupt(enabled) {
        await this._updateControl(_CTRL_INT, enabled ? _CTRL_INT : 0);
    }

    /**
     * Control the driver LED output.
     * @param {boolean} on - True to enable the output.
     * @param {number} [currentMa=12.5] - Current limit; nearest of 12.5, 25, 50, 100 mA.
     */
    async setLed(on, currentMa = 12.5) {
        const index = _nearest(_DRV_CURRENTS, currentMa);
        const led = (await this.readVirtual(_REG_LED)) & 0x07;
        await this.writeVirtual(_REG_LED, led | (index << 4) | (on ? 0x08 : 0));
    }

    /**
     * Control the indicator LED output.
     * @param {boolean} on - True to enable the output.
     * @param {number} [currentMa=1] - Current limit; nearest of 1, 2, 4, 8 mA.
     */
    async setIndicatorLed(on, currentMa = 1) {
        const index = _nearest(_IND_CURRENTS, currentMa);
        const led = (await this.readVirtual(_REG_LED)) & 0x38;
        await this.writeVirtual(_REG_LED, led | (index << 1) | (on ? 0x01 : 0));
    }

    /** Soft-reset the chip (all registers return to their defaults). */
    async softReset() {
        await this._reset();
    }

    /** @returns {Promise<number[]>} `[device_type, hardware_version]` — expect `[0x40, 0x3E]`. */
    async hardwareVersion() {
        return [await this.readVirtual(_REG_HW_VERSION), await this.readVirtual(_REG_HW_VERSION + 1)];
    }

    /** @returns {Promise<number[]>} The firmware version as `[major, minor, sub]`. */
    async firmwareVersion() {
        const r2 = await this.readVirtual(_REG_FW_VERSION);
        const r3 = await this.readVirtual(_REG_FW_VERSION + 1);
        return [r3 >> 4, ((r3 & 0x0F) << 2) | (r2 >> 6), r2 & 0x3F];
    }

    /** @returns {Promise<number>} The device type byte (virtual register 0x00, expect 0x40). */
    async deviceType() {
        return this.readVirtual(_REG_HW_VERSION);
    }

    /**
     * Read CONTROL_SETUP (releases INT) and return DATA_RDY.
     * @returns {Promise<number>} 1 if data is ready, else 0.
     */
    async pollInterrupt() {
        return (await this.readVirtual(_REG_CONTROL)) & _CTRL_DATA_RDY ? 1 : 0;
    }

    async _dispatch() {
        const cb = this._callback;
        if (!cb) return;
        try {
            const status = await this.pollInterrupt();
            if (status) cb(status);
        } catch (e) { /* ignore */ }
    }

    /**
     * Enable the INT output and call `callback(status)` on data ready. Subscribes to the falling
     * edge of the INT pin (`intPin` or `connection.intPin`); otherwise polls every integration time.
     * @param {function(number): void} callback - Called with 1 when data is ready.
     * @param {object} [intPin] - Optional InputPin; defaults to `connection.intPin`.
     */
    async onInterrupt(callback, intPin = null) {
        this._callback = callback;
        await this.setInterrupt(true);
        const pin = intPin || this._conn.intPin;
        if (pin) {
            this._intPin = pin;
            this._edgeHandler = () => this._dispatch();
            await pin.onEdge(this._edgeHandler, 'falling');
        } else {
            const periodMs = Math.max(await this.getIntegrationTime(), 10);
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try { await this._dispatch(); } finally { busy = false; }
            }, periodMs);
        }
    }

    /** Unsubscribe from the INT pin (or stop the polling task) and disable the INT output. */
    async offInterrupt() {
        this._callback = null;
        if (this._intPin && this._edgeHandler) await this._intPin.offEdge(this._edgeHandler);
        this._intPin = null;
        this._edgeHandler = null;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        await this.setInterrupt(false);
    }
}

AS7262Minimal.CHANNELS = _CHANNELS;

module.exports = { AS7262Minimal, AS7262Full };
