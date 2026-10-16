'use strict';

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Busy-wait for sub-millisecond pulses (setTimeout cannot do microseconds). */
function delayUs(us) {
    const end = process.hrtime.bigint() + BigInt(Math.ceil(us)) * 1000n;
    while (process.hrtime.bigint() < end) { /* spin */ }
}

/**
 * How the controller's data pin is wired to the host.
 *
 * - SIMPLEX:         MOSI -> DIN, write only; readData() is not available.
 * - HALF_DUPLEX_HW:  DIN/DOUT shared with MOSI, read through the SPI peripheral's
 *                    hardware 3-wire mode. Not available through spi-device.
 * - HALF_DUPLEX_PIN: MOSI -- resistor -- MISO joined at DIN/DOUT, read through a
 *                    full-duplex (hardware or software) SPI master.
 */
const WireMode = Object.freeze({ SIMPLEX: 0, HALF_DUPLEX_HW: 1, HALF_DUPLEX_PIN: 2 });

/**
 * Display connection (SPI + D/C#, RES#, BUSY) for Node.js.
 *
 * Bundles an SPI link to a display controller (OLED: SH1106; e-paper: SSD1683,
 * UC8276C; ...) with its side-band lines: D/C# (command/data), RES# (hardware
 * reset) and BUSY. RES# and BUSY are optional: an OLED has no BUSY line and may
 * tie RES# high. Chip drivers call sendCommand(), sendData(), readData(),
 * reset() and waitBusy() and never touch D/C, RES, BUSY or CS themselves. See
 * specs/transport_display.md.
 *
 * The BUSY polarity differs between controllers, so it is chip-level
 * knowledge: waitBusy() takes it as an argument and busyLevel() returns the raw
 * level.
 *
 * CS is hardware CS only: every sendCommand()/sendData()/readData() byte group
 * is its own SPI transfer; a command and its parameters are separate CS windows
 * and readData() uses one window per byte.
 *
 * Two ways to reach the controller; give exactly one of:
 * - Hardware SPI: `busNumber` + `deviceNumber` open spi-device (kernel-owned CS),
 *   or `device`, an already-opened spi-device object (used by tests).
 * - Software SPI: `spi`, a SoftSPI-like object (write(buf), read(n)), plus `cs`,
 *   an OutputPin driven here for each transfer.
 */
class DisplayConnection {
    /**
     * @param {object} options
     * @param {import('./output_pin').OutputPin} options.dc - D/C# pin (false = command, true = data).
     * @param {import('./output_pin').OutputPin|null} [options.res=null] - RES# pin, or null if not wired.
     * @param {import('./input_pin').InputPin|null} [options.busy=null] - BUSY pin read with isHigh(), or null.
     * @param {number} [options.wireMode=WireMode.SIMPLEX] - A WireMode value.
     * @param {number} [options.busNumber] - Hardware mode: SPI bus number.
     * @param {number} [options.deviceNumber] - Hardware mode: chip-select line on the bus.
     * @param {number} [options.maxSpeedHz=4000000] - Hardware mode: SPI clock in Hz.
     * @param {object} [options.device] - Hardware mode: an already-opened spi-device object.
     * @param {object} [options.spi] - Software mode: SoftSPI-like object.
     * @param {import('./output_pin').OutputPin} [options.cs] - Software mode: CS# pin (active low).
     * @param {number} [options.resetPulseUs=200] - RES# low time in microseconds.
     * @param {number} [options.resetSettleMs=10] - Wait after RES# is released in milliseconds.
     * @param {import('./output_pin').OutputPin|null} [options.enPin=null] - Optional EN-pin OutputPin.
     * @param {boolean} [options.enActiveHigh=true] - True if the EN pin is active-high.
     */
    constructor(options = {}) {
        const hardware = options.busNumber !== undefined || options.device !== undefined;
        const software = options.spi !== undefined;
        if (hardware === software) {
            throw new Error('specify exactly one of {busNumber, deviceNumber} / {device} or {spi, cs}');
        }
        if (!options.dc) throw new Error('dc pin is required');
        const wireMode = options.wireMode ?? WireMode.SIMPLEX;
        if (![WireMode.SIMPLEX, WireMode.HALF_DUPLEX_HW, WireMode.HALF_DUPLEX_PIN].includes(wireMode)) {
            throw new Error(`unknown wireMode ${wireMode}`);
        }
        if (wireMode === WireMode.HALF_DUPLEX_HW) {
            throw new Error('HALF_DUPLEX_HW is not available through spi-device; use HALF_DUPLEX_PIN');
        }
        if (software) {
            if (!options.cs) throw new Error('software SPI needs a cs OutputPin');
            if (wireMode === WireMode.HALF_DUPLEX_PIN && typeof options.spi.read !== 'function') {
                throw new Error('HALF_DUPLEX_PIN needs a software SPI with read() and a MISO pin');
            }
        }

        this._dc = options.dc;
        this._res = options.res ?? null;
        this._busy = options.busy ?? null;
        this._cs = software ? options.cs : null;
        this._soft = software ? options.spi : null;
        this.wireMode = wireMode;
        this._resetPulseUs = options.resetPulseUs ?? 200;
        this._resetSettleMs = options.resetSettleMs ?? 10;
        this.enPin = options.enPin ?? null;
        this.enActiveHigh = options.enActiveHigh ?? true;
        this._enabled = true;

        if (hardware) {
            if (options.device !== undefined) {
                this._device = options.device;
            } else {
                const spi = require('spi-device');
                this._device = spi.openSync(options.busNumber, options.deviceNumber, {
                    mode: spi.MODE0,
                    maxSpeedHz: options.maxSpeedHz ?? 4_000_000,
                });
            }
        } else {
            this._device = null;
        }
        this._ready = this._init();
    }

