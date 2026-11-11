'use strict';

// Port-0 register addresses; port 1 is +1. A two-byte transfer at a port-0
// register toggles to port 1 (register-pair layout).
const REG_INPUT    = 0x00;
const REG_OUTPUT   = 0x02;
const REG_POLARITY = 0x04;
const REG_CONFIG   = 0x06;

/**
 * PCA6416A 16-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * Provides 16 bidirectional GPIO pins (0–7 = P0_0–P0_7, 8–15 = P1_0–P1_7) via a pin() factory. Each
 * returned pin exposes async read()/write() (Connection is async everywhere,
 * so a synchronous pin proxy cannot return a value); pin.asGpio() gives a
 * synchronous opengpio-shaped facade. 16-bit shadows of OUTPUT and CONFIG make
 * a single-pin change one register write. The chip has no internal pull-ups.
 * The registers form pairs: a two-byte transfer at a port-0 register covers
 * both ports.
 *
 * Registers are accessed after the constructor's initialisation sequence
 * (`this.ready`) has finished.
 */
class Pca6416aMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus (no PEC) register connection.
     * @param {number} [addr=0x20] - 7-bit I²C address (0x20 ADDR = VSS, 0x21 ADDR = VDD(P)).
     */
    constructor(connection, addr = 0x20) {
        this._conn      = connection;
        this._addr      = addr;
        this._shadow    = 0xFFFF;
        this._direction = 0xFFFF;
        this._polarity  = 0x0000;
        this._lastInput = 0x0000;
        this._ready     = this._init();
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    async _init() {
        await this._conn.writeReg(REG_OUTPUT, Buffer.from([0xFF, 0xFF]));
        await this._conn.writeReg(REG_POLARITY, Buffer.from([0x00, 0x00]));
        await this._conn.writeReg(REG_CONFIG, Buffer.from([0xFF, 0xFF]));
        this._lastInput = await this._read16Raw(REG_INPUT);
    }

    async _read16Raw(base) {
        const b = await this._conn.readReg(base, 2);
        return b[0] | (b[1] << 8);
    }

    // One two-byte read at the port-0 register returns port 0 then port 1.
    async _read16(base) {
        await this._ready;
        return this._read16Raw(base);
    }

    async _write16(base, value) {
        await this._ready;
        await this._conn.writeReg(base, Buffer.from([value & 0xFF, (value >> 8) & 0xFF]));
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
        this._shadow = mask & 0xFFFF;
        await this._write16(REG_OUTPUT, this._shadow);
    }

    async _setPin(n, value) {
        if (value) this._shadow |= (1 << n);
        else       this._shadow &= ~(1 << n);
        const port = n >> 3;
        await this._regWrite(REG_OUTPUT + port, this._shadow >> (8 * port));
    }

    async _setDirection(n, input) {
        const port = n >> 3;
        if (input) {
            this._direction |= (1 << n);
        } else {
            // Latch the output level first so the pin never glitches.
            await this._regWrite(REG_OUTPUT + port, this._shadow >> (8 * port));
            this._direction &= ~(1 << n);
        }
        await this._regWrite(REG_CONFIG + port, this._direction >> (8 * port));
    }

    /**
     * Return a Pin proxy for pin n (0–15). All sixteen pins are bidirectional.
     *
     * @param {number} n - Pin index (0–7 = port 0, 8–15 = port 1).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy.
     */
    pin(n, direction = 'in') {
        return new _Pin(this, n, direction);
    }

    /**
     * Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
     *
     * @param {number} [port=0] - 0 (P0_0–P0_7) or 1 (P1_0–P1_7).
     * @returns {Promise<number>} 8-bit bitmask (bit 0 = first pin of the port).
     * @throws {Error} if port is not 0 or 1.
     */
    async readPort(port = 0) {
        if (port !== 0 && port !== 1) throw new Error('port must be 0 or 1');
        return this._regRead(REG_INPUT + port);
    }

    /**
     * Write all 8 output bits of a port via OUTPUTx and update the shadow. Bits
     * of input pins are stored but have no effect until the pin becomes an output.
     *
     * @param {number} [port=0] - 0 (P0_0–P0_7) or 1 (P1_0–P1_7).
     * @param {number} mask - 8-bit output mask.
     * @returns {Promise<void>}
     * @throws {Error} if port is not 0 or 1.
     */
    async writePort(port = 0, mask = 0xFF) {
        if (port !== 0 && port !== 1) throw new Error('port must be 0 or 1');
        mask &= 0xFF;
        this._shadow = (this._shadow & ~(0xFF << (8 * port))) | (mask << (8 * port));
        await this._regWrite(REG_OUTPUT + port, mask);
    }
}

/**
 * GPIO proxy for a single pin. Obtain via pin(n); do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Pca6416aMinimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–15).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n & 15;
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
        return ((await this._chip.readPort(this._n >> 3)) >> (this._n & 7)) & 1;
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
 * Full GPIO proxy — adds watch()/unwatch() for interrupt-driven input.
 */
