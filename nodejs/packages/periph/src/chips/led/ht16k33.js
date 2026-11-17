'use strict';

const _CMD_SYSTEM  = 0x20;
const _CMD_DISPLAY = 0x80;
const _CMD_DIMMING = 0xE0;
const _CMD_KEY_PTR = 0x40;
const _CMD_INT_PTR = 0x60;

/**
 * HT16K33 16x8 LED matrix driver — minimal interface.
 *
 * Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM
 * shadowed in the driver. I2C address 0x70 (A0-A2 select 0x70-0x77).
 * The constructor starts the initialization sequence (oscillator on, ROW15
 * as a ROW output, brightness 16/16, blink off, RAM cleared, display on);
 * every bus method waits for it, and `ready` exposes it.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device.
 */
class HT16K33Minimal {
    constructor(connection) {
        this._conn = connection;
        this._ram = Buffer.alloc(16);
        this._brightness = 15;
        this._blink = 0;
        this._display = true;
        this._rowInt = 0xA0;
        this._ready = this._init();
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    async _init() {
        await this._command(_CMD_SYSTEM | 0x01);
        await this._command(this._rowInt);
        await this._command(_CMD_DIMMING | this._brightness);
        await this._conn.write(Buffer.concat([Buffer.from([0x00]), this._ram]));
        await this._applyDisplay();
    }

    async _command(byte) {
        await this._conn.write(Buffer.from([byte]));
    }

    async _applyDisplay() {
        await this._command(_CMD_DISPLAY | (this._blink << 1) | (this._display ? 1 : 0));
    }

    /**
     * Zero the shadow RAM and write it to the chip.
     * @returns {Promise<void>}
     */
    async clear() {
        await this._ready;
        this._ram.fill(0);
        await this.show();
    }

    /**
     * Set or clear one LED in the shadow RAM (does not write to the chip).
     * @param {number} com - COM line, 0-7.
     * @param {number} row - ROW line, 0-15.
     * @param {boolean} [on=true] - true to light the LED.
     */
    setPixel(com, row, on = true) {
        if (!Number.isInteger(com) || com < 0 || com > 7) throw new RangeError('com must be in 0..7');
        if (!Number.isInteger(row) || row < 0 || row > 15) throw new RangeError('row must be in 0..15');
        const idx = 2 * com + (row >> 3);
        const mask = 1 << (row & 7);
        if (on) this._ram[idx] |= mask;
        else this._ram[idx] &= ~mask & 0xFF;
    }

    /**
     * Write raw RAM bytes at `address` and update the shadow RAM.
     * @param {number[]|Buffer} data - 1-16 bytes of display data.
     * @param {number} [address=0] - Start RAM address, 0-15.
     * @returns {Promise<void>}
     */
    async writeRaw(data, address = 0) {
        const buf = Buffer.from(data);
        if (address < 0 || buf.length < 1 || address + buf.length > 16) {
            throw new RangeError('address + data.length must be within 0..16');
        }
        await this._ready;
        buf.copy(this._ram, address);
        await this._conn.write(Buffer.concat([Buffer.from([address]), buf]));
    }

    /**
     * Write the whole 16-byte shadow RAM to the chip.
     * @returns {Promise<void>}
     */
    async show() {
        await this._ready;
        await this._conn.write(Buffer.concat([Buffer.from([0x00]), this._ram]));
    }

    /**
     * Set the dimming level.
     * @param {number} level - 0-15, giving (level + 1)/16 duty.
     * @returns {Promise<void>}
     */
    async setBrightness(level) {
        if (!Number.isInteger(level) || level < 0 || level > 15) throw new RangeError('level must be in 0..15');
        await this._ready;
        this._brightness = level;
        await this._command(_CMD_DIMMING | level);
    }
}

HT16K33Minimal.COMMONS = 8;
HT16K33Minimal.ROWS = 16;

/**
 * HT16K33 full interface — display control, standby, key scan, INT output.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device.
 */
class HT16K33Full extends HT16K33Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /** Turn the display output on (keeps the blink setting). @returns {Promise<void>} */
    async displayOn() {
        await this._ready;
        this._display = true;
        await this._applyDisplay();
    }

