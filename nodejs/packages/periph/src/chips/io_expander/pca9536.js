'use strict';

const REG_INPUT    = 0x00;
const REG_OUTPUT   = 0x01;
const REG_POLARITY = 0x02;
const REG_CONFIG   = 0x03;
const MASK         = 0x0F; // bits 7:4 are unused

/**
 * PCA9536 4-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * Provides 4 bidirectional GPIO pins (P0–P3) via a pin() factory. Each
 * returned pin exposes async read()/write() (Connection is async everywhere,
 * so a synchronous pin proxy cannot return a value); pin.asGpio() gives a
 * synchronous opengpio-shaped facade. Shadows of OUTPUT and CONFIG make a
 * single-pin change one register write. Every I/O pin has a weak (about
 * 100 kΩ) pull-up that is always on and cannot be switched; the chip has no
 * register auto-increment: every register is accessed one byte at a time.
 *
 * Registers are accessed after the constructor's initialisation sequence
 * (`this.ready`) has finished.
 */
class Pca9536Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus (no PEC) register connection.
     * @param {number} [addr=0x41] - 7-bit I²C address (fixed 0x41).
     */
    constructor(connection, addr = 0x41) {
        this._conn      = connection;
        this._addr      = addr;
        this._shadow    = 0xFF;
        this._direction = 0xFF;
        this._polarity  = 0x00;
        this._lastInput = 0x00;
        this._ready     = this._init();
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    async _init() {
        await this._conn.writeReg(REG_OUTPUT, 0xFF);
        await this._conn.writeReg(REG_POLARITY, 0x00);
        await this._conn.writeReg(REG_CONFIG, 0xFF);
        this._lastInput = (await this._conn.readReg(REG_INPUT, 1))[0] & MASK;
    }

    async _regRead(reg) {
        await this._ready;
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _regWrite(reg, value) {
        await this._ready;
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _writeOutput(mask) {
        // Bits 7:4 are unused; keep them at their reset value (1).
        this._shadow = (mask & MASK) | 0xF0;
        await this._regWrite(REG_OUTPUT, this._shadow);
    }

    async _setPin(n, value) {
        if (value) this._shadow |= (1 << n);
        else       this._shadow &= ~(1 << n);
        await this._writeOutput(this._shadow);
    }

    async _setDirection(n, input) {
        if (input) {
            this._direction |= (1 << n);
        } else {
            // Latch the output level first so the pin never glitches.
            await this._regWrite(REG_OUTPUT, this._shadow);
            this._direction &= ~(1 << n);
        }
        await this._regWrite(REG_CONFIG, this._direction);
    }

    /**
     * Return a Pin proxy for pin n (0–3). All four pins are bidirectional.
     *
     * @param {number} n - Pin index (0–3).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy.
     */
    pin(n, direction = 'in') {
        if (!Number.isInteger(n) || n < 0 || n > 3) throw new RangeError('pin must be 0-3');
        return new _Pin(this, n, direction);
    }

    /**
     * Read all 4 pins as a bitmask (INPUT register, actual pin levels).
     *
     * @param {number} [port=0] - Ignored; the chip has a single port.
     * @returns {Promise<number>} 4-bit bitmask (bit 0 = P0).
     */
    async readPort(port = 0) {
        return (await this._regRead(REG_INPUT)) & MASK;
    }

    /**
     * Write all 4 output bits via OUTPUT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param {number} [port=0] - Ignored; the chip has a single port.
     * @param {number} mask - 4-bit output mask.
     * @returns {Promise<void>}
     */
    async writePort(port = 0, mask = 0xFF) {
        await this._writeOutput(mask);
    }
}

/**
 * GPIO proxy for a single pin. Obtain via pin(n); do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Pca9536Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–3).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n & 3;
        this._direction = direction;
        this._ready     = direction === 'out' ? chip._setDirection(this._n, false) : Promise.resolve();
    }

    /** @type {string} Pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Read the pin (actual logic level).
     * @returns {Promise<number>} 0 or 1.
     */
    async read() {
        await this._ready;
        return ((await this._chip.readPort()) >> this._n) & 1;
    }

    /**
     * Write the pin's output latch.
     * @param {number} value - 0 or 1.
     * @returns {Promise<void>}
     */
    async write(value) {
        await this._ready;
        await this._chip._setPin(this._n, value ? 1 : 0);
    }

    /**
     * Set pin direction (writes CONFIG).
     * @param {string} direction - 'in' or 'out'.
     * @returns {Promise<void>}
     */
    async setDirection(direction) {
        await this._ready;
        this._direction = direction;
        await this._chip._setDirection(this._n, direction !== 'out');
    }

    /**
     * Return a synchronous opengpio-shaped Input/Output facade for this pin.
     *
     * Output facades read back the shadow register (authoritative) and write
     * through fire-and-forget. Input facades are backed by a 5 ms background
     * poll and are therefore eventually consistent; call stop() to release it.
     *
     * @returns {{direction: string, value: boolean, stop: function}} opengpio-shaped facade.
     */
    asGpio() {
        const pin = this;
        if (this._direction === 'out') {
            return {
                get direction() { return 'out'; },
                get value() { return ((pin._chip._shadow >> pin._n) & 1) === 1; },
                set value(v) { pin._chip._setPin(pin._n, v ? 1 : 0); },
                stop() {},
            };
        }
        let cached = false;
        const timer = setInterval(async () => { cached = (await pin.read()) === 1; }, 5);
        return {
            get direction() { return 'in'; },
            get value() { return cached; },
            set value(_v) { throw new Error('cannot set value on an input pin'); },
            stop() { clearInterval(timer); },
        };
    }

    /** Release the pin (no-op; shadow state preserved). */
    stop() {}
}

/**
 * Full GPIO proxy — adds watch()/unwatch() for polling-based input events.
 */
class _FullPin extends _Pin {
    /**
     * Subscribe to this pin's edge events (polling-based).
     *
     * At most one handler per pin; a second call replaces the first. The chip
     * has no interrupt output and reports changes only; rising/falling is
     * filtered in software. A polling timer is started on first use.
     *
     * @param {function} handler - Called with this pin when its state matches trigger.
     * @param {string} [trigger='change'] - 'rising', 'falling', or 'change'.
     * @returns {Promise<void>}
     * @throws {Error} if the pin is configured as an output.
     */
    async watch(handler, trigger = 'change') {
        await this._ready;
        if (((this._chip._direction >> this._n) & 1) === 0) throw new Error('cannot watch an output pin');
        this._chip._watchers[this._n] = { handler, trigger };
        this._chip._startPinPolling();
    }

    /**
     * Unsubscribe this pin's handler.
     * @returns {Promise<void>}
     */
    async unwatch() {
        delete this._chip._watchers[this._n];
    }
}

/**
 * PCA9536 full interface — polarity, whole-port direction, change polling, raw access.
 *
 * The chip has no interrupt output, so change detection is by polling
 * (pollChanges / watchChanges); Pin.watch is built on it.
 */
class Pca9536Full extends Pca9536Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus (no PEC) register connection.
     * @param {number} [addr=0x41] - 7-bit I²C address (fixed 0x41).
     */
    constructor(connection, addr = 0x41) {
        super(connection, addr);
        this._callback = null;
        this._pollTimer = null;
        this._polling = false;
        this._level = 0x00;
        this._watchers = {};
    }

    /**
     * Return a Full Pin proxy for pin n (0–3).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
     */
    pin(n, direction = 'in') {
        if (!Number.isInteger(n) || n < 0 || n > 3) throw new RangeError('pin must be 0-3');
        return new _FullPin(this, n, direction);
    }

    /**
     * Configure input polarity inversion (POLARITY ← mask). The cached input
     * value is refreshed, so the polarity change itself is not reported by
     * pollChanges().
     * @param {number} mask - 4-bit mask: 1 = invert the INPUT bit.
     * @returns {Promise<void>}
     */
    async configurePolarity(mask) {
        this._polarity = mask & MASK;
        await this._regWrite(REG_POLARITY, this._polarity);
        this._lastInput = (await this._regRead(REG_INPUT)) & MASK;
    }

    /**
     * Return the shadow CONFIG register (1 = input).
     * @returns {number} 4-bit direction mask.
     */
    getDirection() {
        return this._direction & MASK;
    }

    /**
     * Write CONFIG in one transaction (1 = input, 0 = output). OUTPUT is
     * written first so pins turning into outputs never glitch; the cached
     * input value is refreshed afterwards.
     * @param {number} mask - 4-bit direction mask.
     * @returns {Promise<void>}
     */
    async setDirection(mask) {
        await this._regWrite(REG_OUTPUT, this._shadow);
        this._direction = (mask & MASK) | 0xF0;
        await this._regWrite(REG_CONFIG, this._direction);
        this._lastInput = (await this._regRead(REG_INPUT)) & MASK;
    }

    /**
     * Raw register read (debug / escape hatch).
     * @param {number} reg - Register address 0–3.
     * @returns {Promise<number>}
     * @throws {Error} if reg is outside 0–3.
     */
    async readRegister(reg) {
        if (reg < 0 || reg > 3) throw new Error('register must be 0-3');
        return this._regRead(reg);
    }

    /**
     * Raw register write (1–3); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     * @param {number} reg - Register address 1–3 (INPUT is read-only).
     * @param {number} value - 8-bit value.
     * @returns {Promise<void>}
     * @throws {Error} if reg is 0 or outside 1–3.
     */
    async writeRegister(reg, value) {
        if (reg < 1 || reg > 3) throw new Error('register must be 1-3 (INPUT is read-only)');
        value &= 0xFF;
        if (reg === REG_OUTPUT) this._shadow = value;
        else if (reg === REG_POLARITY) this._polarity = value;
        else this._direction = value;
        await this._regWrite(reg, value);
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and
     * refresh the input cache. The chip has no RESET pin; only a power cycle
     * clears a lock-up.
     * @returns {Promise<void>}
     */
    async reset() {
        this.unwatchChanges();
        await this._regWrite(REG_OUTPUT, 0xFF);
        await this._regWrite(REG_POLARITY, 0x00);
        await this._regWrite(REG_CONFIG, 0xFF);
        this._shadow = 0xFF;
        this._polarity = 0x00;
        this._direction = 0xFF;
        this._lastInput = (await this._regRead(REG_INPUT)) & MASK;
    }

    /**
     * Start a polling timer that reports input changes. `callback(changedMask)`
     * is invoked whenever pollChanges() returns a non-zero mask.
     *
     * @param {function} callback - Called with (changedMask).
     * @param {number} [intervalS=0.005] - Polling interval in seconds.
     */
    watchChanges(callback, intervalS = 0.005) {
        this.unwatchChanges();
        this._callback = callback;
        this._pollTimer = setInterval(() => { this._service(); }, Math.max(1, intervalS * 1000));
    }

    /** Stop the polling timer started by watchChanges (and pin watchers). */
    unwatchChanges() {
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    _startPinPolling() {
        if (!this._pollTimer) this.watchChanges(null);
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const changed = await this.pollChanges();
            if (!changed) return;
            if (this._callback) this._callback(changed);
            for (const [bit, w] of Object.entries(this._watchers)) {
                if (!((changed >> bit) & 1)) continue;
                const current = (this._level >> bit) & 1;
                if (w.trigger === 'change' || (w.trigger === 'rising' && current === 1) ||
                    (w.trigger === 'falling' && current === 0)) {
                    w.handler(this.pin(Number(bit)));
                }
            }
        } finally {
            this._polling = false;
        }
    }

    /**
     * Read INPUT and return the changed-pin mask: the XOR of the new and the
     * cached INPUT value, limited to pins configured as inputs. The new value
     * is cached (software edge detection; there is no hardware interrupt).
     * @returns {Promise<number>} 4-bit mask; bit n = 1 if input Pn changed.
     */
    async pollChanges() {
        const now = (await this._regRead(REG_INPUT)) & MASK;
        const changed = (now ^ this._lastInput) & this._direction & MASK;
        this._lastInput = now;
        this._level = now;
        return changed;
    }
}

module.exports = { Pca9536Minimal, Pca9536Full };
