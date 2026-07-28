'use strict';

const REG_IODIRA  = 0x00;
const REG_IODIRB  = 0x01;
const REG_IPOLA   = 0x02;
const REG_IPOLB   = 0x03;
const REG_GPINTENA = 0x04;
const REG_GPINTENB = 0x05;
const REG_DEFVALA  = 0x06;
const REG_DEFVALB  = 0x07;
const REG_INTCONA  = 0x08;
const REG_INTCONB  = 0x09;
const REG_IOCON    = 0x0A;
const REG_GPPUA    = 0x0C;
const REG_GPPUB    = 0x0D;
const REG_INTFA    = 0x0E;
const REG_INTFB    = 0x0F;
const REG_INTCAPA  = 0x10;
const REG_INTCAPB  = 0x11;
const REG_GPIOA    = 0x12;
const REG_GPIOB    = 0x13;
const REG_OLATA    = 0x14;
const REG_OLATB    = 0x15;

/**
 * MCP23017 16-bit bidirectional I/O port expander — minimal interface.
 *
 * Provides access to 16 GPIO pins (GPA0–GPA7 and GPB0–GPB7) via a pin()
 * factory. Each returned pin exposes async read()/write() (not onoff's
 * synchronous readSync()/writeSync() — Connection is async everywhere, so
 * a synchronous pin proxy can no longer correctly return a value). Call
 * pin.asGpio() for a synchronous opengpio-shaped facade when a pin needs
 * to be passed somewhere a real opengpio Input/Output is expected — see
 * _Pin.asGpio() below.
 *
 * At construction, all pins initialise as inputs except GPA7 and GPB7
 * which are output-only on the hardware and are forced to output mode.
 * A shadow register tracks the output latch (OLATA/OLATB) to enable
 * bit-level read-modify-write without an I²C read.
 *
 * IOCON.BANK is left at 0 (power-on default) throughout; do not alter it.
 */
class Mcp23017Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     * @param {number} [addr=0x20] - 7-bit I²C address (default 0x20, range 0x20–0x27).
     */
    constructor(connection, addr = 0x20) {
        this._conn   = connection;
        this._addr   = addr;
        this._shadow = [0, 0];

        // fire-and-forget: underlying I2C write is synchronous
        this._writeReg(REG_OLATA,  0x00);
        this._writeReg(REG_OLATB,  0x00);
        this._writeReg(REG_IODIRA, 0x7F);
        this._writeReg(REG_IODIRB, 0x7F);
        this._writeReg(REG_IPOLA,  0x00);
        this._writeReg(REG_IPOLB,  0x00);
        this._writeReg(REG_GPPUA,  0x00);
        this._writeReg(REG_GPPUB,  0x00);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg, value & 0xFF]));
    }

    async _readReg(reg) {
        const buf = await this._conn.writeRead(Buffer.from([reg]), 1);
        return buf[0];
    }

    async _writePort(port, mask) {
        await this._writeReg(REG_OLATA + port, mask & 0xFF);
    }

    async _readPort(port) {
        return this._readReg(REG_GPIOA + port);
    }

    async _setPin(n, value) {
        const port = n >> 3;
        const bit  = n & 7;
        if (value) this._shadow[port] |=  (1 << bit);
        else       this._shadow[port] &= ~(1 << bit);
        await this._writePort(port, this._shadow[port]);
    }

    /**
     * Return a Pin proxy for pin n (0–15).
     *
     * Pins 0–7 map to PORTA (GPA0–GPA7); pins 8–15 map to PORTB (GPB0–GPB7).
     * Pins 7 and 15 (GPA7/GPB7) are output-only; attempting to set them
     * as input via direction='in' will still allow reading the output latch
     * value, but the hardware forces them as outputs.
     *
     * @param {number} n - Pin index (0–15).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy.
     */
    pin(n, direction = 'in') {
        return new _Pin(this, n, direction);
    }

    /**
     * Read all 8 pins of a port as a bitmask.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {Promise<number>} 8-bit bitmask (bit 0 = pin 0 of the port).
     */
    async readPort(port = 0) {
        return this._readPort(port);
    }

    /**
     * Write all 8 output pins of a port via the output latch.
     * Updates the internal shadow register.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit output mask.
     * @returns {Promise<void>}
     */
    async writePort(port = 0, mask = 0x00) {
        this._shadow[port] = mask & 0xFF;
        await this._writePort(port, this._shadow[port]);
    }

    /**
     * Configure the direction (IODIR) of a full port.
     * @param {number} port - 0 = PORTA (IODIRA), 1 = PORTB (IODIRB).
     * @param {number} mask - 8-bit mask; bit = 1 → input, 0 → output.
     * @returns {Promise<void>}
     */
    async configureDirection(port, mask) {
        await this._writeReg(REG_IODIRA + (port & 1), mask & 0xFF);
    }
}

