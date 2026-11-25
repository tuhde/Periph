'use strict';

const REG_IODIR   = 0x00;
const REG_IPOL    = 0x01;
const REG_GPINTEN = 0x02;
const REG_DEFVAL  = 0x03;
const REG_INTCON  = 0x04;
const REG_IOCON   = 0x05;
const REG_GPPU    = 0x06;
const REG_INTF    = 0x07;
const REG_INTCAP  = 0x08;
const REG_GPIO    = 0x09;
const REG_OLAT    = 0x0A;

const IOCON_SEQOP  = 0x20;
const IOCON_HAEN   = 0x08;
const IOCON_ODR    = 0x04;
const IOCON_INTPOL = 0x02;

/**
 * Shared implementation of the MCP23008 (I²C) and MCP23S08 (SPI) drivers —
 * minimal interface. Use {@link Mcp23008Minimal} or {@link Mcp23S08Minimal}.
 *
 * Provides 8 bidirectional GPIO pins (GP0–GP7) via a pin() factory. Each
 * returned pin exposes async read()/write() (Connection is async everywhere,
 * so a synchronous pin proxy cannot return a value); pin.asGpio() gives a
 * synchronous opengpio-shaped facade. Shadows of OLAT and IODIR make a
 * single-pin change one register write.
 *
 * Subclasses implement `_regRead(reg, n)` and `_regWrite(reg, data)`; both wait
 * for the constructor's initialisation sequence (`this.ready`) to finish first.
 */
class Mcp23x08Minimal {
    constructor() {
        this._shadow    = 0x00;
        this._direction = 0xFF;
        this._iocon     = 0x00;
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    async _init() {
        await this._rawWrite(REG_OLAT, 0x00);
    }

    async _writeOlat(mask) {
        this._shadow = mask & 0xFF;
        await this._regWrite(REG_OLAT, this._shadow);
    }

    async _setPin(n, value) {
        if (value) this._shadow |= (1 << n);
        else       this._shadow &= ~(1 << n);
        await this._writeOlat(this._shadow);
    }

    async _setDirection(n, input) {
        if (input) {
            this._direction |= (1 << n);
        } else {
            // Latch the output level first so the pin never glitches.
            await this._regWrite(REG_OLAT, this._shadow);
            this._direction &= ~(1 << n);
        }
        await this._regWrite(REG_IODIR, this._direction);
    }

    /**
     * Return a Pin proxy for pin n (0–7). All eight pins are bidirectional.
     *
     * @param {number} n - Pin index (0–7).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy.
     */
    pin(n, direction = 'in') {
        return new _Pin(this, n, direction);
    }

    /**
     * Read all 8 pins as a bitmask (GPIO register, actual pin levels).
     *
     * @param {number} [port=0] - Ignored; the chip has a single port.
     * @returns {Promise<number>} 8-bit bitmask (bit 0 = GP0).
     */
    async readPort(port = 0) {
        return (await this._regRead(REG_GPIO, 1))[0];
    }

    /**
     * Write all 8 output bits via OLAT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param {number} [port=0] - Ignored; the chip has a single port.
     * @param {number} mask - 8-bit output mask.
     * @returns {Promise<void>}
     */
    async writePort(port = 0, mask = 0x00) {
        await this._writeOlat(mask);
    }
}

/**
 * GPIO proxy for a single pin. Obtain via pin(n); do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Mcp23x08Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–7).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n & 7;
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
     * Set pin direction (writes IODIR).
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
 * MCP23008 8-bit I/O port expander (I²C) — minimal interface.
 */
class Mcp23008Minimal extends Mcp23x08Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection.
     * @param {number} [addr=0x20] - 7-bit I²C address (0x20–0x27).
     */
    constructor(connection, addr = 0x20) {
        super();
        this._conn = connection;
        this._addr = addr;
        this._ready = this._init();
    }

    async _rawWrite(reg, value) { await this._conn.writeReg(reg, value & 0xFF); }

    async _regRead(reg, n) {
        await this._ready;
        return this._conn.readReg(reg, n);
    }

    async _regWrite(reg, value) {
        await this._ready;
        await this._rawWrite(reg, value);
    }
}

/**
 * MCP23S08 8-bit I/O port expander (SPI) — minimal interface.
 *
 * Every transaction is `[0x40 | (addr << 1) | R/W] [reg] [data…]`; the driver
 * builds the two header bytes itself and talks to a plain Connection
 * (`write` / `writeRead`). IOCON.HAEN is set during init so up to four devices
 * can share one chip select.
 */
