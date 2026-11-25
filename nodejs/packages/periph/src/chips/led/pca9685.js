'use strict';

const _REG_MODE1 = 0x00;
const _REG_MODE2 = 0x01;
const _REG_SUBADR1 = 0x02;
const _REG_ALLCALLADR = 0x05;
const _REG_LED0 = 0x06;
const _REG_ALL_LED = 0xFA;
const _REG_PRE_SCALE = 0xFE;

const _RESTART = 0x80;
const _EXTCLK = 0x40;
const _AI = 0x20;
const _SLEEP = 0x10;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _check(value, name, max) {
    if (!Number.isInteger(value) || value < 0 || value > max) throw new RangeError(`${name} must be in 0..${max}`);
}

function _dutyBytes(duty) {
    if (typeof duty !== 'number' || !(duty >= 0 && duty <= 1)) throw new RangeError('duty must be in 0.0..1.0');
    const off = Math.round(duty * 4096);
    if (off <= 0) return Buffer.from([0, 0, 0, 0x10]);
    if (off >= 4096) return Buffer.from([0, 0x10, 0, 0]);
    return Buffer.from([0, 0, off & 0xFF, off >> 8]);
}

function _pwmBytes(on, off) {
    _check(on, 'on', 4095);
    _check(off, 'off', 4095);
    if (on === off) throw new RangeError('on must differ from off');
    return Buffer.from([on & 0xFF, on >> 8, off & 0xFF, off >> 8]);
}

/**
 * PCA9685 16-channel, 12-bit PWM LED/servo controller — minimal interface.
 *
 * Dims LEDs / drives servos on 16 channels with one call each. The constructor starts the
 * initialization (sleep, PRE_SCALE = 5 for 1 kHz, then wake with auto-increment on and ALLCALL off,
 * 1 ms oscillator wait); every bus method waits for it and `ready` exposes it. Outputs are totem-pole,
 * not inverted, and change on the I²C STOP; all channels keep their power-on state (off).
 * Bus calls are serialised internally.
 */
class PCA9685Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x40-0x6F or 0x71-0x77).
     */
    constructor(connection) {
        this._conn = connection;
        this._chain = Promise.resolve();
        this._ready = this._exec(async () => {
            await this._conn.writeReg(_REG_MODE1, _AI | _SLEEP);
            await this._conn.writeReg(_REG_PRE_SCALE, 5);
            await this._conn.writeReg(_REG_MODE1, _AI);
            await _sleep(1);
        });
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    _exec(fn) {
        const p = this._chain.then(fn);
        this._chain = p.catch(() => {});
        return p;
    }

    /**
     * Set the duty cycle of one channel (ON = 0, OFF = round(duty * 4096)); 0.0 writes full OFF and 1.0 full ON.
     * @param {number} channel - 0-15.
     * @param {number} duty - 0.0-1.0.
     * @returns {Promise<void>}
     */
    setDuty(channel, duty) {
        _check(channel, 'channel', 15);
        const data = _dutyBytes(duty);
        return this._exec(() => this._conn.writeReg(_REG_LED0 + 4 * channel, data));
    }
}

/**
 * PCA9685 full interface — phase-shifted PWM, frequency, servo pulses, modes, addresses.
 */
class PCA9685Full extends PCA9685Minimal {
    async _mode1() { return (await this._conn.readReg(_REG_MODE1, 1))[0]; }

    /**
     * Write the ON and OFF counts of one channel in one transaction (phase-shifted PWM).
     * @param {number} channel - 0-15.
     * @param {number} on - 0-4095.
     * @param {number} off - 0-4095, must differ from `on`.
     * @returns {Promise<void>}
     */
    setPwm(channel, on, off) {
        _check(channel, 'channel', 15);
        const data = _pwmBytes(on, off);
        return this._exec(() => this._conn.writeReg(_REG_LED0 + 4 * channel, data));
    }

    /**
     * Read the ON and OFF counts of one channel (without the full-ON/OFF flags).
     * @param {number} channel - 0-15.
     * @returns {Promise<{on: number, off: number}>}
     */
    getPwm(channel) {
        _check(channel, 'channel', 15);
        return this._exec(async () => {
            const d = await this._conn.readReg(_REG_LED0 + 4 * channel, 4);
            return { on: ((d[1] & 0x0F) << 8) | d[0], off: ((d[3] & 0x0F) << 8) | d[2] };
        });
    }

    /**
     * Force a channel permanently high (full-ON bit set, full-OFF bit cleared).
     * @param {number} channel - 0-15.
     * @returns {Promise<void>}
     */
    setFullOn(channel) {
        _check(channel, 'channel', 15);
        return this._exec(() => this._conn.writeReg(_REG_LED0 + 4 * channel, Buffer.from([0, 0x10, 0, 0])));
    }