    /** Turn the display output off (keeps the blink setting). @returns {Promise<void>} */
    async displayOff() {
        await this._ready;
        this._display = false;
        await this._applyDisplay();
    }

    /**
     * Set the blink frequency.
     * @param {number} frequency - 0 (off), 2, 1 or 0.5 Hz.
     * @returns {Promise<void>}
     */
    async setBlink(frequency) {
        const codes = new Map([[0, 0], [2, 1], [1, 2], [0.5, 3]]);
        if (!codes.has(frequency)) throw new RangeError('frequency must be 0, 2, 1 or 0.5');
        await this._ready;
        this._blink = codes.get(frequency);
        await this._applyDisplay();
    }

    /** Stop the oscillator (display off, key scan stopped). @returns {Promise<void>} */
    async standby() {
        await this._ready;
        await this._command(_CMD_SYSTEM);
    }

    /** Start the oscillator and restore display, dimming, ROW/INT and RAM. @returns {Promise<void>} */
    async wake() {
        await this._ready;
        await this._command(_CMD_SYSTEM | 0x01);
        await this._command(this._rowInt);
        await this._command(_CMD_DIMMING | this._brightness);
        await this.show();
        await this._applyDisplay();
    }

    /**
     * Select the function of the ROW15/INT pin.
     * @param {boolean} enable - true makes the pin an interrupt output (ROW15/K13 unusable).
     * @param {boolean} [activeHigh=false] - INT polarity when enabled.
     * @returns {Promise<void>}
     */
    async setIntOutput(enable, activeHigh = false) {
        await this._ready;
        this._rowInt = enable ? (activeHigh ? 0xA3 : 0xA1) : 0xA0;
        await this._command(this._rowInt);
    }

    /**
     * Read and clear the key data RAM.
     * @returns {Promise<number>} Key bitmask, bit k*13 + (K-1) for key input K (1-13) on key source KSk (0-2).
     */
    async readKeys() {
        await this._ready;
        await this._conn.write(Buffer.from([_CMD_KEY_PTR]));
        const b = await this._conn.read(6);
        let mask = 0;
        for (let k = 0; k < 3; k++) {
            const word = (b[2 * k] | (b[2 * k + 1] << 8)) & 0x1FFF;
            mask += word * Math.pow(2, 13 * k);
        }
        return mask;
    }

    /** Read the INT flag. @returns {Promise<boolean>} true if a key press is pending. */
    async keyPressed() {
        await this._ready;
        await this._conn.write(Buffer.from([_CMD_INT_PTR]));
        const b = await this._conn.read(1);
        return (b[0] & 0x01) !== 0;
    }

    /**
     * Read one LED state from the shadow RAM.
     * @param {number} com - COM line, 0-7.
     * @param {number} row - ROW line, 0-15.
     * @returns {boolean}
     */
    getPixel(com, row) {
        if (com < 0 || com > 7 || row < 0 || row > 15) throw new RangeError('com 0..7, row 0..15');
        return ((this._ram[2 * com + (row >> 3)] >> (row & 7)) & 1) === 1;
    }

    /**
     * Check the INT flag and, if set, read (and clear) the key data.
     * @returns {Promise<number>} Key bitmask, or 0 if no key is pending.
     */
    async pollInterrupt() {
        if (!(await this.keyPressed())) return 0;
        return this.readKeys();
    }

    /**
     * Subscribe to key-press events. Delivery is via the INT line
     * (options.intPin or connection.intPin, edge per the configured
     * polarity) or a 20 ms polling timer.
     * @param {function} callback - Called with (keys).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const intPin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, this._rowInt === 0xA3 ? 'rising' : 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 20);
        }
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
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
            const keys = await this.pollInterrupt();
            if (keys && this._callback) this._callback(keys);
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { HT16K33Minimal, HT16K33Full };