class Mcp23S08Minimal extends Mcp23x08Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - SPI Connection (mode 0, MSB first).
     * @param {number} [addr=0] - Hardware device address 0–3 (A1:A0 pins).
     */
    constructor(connection, addr = 0) {
        super();
        this._conn = connection;
        this._addr = addr & 3;
        this._iocon = IOCON_HAEN;
        this._ready = this._initSpi();
    }

    async _initSpi() {
        // HAEN resets to 0, so an un-addressed device decodes address 00: send
        // the first IOCON write with the address-0 opcode.
        await this._conn.write(Buffer.from([0x40, REG_IOCON, IOCON_HAEN]));
        await this._init();
    }

    async _rawWrite(reg, value) {
        await this._conn.write(Buffer.from([0x40 | (this._addr << 1), reg, value & 0xFF]));
    }

    async _regRead(reg, n) {
        await this._ready;
        return this._conn.writeRead(Buffer.from([0x40 | (this._addr << 1) | 1, reg]), n);
    }

    async _regWrite(reg, value) {
        await this._ready;
        await this._rawWrite(reg, value);
    }
}

/**
 * Full-stage methods shared by Mcp23008Full and Mcp23S08Full (copied onto each
 * class's prototype).
 */
const FullMethods = {
    _initFull() {
        this._pullup   = 0x00;
        this._gpinten  = 0x00;
        this._activeHigh = false;
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._watchers = {};
        this._edgeHandler = () => { this._service(); };
    },

    /**
     * Return a Full Pin proxy for pin n (0–7).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
     */
    pin(n, direction = 'in') {
        return new _FullPin(this, n, direction);
    },

    /**
     * Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask). Only effective on inputs.
     * @param {number} mask - 8-bit mask: 1 = pull-up on.
     * @returns {Promise<void>}
     */
    async configurePullup(mask) {
        this._pullup = mask & 0xFF;
        await this._regWrite(REG_GPPU, this._pullup);
    },

    /**
     * Configure input polarity inversion (IPOL ← mask).
     * @param {number} mask - 8-bit mask: 1 = invert the GPIO read.
     * @returns {Promise<void>}
     */
    async configurePolarity(mask) {
        await this._regWrite(REG_IPOL, mask & 0xFF);
    },

    /**
     * Set DEFVAL for default-compare interrupts.
     * @param {number} mask - 8-bit default compare value.
     * @returns {Promise<void>}
     */
    async setDefaultValue(mask) {
        await this._regWrite(REG_DEFVAL, mask & 0xFF);
    },

    /**
     * Arm interrupt-on-change pins (GPINTEN ← mask). Only inputs can interrupt.
     * @param {number} mask - 8-bit mask: bit n arms GPn.
     * @returns {Promise<void>}
     */
    async enablePinInterrupt(mask) {
        this._gpinten = mask & 0xFF;
        await this._regWrite(REG_GPINTEN, this._gpinten);
    },

    /**
     * Configure the INT output (IOCON ODR / INTPOL).
     * @param {object} [options]
     * @param {boolean} [options.openDrain=false] - Open-drain INT (overrides activeHigh).
     * @param {boolean} [options.activeHigh=false] - Active-high when push-pull.
     * @returns {Promise<void>}
     */
    async configureIntPin(options = {}) {
        const openDrain = !!options.openDrain;
        const activeHigh = !!options.activeHigh;
        this._activeHigh = activeHigh && !openDrain;
        let iocon = this._iocon & ~(IOCON_ODR | IOCON_INTPOL);
        if (openDrain) iocon |= IOCON_ODR;
        else if (activeHigh) iocon |= IOCON_INTPOL;
        this._iocon = iocon;
        await this._regWrite(REG_IOCON, iocon);
    },

    /**
     * Enable or disable sequential register access (IOCON SEQOP). The driver
     * itself never relies on a value other than enabled.
     * @param {boolean} enabled - true (default, auto-increment) or false.
     * @returns {Promise<void>}
     */
    async setSequential(enabled) {
        let iocon = this._iocon & ~IOCON_SEQOP;
        if (!enabled) iocon |= IOCON_SEQOP;
        this._iocon = iocon;
        await this._regWrite(REG_IOCON, iocon);
    },

    /**
     * Raw register read (debug / escape hatch).
     * @param {number} reg - Register address 0x00–0x0A.
     * @returns {Promise<number>}
     */
    async readRegister(reg) {
        return (await this._regRead(reg, 1))[0];
    },

    /**
     * Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
     * @param {number} reg - Register address 0x00–0x0A.
     * @param {number} value - 8-bit value.
     * @returns {Promise<void>}
     */
    async writeRegister(reg, value) {
        value &= 0xFF;
        if (reg === REG_IODIR) this._direction = value;
        else if (reg === REG_OLAT) this._shadow = value;
        else if (reg === REG_IOCON) this._iocon = value;
        else if (reg === REG_GPPU) this._pullup = value;
        else if (reg === REG_GPINTEN) this._gpinten = value;
        await this._regWrite(reg, value);
    },

    /**
     * Rewrite every register to its reset value and clear the shadows. The chip
     * has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
     * rewritten to HAEN (not 0) so addressed access keeps working.
     * @returns {Promise<void>}
     */
    async reset() {
        await this.offInterrupt();
        for (const reg of [REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT]) {
            await this._regWrite(reg, 0x00);
        }
        await this._regWrite(REG_IODIR, 0xFF);
        this._iocon = this._baseIocon();
        await this._regWrite(REG_IOCON, this._iocon);
        this._shadow = 0x00;
        this._direction = 0xFF;
        this._pullup = 0x00;
        this._gpinten = 0x00;
        this._activeHigh = false;
    },

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, this._activeHigh ? 'rising' : 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 5);
        }
    },

    /**
     * Subscribe to INT assertions. `callback(status)` receives the INTF mask of
     * the pins that caused the interrupt. Every pin armed via
     * enablePinInterrupt() or watch() is used; delivery is via the INT line
     * (options.intPin or connection.intPin) or a 5 ms polling timer.
     *
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @param {string} [options.mode='change'] - 'change' (INTCON = 0) or 'default' (compare against DEFVAL).
     * @returns {Promise<void>}
     * @throws {Error} if no pin has been armed.
     */
    async onInterrupt(callback, options = {}) {
        if (!this._gpinten) throw new Error('no pins armed: call enablePinInterrupt() first');
        this._callback = callback;
        await this._regWrite(REG_INTCON, options.mode === 'default' ? this._gpinten : 0x00);
        await this._arm(options.intPin ?? this._conn.intPin ?? null);
    },

    /**
     * Unsubscribe and stop delivery (GPINTEN is left untouched).
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    },

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = (await this._regRead(REG_INTF, 1))[0];
            if (!status) return;
            const captured = (await this._regRead(REG_INTCAP, 1))[0]; // clears the interrupt
            if (this._callback) this._callback(status);
            for (const [bit, w] of Object.entries(this._watchers)) {
                if (!((status >> bit) & 1)) continue;
                const current = (captured >> bit) & 1;
                if (w.trigger === 'change' || (w.trigger === 'rising' && current === 1) ||
                    (w.trigger === 'falling' && current === 0)) {
                    w.handler(this.pin(Number(bit)));
                }
            }
        } finally {
            this._polling = false;
        }
    },

    /**
     * Read INTF, then INTCAP (clearing the interrupt). The captured port value
     * is discarded — use readCapture().
     * @returns {Promise<number>} INTF bitmask.
     */
    async pollInterrupt() {
        const flags = (await this._regRead(REG_INTF, 1))[0];
        await this._regRead(REG_INTCAP, 1);
        return flags;
    },

    /**
     * Read INTCAP: the port state captured at the interrupt. Also clears the interrupt.
     * @returns {Promise<number>} 8-bit captured port value.
     */
    async readCapture() {
        return (await this._regRead(REG_INTCAP, 1))[0];
    },
};