    /**
     * Force a channel permanently low (full-OFF bit set; it wins over full ON).
     * @param {number} channel - 0-15.
     * @returns {Promise<void>}
     */
    setFullOff(channel) {
        _check(channel, 'channel', 15);
        return this._exec(() => this._conn.writeReg(_REG_LED0 + 4 * channel, Buffer.from([0, 0, 0, 0x10])));
    }

    /**
     * Set the same duty cycle on all 16 channels (ALL_LED registers).
     * @param {number} duty - 0.0-1.0.
     * @returns {Promise<void>}
     */
    setAllDuty(duty) {
        const data = _dutyBytes(duty);
        return this._exec(() => this._conn.writeReg(_REG_ALL_LED, data));
    }

    /**
     * Set the same ON and OFF counts on all 16 channels.
     * @param {number} on - 0-4095.
     * @param {number} off - 0-4095, must differ from `on`.
     * @returns {Promise<void>}
     */
    setAllPwm(on, off) {
        const data = _pwmBytes(on, off);
        return this._exec(() => this._conn.writeReg(_REG_ALL_LED, data));
    }

    /**
     * Set the PWM frequency: PRE_SCALE = round(oscHz / (4096 * hz)) - 1, clamped 3-255. Sleeps the chip,
     * writes PRE_SCALE, wakes it, waits 500 us and restarts running channels.
     * @param {number} hz - Wanted frequency in Hz (24-1526 at 25 MHz).
     * @param {number} [oscHz=25000000] - Oscillator frequency in Hz.
     * @returns {Promise<void>}
     */
    setFrequency(hz, oscHz = 25000000) {
        if (!(hz > 0) || !(oscHz > 0)) throw new RangeError('hz and oscHz must be positive');
        const prescale = Math.max(3, Math.min(255, Math.round(oscHz / (4096 * hz)) - 1));
        return this._exec(async () => {
            const old = await this._mode1();
            const awake = old & ~(_RESTART | _SLEEP) & 0xFF;
            await this._conn.writeReg(_REG_MODE1, (old & 0x7F) | _SLEEP);
            await this._conn.writeReg(_REG_PRE_SCALE, prescale);
            await this._conn.writeReg(_REG_MODE1, awake);
            await _sleep(1);
            if (old & _RESTART) await this._conn.writeReg(_REG_MODE1, awake | _RESTART);
        });
    }

    /**
     * Read PRE_SCALE and return the PWM frequency.
     * @param {number} [oscHz=25000000] - Oscillator frequency in Hz.
     * @returns {Promise<number>} oscHz / (4096 * (PRE_SCALE + 1)) in Hz.
     */
    getFrequency(oscHz = 25000000) {
        return this._exec(async () => oscHz / (4096 * ((await this._conn.readReg(_REG_PRE_SCALE, 1))[0] + 1)));
    }

    /**
     * Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds * frequency * 4096).
     * Uses the frequency programmed in PRE_SCALE (25 MHz oscillator).
     * @param {number} channel - 0-15.
     * @param {number} seconds - Pulse width, 0 up to one PWM period.
     * @returns {Promise<void>}
     */
    setPulseWidth(channel, seconds) {
        _check(channel, 'channel', 15);
        if (!(seconds >= 0)) throw new RangeError('seconds must be >= 0');
        return this._exec(async () => {
            const hz = 25000000 / (4096 * ((await this._conn.readReg(_REG_PRE_SCALE, 1))[0] + 1));
            const data = _dutyBytes(Math.min(1, seconds * hz));
            await this._conn.writeReg(_REG_LED0 + 4 * channel, data);
        });
    }

    /** Enter low-power mode (MODE1.SLEEP = 1, oscillator off). @returns {Promise<void>} */
    sleep() {
        return this._exec(async () => {
            await this._conn.writeReg(_REG_MODE1, ((await this._mode1()) & 0x7F) | _SLEEP);
        });
    }

    /** Leave low-power mode, wait 500 us and restart channels that were running. @returns {Promise<void>} */
    wake() {
        return this._exec(async () => {
            const old = await this._mode1();
            await this._conn.writeReg(_REG_MODE1, old & ~(_RESTART | _SLEEP) & 0xFF);
            await _sleep(1);
            if (old & _RESTART) await this._conn.writeReg(_REG_MODE1, (old & ~_SLEEP & 0xFF) | _RESTART);
        });
    }

    /** Datasheet restart sequence (read MODE1, clear SLEEP, wait 500 us, write RESTART). @returns {Promise<void>} */
    restart() {
        return this._exec(async () => {
            const old = await this._mode1();
            await this._conn.writeReg(_REG_MODE1, old & ~(_RESTART | _SLEEP) & 0xFF);
            await _sleep(1);
            await this._conn.writeReg(_REG_MODE1, (old & ~_SLEEP & 0xFF) | _RESTART);
        });
    }