class _FullPin extends _Pin {
    /**
     * Subscribe to this pin's edge events.
     *
     * At most one handler per pin; a second call replaces the first. The chip
     * reports changes only; rising/falling is filtered in software. Delivery
     * starts when the chip's onInterrupt() is called.
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
        if (!this._chip._intPinUsed && !this._chip._pollTimer && this._chip._conn.intPin) {
            await this._chip._arm(this._chip._conn.intPin);
        }
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
 * PCA6416A full interface — polarity, whole-device direction, interrupts, raw access.
 */
class Pca6416aFull extends Pca6416aMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus (no PEC) register connection.
     * @param {number} [addr=0x20] - 7-bit I²C address.
     */
    constructor(connection, addr = 0x20) {
        super(connection, addr);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._level = 0x0000;
        this._watchers = {};
        this._edgeHandler = () => { this._service(); };
    }

    /**
     * Return a Full Pin proxy for pin n (0–15).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
     */
    pin(n, direction = 'in') {
        return new _FullPin(this, n, direction);
    }

    /**
     * Read both INPUT registers in one two-byte transaction.
     * @returns {Promise<number>} 16-bit value (bit n = pin n; bits 0–7 port 0, 8–15 port 1).
     */
    async readAll() {
        return this._read16(REG_INPUT);
    }

    /**
     * Write both OUTPUT registers in one two-byte transaction and update the shadow.
     * @param {number} value - 16-bit output value (bit n = pin n).
     * @returns {Promise<void>}
     */
    async writeAll(value) {
        await this._writeOutput(value);
    }

    /**
     * Configure input polarity inversion (POLARITY0/1 ← mask).
     * @param {number} mask - 16-bit mask: 1 = invert the INPUT bit of that pin.
     * @returns {Promise<void>}
     */
    async configurePolarity(mask) {
        this._polarity = mask & 0xFFFF;
        await this._write16(REG_POLARITY, this._polarity);
        this._lastInput = await this._read16(REG_INPUT);
    }

    /**
     * Return the shadow CONFIG registers (1 = input).
     * @returns {number} 16-bit direction mask.
     */
    getDirection() {
        return this._direction;
    }

    /**
     * Write CONFIG0/1 in one two-byte transaction (1 = input, 0 = output).
     * OUTPUT is written first so pins turning into outputs never glitch; the
     * cached input value is refreshed afterwards to drop the false interrupt
     * an output→input change can raise.
     * @param {number} mask - 16-bit direction mask.
     * @returns {Promise<void>}
     */
    async setDirection(mask) {
        await this._write16(REG_OUTPUT, this._shadow);
        this._direction = mask & 0xFFFF;
        await this._write16(REG_CONFIG, this._direction);
        this._lastInput = await this._read16(REG_INPUT);
    }

    /**
     * Raw register read (debug / escape hatch).
     * @param {number} reg - Register address 0–7.
     * @returns {Promise<number>}
     * @throws {Error} if reg is outside 0–7.
     */
    async readRegister(reg) {
        if (reg < 0 || reg > 7) throw new Error('register must be 0-7');
        return this._regRead(reg);
    }

    /**
     * Raw register write (2–7); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     * @param {number} reg - Register address 2–7 (INPUT0/1 are read-only).
     * @param {number} value - 8-bit value.
     * @returns {Promise<void>}
     * @throws {Error} if reg is 0 or 1, or outside 2–7.
     */
    async writeRegister(reg, value) {
        if (reg < 2 || reg > 7) throw new Error('register must be 2-7 (INPUT is read-only)');
        value &= 0xFF;
        const shift = (reg & 1) * 8;
        const keep = ~(0xFF << shift) & 0xFFFF;
        if (reg >> 1 === 1) this._shadow = (this._shadow & keep) | (value << shift);
        else if (reg >> 1 === 2) this._polarity = (this._polarity & keep) | (value << shift);
        else this._direction = (this._direction & keep) | (value << shift);
        await this._regWrite(reg, value);
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF, both
     * ports) and refresh the input cache. The RESET pin is external and not driven.
     * @returns {Promise<void>}
     */
    async reset() {
        await this.offInterrupt();
        await this._write16(REG_OUTPUT, 0xFFFF);
        await this._write16(REG_POLARITY, 0x0000);
        await this._write16(REG_CONFIG, 0xFFFF);
        this._shadow = 0xFFFF;
        this._polarity = 0x0000;
        this._direction = 0xFFFF;
        this._lastInput = await this._read16(REG_INPUT);
        this._level = this._lastInput;
    }

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 5);
        }
    }

    /**
     * Subscribe to INT assertions. `callback(changedMask)` receives the 16-bit
     * mask of input pins whose level changed since the previous INPUT read.
     * Delivery is via the INT line (options.intPin or connection.intPin,
     * falling edge) or a 5 ms polling timer.
     *
     * @param {function} callback - Called with (changedMask).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        await this._arm(options.intPin ?? this._conn.intPin ?? null);
    }

    /**
     * Unsubscribe and stop delivery.
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
        if (this._polling) return;
        this._polling = true;
        try {
            const changed = await this.pollInterrupt();
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
     * Read both INPUT registers (clearing INT) and return the changed-pin mask:
     * the XOR of the new and the cached INPUT value, limited to pins configured
     * as inputs.
     * @returns {Promise<number>} 16-bit mask; bit n = 1 if input pin n changed.
     */
    async pollInterrupt() {
        const now = await this._read16(REG_INPUT);
        const changed = (now ^ this._lastInput) & this._direction;
        this._lastInput = now;
        this._level = now;
        return changed;
    }
}

module.exports = { Pca6416aMinimal, Pca6416aFull };