/**
 * Full GPIO proxy — adds watch()/unwatch() for interrupt-driven input.
 */
class _FullPin extends _Pin {
    /**
     * Subscribe to this pin's edge events (sets its GPINTEN bit).
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
        await this._chip.enablePinInterrupt(this._chip._gpinten | (1 << this._n));
    }

    /**
     * Unsubscribe this pin's handler and clear its GPINTEN bit.
     * @returns {Promise<void>}
     */
    async unwatch() {
        delete this._chip._watchers[this._n];
        await this._chip.enablePinInterrupt(this._chip._gpinten & ~(1 << this._n));
    }

    /**
     * Invert active-low polarity for this pin (IPOL bit).
     * @param {boolean} invert - true to invert the read value.
     * @returns {Promise<void>}
     */
    async setActiveLow(invert) {
        this._activeLow = !!invert;
        const ipol = (await this._chip.readRegister(REG_IPOL) & ~(1 << this._n)) | (invert ? (1 << this._n) : 0);
        await this._chip.configurePolarity(ipol);
    }
}

/** MCP23008 full interface (I²C) — pull-ups, polarity, interrupts, raw access. */
class Mcp23008Full extends Mcp23008Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection.
     * @param {number} [addr=0x20] - 7-bit I²C address.
     */
    constructor(connection, addr = 0x20) {
        super(connection, addr);
        this._initFull();
    }

    _baseIocon() { return 0x00; }
}

/** MCP23S08 full interface (SPI) — pull-ups, polarity, interrupts, raw access. */
class Mcp23S08Full extends Mcp23S08Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - SPI Connection (mode 0, MSB first).
     * @param {number} [addr=0] - Hardware device address 0–3 (A1:A0 pins).
     */
    constructor(connection, addr = 0) {
        super(connection, addr);
        this._initFull();
    }

    _baseIocon() { return IOCON_HAEN; }
}

for (const Cls of [Mcp23008Full, Mcp23S08Full]) {
    Object.defineProperties(Cls.prototype, Object.getOwnPropertyDescriptors(FullMethods));
}

module.exports = { Mcp23008Minimal, Mcp23008Full, Mcp23S08Minimal, Mcp23S08Full };