    /**
     * Configure MODE2: output driver, inversion and the output state while OE = 1.
     * @param {boolean} [openDrain=false] - true for open-drain, false for totem-pole.
     * @param {boolean} [invert=false] - Invert the output logic.
     * @param {number} [offState=PCA9685Full.OFF_LOW] - OFF_LOW, OFF_HIGH_OR_HIGHZ or OFF_HIGHZ.
     * @returns {Promise<void>}
     */
    setOutputMode(openDrain = false, invert = false, offState = PCA9685Full.OFF_LOW) {
        _check(offState, 'offState', 2);
        const mode2 = (invert ? 0x10 : 0) | (openDrain ? 0 : 0x04) | offState;
        return this._exec(async () => {
            const old = (await this._conn.readReg(_REG_MODE2, 1))[0];
            await this._conn.writeReg(_REG_MODE2, (old & 0x08) | mode2);
        });
    }

    /**
     * Select when outputs change: on ACK (true, MODE2.OCH = 1) or on STOP (false, default).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    setUpdateOnAck(enabled) {
        return this._exec(async () => {
            const old = (await this._conn.readReg(_REG_MODE2, 1))[0];
            await this._conn.writeReg(_REG_MODE2, enabled ? (old | 0x08) : (old & ~0x08 & 0xFF));
        });
    }

    /**
     * Switch to the EXTCLK pin as clock (sticky until power cycle / software reset). Pass the external
     * clock frequency as `oscHz` to setFrequency() / getFrequency() afterwards.
     * @returns {Promise<void>}
     */
    useExternalClock() {
        return this._exec(async () => {
            const sleeping = ((await this._mode1()) & 0x7F) | _SLEEP;
            await this._conn.writeReg(_REG_MODE1, sleeping);
            await this._conn.writeReg(_REG_MODE1, sleeping | _EXTCLK);
            await this._conn.writeReg(_REG_MODE1, (sleeping | _EXTCLK) & ~_SLEEP & 0xFF);
            await _sleep(1);
        });
    }

    /**
     * Enable or disable the LED All Call address and set its value.
     * @param {boolean} enabled
     * @param {number} [address=0x70] - 7-bit address.
     * @returns {Promise<void>}
     */
    setAllcall(enabled, address = 0x70) {
        _check(address, 'address', 0x7F);
        return this._exec(async () => {
            await this._conn.writeReg(_REG_ALLCALLADR, address << 1);
            const old = await this._mode1();
            await this._conn.writeReg(_REG_MODE1, (old & 0x7E) | (enabled ? 1 : 0));
        });
    }

    /**
     * Program and enable/disable one of the three LED Sub Call addresses.
     * @param {number} index - 1-3.
     * @param {number} address - 7-bit address.
     * @param {boolean} [enabled=true]
     * @returns {Promise<void>}
     */
    setSubaddress(index, address, enabled = true) {
        if (!Number.isInteger(index) || index < 1 || index > 3) throw new RangeError('index must be in 1..3');
        _check(address, 'address', 0x7F);
        const mask = 1 << (4 - index);
        return this._exec(async () => {
            await this._conn.writeReg(_REG_SUBADR1 + index - 1, address << 1);
            const old = (await this._mode1()) & 0x7F;
            await this._conn.writeReg(_REG_MODE1, enabled ? (old | mask) : (old & ~mask & 0xFF));
        });
    }

    /**
     * Return the chip registers to their power-on values by writing the documented defaults
     * (MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off). No general-call software reset.
     * @returns {Promise<void>}
     */
    reset() {
        return this._exec(async () => {
            await this._conn.writeReg(_REG_MODE1, 0x10);
            await this._conn.writeReg(_REG_PRE_SCALE, 0x1E);
            await this._conn.writeReg(_REG_MODE2, 0x04);
            await this._conn.writeReg(_REG_ALL_LED, Buffer.from([0, 0, 0, 0x10]));
            await this._conn.writeReg(_REG_SUBADR1, Buffer.from([0xE2, 0xE4, 0xE8, 0xE0]));
            await this._conn.writeReg(_REG_MODE1, 0x11);
        });
    }

    /**
     * Send the Software Reset call (0x06) over a connection bound to the general call address 0x00.
     * Resets every PCA9685 on the bus; wait at least 500 us before using the chips again.
     * @param {object} generalCall - Connection bound to I²C address 0x00.
     * @returns {Promise<void>}
     */
    static softwareReset(generalCall) {
        return generalCall.write(Buffer.from([0x06]));
    }

    /**
     * Read the raw mode registers.
     * @returns {Promise<{mode1: number, mode2: number}>}
     */
    readMode() {
        return this._exec(async () => {
            const d = await this._conn.readReg(_REG_MODE1, 2);
            return { mode1: d[0], mode2: d[1] };
        });
    }
}

PCA9685Minimal.CHANNELS = 16;
PCA9685Minimal.ADDRESS = 0x40;
PCA9685Full.OFF_LOW = 0;
PCA9685Full.OFF_HIGH_OR_HIGHZ = 1;
PCA9685Full.OFF_HIGHZ = 2;

module.exports = { PCA9685Minimal, PCA9685Full };