    /** Set the idle levels of the side-band lines (D/C# low, RES# high, CS# high). */
    async _init() {
        await this._dc.set(false);
        if (this._res) await this._res.set(true);
        if (this._cs) await this._cs.set(true);
    }

    /** Resolves once the constructor has driven the idle levels. @returns {Promise<void>} */
    ready() { return this._ready; }

    /** Resume operation; drives the hardware EN pin to its active level if wired. @returns {Promise<void>} */
    async enable() {
        this._enabled = true;
        if (this.enPin) await this.enPin.set(this.enActiveHigh);
    }

    /** Gate all bus operations; drives the hardware EN pin inactive if wired. @returns {Promise<void>} */
    async disable() {
        this._enabled = false;
        if (this.enPin) await this.enPin.set(!this.enActiveHigh);
    }

    /** @returns {boolean} The current software-gate state. */
    isEnabled() { return this._enabled; }

    /**
     * Hardware reset: RES# low for resetPulseUs, then high and wait resetSettleMs.
     *
     * Does not wait for BUSY; chip drivers call waitBusy() afterwards with their
     * polarity. No-op if disabled or no RES# pin was given.
     * @returns {Promise<void>}
     */
    async reset() {
        if (!this._enabled || !this._res) return;
        await this._res.set(false);
        delayUs(this._resetPulseUs);
        await this._res.set(true);
        await sleepMs(this._resetSettleMs);
    }

    /**
     * Wait @p ms milliseconds (for chips without a BUSY pin, e.g. a power-up wait).
     * @param {number} ms - Time in milliseconds.
     * @returns {Promise<void>}
     */
    async delayMs(ms) { await sleepMs(ms); }

    /** @returns {boolean} Raw level of the BUSY line; false when no BUSY pin was given. */
    busyLevel() {
        return this._busy !== null && this._busy.isHigh();
    }

    /**
     * Poll BUSY (every millisecond) until it is no longer active.
     * @param {boolean} busyActiveHigh - true if the controller drives BUSY high while busy
     *   (SSD1683), false if it drives it low (UC8276C BUSY_N).
     * @param {number|null} [timeoutMs=null] - Give up after this many milliseconds; null waits forever.
     * @returns {Promise<boolean>} true when the controller became ready (or has no BUSY pin),
     *   false on timeout.
     */
    async waitBusy(busyActiveHigh, timeoutMs = null) {
        if (this._busy === null) return true;
        const start = Date.now();
        while (this._busy.isHigh() === !!busyActiveHigh) {
            if (timeoutMs !== null && Date.now() - start >= timeoutMs) return false;
            await sleepMs(1);
        }
        return true;
    }

    async _spiWrite(buf) {
        if (this._soft) {
            await this._cs.set(false);
            this._soft.write(buf);
            await this._cs.set(true);
        } else {
            this._device.transferSync([{ sendBuffer: buf, byteLength: buf.length }]);
        }
    }

    async _spiRead(n) {
        if (this._soft) {
            await this._cs.set(false);
            const out = this._soft.read(n);
            await this._cs.set(true);
            return out;
        }
        const receiveBuffer = Buffer.alloc(n);
        this._device.transferSync([{ receiveBuffer, byteLength: n }]);
        return receiveBuffer;
    }

    /**
     * Send a command byte (D/C# low), then optional parameter bytes (D/C# high).
     *
     * The command byte and the parameters are separate SPI transfers (separate
     * CS windows). No-op if disabled.
     * @param {number} cmd - Command byte.
     * @param {Buffer|Uint8Array|number[]} [data=[]] - Parameter bytes.
     * @returns {Promise<void>}
     */
    async sendCommand(cmd, data = []) {
        if (!this._enabled) return;
        await this._dc.set(false);
        await this._spiWrite(Buffer.from([cmd]));
        const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (payload.length > 0) {
            await this._dc.set(true);
            await this._spiWrite(payload);
        }
    }

    /**
     * Send data bytes (D/C# high) as one SPI transfer. No-op if disabled.
     * @param {Buffer|Uint8Array|number[]} data - Bytes to send, e.g. a pixel plane.
     * @returns {Promise<void>}
     */
    async sendData(data) {
        if (!this._enabled) return;
        const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (payload.length === 0) return;
        await this._dc.set(true);
        await this._spiWrite(payload);
    }

    /**
     * Read length bytes the controller drives after a read command (D/C# high).
     *
     * One CS window per byte. A disabled connection returns zeros.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} The bytes read.
     * @throws {Error} In SIMPLEX wire mode.
     */
    async readData(length) {
        if (this.wireMode === WireMode.SIMPLEX) {
            throw new Error('readData() is not available in SIMPLEX wire mode');
        }
        if (!this._enabled) return Buffer.alloc(length);
        await this._dc.set(true);
        const out = Buffer.alloc(length);
        for (let i = 0; i < length; i++) {
            out[i] = (await this._spiRead(1))[0];
        }
        return out;
    }

    /** Close the SPI device if this connection opened one. */
    close() {
        if (this._device && typeof this._device.closeSync === 'function') this._device.closeSync();
    }
}

module.exports = { DisplayConnection, WireMode };