/**
 * GPIO proxy for a single MCP23017 pin.
 *
 * Obtain via Mcp23017Minimal.pin(n). Do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Mcp23017Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–15).
     * @param {string} direction - 'in' or 'out'; fixed for the pin's lifetime.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n;
        this._direction = direction;
    }

    /** @type {string} Pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Read pin.
     * @returns {Promise<number>} 0 or 1.
     */
    async read() {
        return (await this._chip._readPort(this._n >> 3) >> (this._n & 7)) & 1;
    }

    /**
     * Write pin.
     * @param {number} value - 0 or 1.
     * @returns {Promise<void>}
     */
    async write(value) {
        await this._chip._setPin(this._n, value ? 1 : 0);
    }

    /**
     * Set pin direction.
     * @param {string} direction - 'in' or 'out'.
     * @returns {Promise<void>}
     */
    async setDirection(direction) {
        this._direction = direction;
    }

    /**
     * Return a synchronous opengpio-shaped Input/Output facade for this
     * pin — a boolean `.value` getter/setter, fixed `direction`, and
     * `stop()` — so it can be passed anywhere code expects a real
     * opengpio GPIO line.
     *
     * Output-direction facades read back the shadow register directly
     * (authoritative) and write through fire-and-forget. Input-direction
     * facades are backed by a 5 ms background poll that refreshes a
     * cached value — `.value` is therefore eventually consistent, not a
     * live bus read on every access like this pin's own async read().
     * Call stop() to release the background poll when done.
     *
     * @returns {{direction: string, value: boolean, stop: function}} opengpio-shaped facade.
     */
    asGpio() {
        const pin = this;
        if (this._direction === 'out') {
            const port = this._n >> 3;
            const bit = this._n & 7;
            return {
                get direction() { return 'out'; },
                get value() { return ((pin._chip._shadow[port] >> bit) & 1) === 1; },
                set value(v) { pin._chip._setPin(pin._n, v ? 1 : 0); }, // fire-and-forget
                stop() {},
            };
        }
        let cached = false;
        const timer = setInterval(async () => {
            cached = (await pin.read()) === 1;
        }, 5);
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
 * MCP23017 full interface — extends Minimal with interrupt support,
 * per-pin pull-ups, and interrupt-on-change configuration.
 *
 * Level 3: the chip has two independent INT lines (INTA/INTB), one per
 * port. onInterrupt()/offInterrupt() subscribe/unsubscribe both ports at
 * once (delivered via one shared InputPin — wire IOCON.MIRROR or external
 * wiring so a single physical line carries both); onInterruptPort()/
 * offInterruptPort() target one port with its own InputPin. pollInterrupt()
 * reads and clears INTF; readCapture() reads INTCAP (the latched pin state)
 * separately. Per-pin watch()/unwatch() are also available.
 */
class Mcp23017Full extends Mcp23017Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     * @param {number} [addr=0x20] - 7-bit I²C address.
     */
    constructor(connection, addr = 0x20) {
        super(connection, addr);
        this._callbackBoth = null;
        this._callbackPort = [null, null];
        this._intPinUsed   = [null, null];
        this._pollTimer    = [null, null];
        this._watchers     = [{}, {}]; // per port: { [bit]: { handler, trigger, lastState } }
        this._edgeHandlerA = () => { this._handleEdge(0); };
        this._edgeHandlerB = () => { this._handleEdge(1); };
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
     * Enable/disable per-pin pull-ups on a port.
     *
     * Pull-ups are only electrically active on pins configured as inputs.
     * Enabling them on output pins has no hardware effect but is allowed
     * in Full (caller responsibility).
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit mask: 1 = enable 100 kΩ pull-up.
     * @returns {Promise<void>}
     */
    async configurePullup(port = 0, mask = 0x00) {
        await this._writeReg(REG_GPPUA + port, mask & 0xFF);
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit mask: 1 = invert GPIO read.
     * @returns {Promise<void>}
     */
    async configurePolarity(port = 0, mask = 0x00) {
        await this._writeReg(REG_IPOLA + port, mask & 0xFF);
    }

    /**
     * Set DEFVAL register for default-compare interrupt mode.
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit default compare value.
     * @returns {Promise<void>}
     */
    async setDefaultValue(port = 0, mask = 0x00) {
        await this._writeReg(REG_DEFVALA + (port & 1), mask & 0xFF);
    }

    async _armPort(port, intPin) {
        await this._writeReg(REG_INTCONA + port, 0x00); // interrupt-on-change mode
        await this._writeReg(REG_GPINTENA + port, 0xFF);
        this._intPinUsed[port] = intPin;
        if (this._pollTimer[port]) { clearInterval(this._pollTimer[port]); this._pollTimer[port] = null; }
        if (intPin) {
            await intPin.onEdge(port === 0 ? this._edgeHandlerA : this._edgeHandlerB, 'falling');
        } else {
            this._pollTimer[port] = setInterval(() => { this._handleEdge(port); }, 5);
        }
    }

    /**
     * Subscribe to INT assertions on both ports.
     *
     * The common case: both INT lines share one physical GPIO, either via
     * IOCON.MIRROR (options.mirror = true) or external wiring. Wires
     * options.intPin (or connection.intPin if omitted) to poll both ports
     * on every edge. Falls back to two independent 5 ms polling loops if no
     * InputPin is available.
     *
     * @param {function} callback - Called with (port, status) on any input change.
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @param {boolean} [options.mirror=false] - Set IOCON.MIRROR so either port's interrupt activates both INTA and INTB.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callbackBoth = callback;
        const iocon = await this._readReg(REG_IOCON);
        await this._writeReg(REG_IOCON, options.mirror ? (iocon | (1 << 6)) : iocon);
        const pin = options.intPin ?? this._conn.intPin ?? null;
        await this._armPort(0, pin);
        await this._armPort(1, pin);
    }

    /**
     * Subscribe to INT assertions on a single port.
     *
     * Use this (with a dedicated InputPin per call) when INTA and INTB are
     * wired to two separate GPIOs.
     *
     * @param {number} port - Port index: 0 = PORTA (INTA), 1 = PORTB (INTB).
     * @param {function} callback - Called with (status) on any input change.
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterruptPort(port, callback, options = {}) {
        port &= 1;
        this._callbackPort[port] = callback;
        await this._armPort(port, options.intPin ?? this._conn.intPin ?? null);
    }

    /**
     * Unsubscribe and stop delivery on both ports.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        await this.offInterruptPort(0);
        await this.offInterruptPort(1);
        this._callbackBoth = null;
    }

    /**
     * Unsubscribe and stop delivery on a single port.
     * @param {number} port - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {Promise<void>}
     */
    async offInterruptPort(port) {
        port &= 1;
        await this._writeReg(REG_GPINTENA + port, 0x00);
        if (this._intPinUsed[port]) {
            await this._intPinUsed[port].offEdge(port === 0 ? this._edgeHandlerA : this._edgeHandlerB);
            this._intPinUsed[port] = null;
        }
        if (this._pollTimer[port]) { clearInterval(this._pollTimer[port]); this._pollTimer[port] = null; }
        this._callbackPort[port] = null;
    }

    async _handleEdge(port) {
        const status = await this.pollInterrupt(port);
        if (!status) return;
        if (this._callbackBoth) this._callbackBoth(port, status);
        if (this._callbackPort[port]) this._callbackPort[port](status);

        const raw = await this._readPort(port);
        for (const [bit, w] of Object.entries(this._watchers[port])) {
            if (!((status >> bit) & 1)) continue;
            const current = (raw >> bit) & 1;
            const rising = current === 1 && w.lastState === 0;
            const falling = current === 0 && w.lastState === 1;
            w.lastState = current;
            if (w.trigger === 'change' || (w.trigger === 'rising' && rising) || (w.trigger === 'falling' && falling)) {
                w.handler(this.pin(port * 8 + Number(bit)));
            }
        }
    }

    /**
     * Read & clear interrupt status; returns the raw INTF flag register.
     *
     * Reads INTFA/INTFB (which pins triggered) then INTCAPA/INTCAPB (which
     * clears the interrupt latch and re-arms it). Note that reading either
     * INTCAP or GPIO clears the interrupt condition on this chip — call
     * pollInterrupt() or readCapture() per event, not both.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {Promise<number>} 8-bit interrupt flag mask.
     */
    async pollInterrupt(port = 0) {
        port &= 1;
        const flags = await this._readReg(REG_INTFA + port);
        await this._readReg(REG_INTCAPA + port); // clears and re-arms the interrupt; value discarded
        return flags;
    }

    /**
     * Read INTCAP: the port state latched at the moment of the interrupt.
     *
     * Also clears the interrupt latch (see pollInterrupt() doc) — call this
     * instead of pollInterrupt() when the captured pin state is wanted
     * rather than the flag register.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {Promise<number>} 8-bit captured port bitmask at the moment of interrupt.
     */
    async readCapture(port = 0) {
        return this._readReg(REG_INTCAPA + (port & 1));
    }
}

/**
 * Full GPIO proxy — adds watch() for interrupt-driven input.
 */
class _FullPin extends _Pin {
    /**
     * @param {Mcp23017Full} chip - Parent full driver instance.
     * @param {number} n - Pin index (0–15).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        super(chip, n, direction);
    }

    /**
     * Subscribe to this pin's edge events.
     *
     * At most one handler per pin at a time; a second watch() call replaces
     * the first. Call the chip's onInterrupt()/onInterruptPort() (for this
     * pin's port) first to arm delivery.
     *
     * @param {function} handler - Called with this pin when its state matches trigger.
     * @param {string} [trigger='change'] - 'rising', 'falling', or 'change'.
     */
    watch(handler, trigger = 'change') {
        const port = this._n >> 3;
        const bit = this._n & 7;
        this._chip._watchers[port][bit] = { handler, trigger, lastState: 1 };
    }

    /** Unsubscribe this pin's handler. No-op if not registered. */
    unwatch() {
        const port = this._n >> 3;
        const bit = this._n & 7;
        delete this._chip._watchers[port][bit];
    }

    /**
     * Invert active-low polarity for this pin.
     * @param {boolean} invert - true to invert read/write values.
     */
    setActiveLow(invert) {
        this._activeLow = !!invert;
    }
}

module.exports = { Mcp23017Minimal, Mcp23017Full };
