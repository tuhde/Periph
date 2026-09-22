'use strict';

/**
 * TPIC6B595 8-bit power SIPO shift register — minimal interface.
 *
 * Drives up to {@code numDevices} cascaded TPIC6B595s through a SiPo
 * (serial-in/parallel-out) connection. Each device exposes 8 open-drain
 * outputs (DRAIN0–DRAIN7); every write shifts the entire cascade MSB-first
 * and pulses RCK to latch all outputs atomically. Outputs only sink current
 * — they never source it; an external pull-up or load supply is required
 * for the "off"/high state.
 *
 * The driver owns a {@code numDevices}-byte shadow register so single-pin
 * updates work without re-reading the bus. Every write (pin, port, fill)
 * rebuilds and retransmits the entire reversed cascade — see
 * {@code specs/io_expander/tpic6b595.md} for the wire-order reversal that
 * cascading requires.
 *
 * Initialises every output to OFF at construction (shadow zero, latched
 * once). If the SiPo connection has SRCLR wired, the constructor pulses
 * it to clear the shift register before the all-zero latch.
 */
class Tpic6b595Minimal {
    /**
     * @param {import('../../connection/sipo').SiPoConnection} connection - Configured SiPo connection.
     * @param {number} [numDevices=1] - Number of cascaded TPIC6B595s on the wire.
     */
    constructor(connection, numDevices = 1) {
        this._conn = connection;
        this._numDevices = numDevices;
        this._shadow = new Uint8Array(numDevices);
        try { this._conn.clear(); } catch (_) { /* SRCLR optional */ }
        this._flush();
    }

    _flush() {
        const wire = Buffer.alloc(this._numDevices);
        for (let i = 0; i < this._numDevices; i++) {
            wire[i] = this._shadow[this._numDevices - 1 - i];
        }
        this._conn.write(wire);
    }

    async _setPin(n, value) {
        const port = Math.floor(n / 8);
        const bit = n % 8;
        if (value) this._shadow[port] |=  (1 << bit);
        else       this._shadow[port] &= ~(1 << bit);
        this._flush();
    }

    /**
     * Return a Pin proxy object for global pin number {@code n}.
     * @param {number} n - Pin index, 0 (DRAIN0 of the device nearest the controller) to {@code numDevices * 8 - 1}.
     * @returns {_Pin} Pin proxy.
     */
    pin(n) {
        return new _Pin(this, n);
    }

    /**
     * Write all 8 outputs of cascaded device {@code port} from {@code mask}.
     *
     * Updates the shadow register for the targeted port, rebuilds the
     * reversed cascade, shifts it out, and pulses RCK to latch every
     * cascaded device's outputs.
     *
     * @param {number} port - Cascaded device index (0 = nearest the controller).
     * @param {number} mask - 8-bit output mask. Bit 0 = DRAIN0, bit 7 = DRAIN7.
     *   1 = ON (DMOS conducting, sinks current); 0 = OFF (high-impedance).
     * @returns {Promise<void>}
     */
    async writePort(port, mask) {
        this._shadow[port] = mask & 0xFF;
        this._flush();
    }

    /**
     * Set every pin on every cascaded device to {@code value}.
     *
     * Sends immediately — the fast path for "all on"/"all off".
     *
     * @param {boolean} value - true to turn every output ON, false to turn them OFF.
     * @returns {Promise<void>}
     */
    async fill(value) {
        const b = value ? 0xFF : 0x00;
        for (let i = 0; i < this._numDevices; i++) this._shadow[i] = b;
        this._flush();
    }

    /**
     * Turn every output off (equivalent to {@code fill(false)}).
     * @returns {Promise<void>}
     */
    async off() { return this.fill(false); }
}

/**
 * GPIO proxy for a single TPIC6B595 pin — output-only.
 *
 * Obtain via {@code Tpic6b595Minimal.pin(n)}. Do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Tpic6b595Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0 to {@code numDevices * 8 - 1}).
     */
    constructor(chip, n) {
        this._chip = chip;
        this._n = n;
    }

    /**
     * Read pin shadow bit (NOT a bus read — SiPo is write-only).
     * @returns {Promise<number>} 0 or 1.
     */
    async read() {
        const port = Math.floor(this._n / 8);
        const bit  = this._n % 8;
        return (this._chip._shadow[port] >> bit) & 1;
    }

    /**
     * Write pin.
     * @param {number} value - 0 (DMOS OFF, high-impedance) or 1 (DMOS ON, sinks current).
     * @returns {Promise<void>}
     */
    async write(value) {
        await this._chip._setPin(this._n, value ? 1 : 0);
    }

    /** Set DMOS output ON (sink current through the external load). */
    async on() { await this._chip._setPin(this._n, 1); }

    /** Set DMOS output OFF (high-impedance). */
    async off() { await this._chip._setPin(this._n, 0); }

    /** Invert the shadow bit for this pin. */
    async toggle() {
        const port = Math.floor(this._n / 8);
        const bit  = this._n % 8;
        const cur = (this._chip._shadow[port] >> bit) & 1;
        await this._chip._setPin(this._n, 1 - cur);
    }

    /**
     * OutputPin interface — satisfies the project's OutputPin contract.
     * @param {boolean} high - true = ON, false = OFF.
     */
    async set(high) { await this._chip._setPin(this._n, high ? 1 : 0); }

    /**
     * Return a synchronous opengpio-shaped Output facade for this pin —
     * a boolean `.value` getter/setter and `stop()` — so it can be passed
     * anywhere real opengpio GPIO is expected.
     *
     * The facade reads back the shadow register directly (authoritative —
     * there is no bus round-trip to be eventually-consistent about).
     *
     * @returns {{direction: string, value: boolean, stop: function}} opengpio-shaped facade.
     */
    asGpio() {
        const pin = this;
        const port = Math.floor(this._n / 8);
        const bit  = this._n % 8;
        return {
            get direction() { return 'out'; },
            get value() { return ((pin._chip._shadow[port] >> bit) & 1) === 1; },
            set value(v) { pin._chip._setPin(pin._n, v ? 1 : 0); }, // fire-and-forget
            stop() {},
        };
    }

    /** Release the pin (no-op; shadow state preserved). */
    stop() {}
}

/**
 * TPIC6B595 full interface — extends Tpic6b595Minimal with hardware features.
 *
 * Adds {@code clear()}, {@code setOutputEnable()}, and {@code writeAll()} for
 * the shift-register-clear and output-enable hardware lines, plus a bulk
 * multi-device write. Pin API surface stays exactly Minimal's — every
 * TPIC6B595 pin is a fixed, capability-less output.
 */
class Tpic6b595Full extends Tpic6b595Minimal {
    /**
     * @param {import('../../connection/sipo').SiPoConnection} connection - Configured SiPo connection.
     * @param {number} [numDevices=1] - Number of cascaded TPIC6B595s on the wire.
     */
    constructor(connection, numDevices = 1) {
        super(connection, numDevices);
    }

    /**
     * Pulse SRCLR to clear the shift register only.
     *
     * The storage register (and therefore the DRAIN outputs) keeps its
     * last-latched value until the next RCK pulse. To actually blank the
     * outputs, call {@code off()} or {@code fill(false)} afterwards.
     *
     * @returns {void}
     * @throws {Error} If SRCLR was not wired on the SiPo connection.
     */
    clear() { this._conn.clear(); }

    /**
     * Drive G LOW ({@code enabled=true}) or HIGH ({@code enabled=false}).
     *
     * {@code enabled=true} lets the storage register drive the outputs.
     * {@code enabled=false} forces every output off without disturbing
     * the shadow register or the storage register's contents — the
     * datasheet's documented use case for global PWM-style dimming.
     *
     * @param {boolean} enabled - true to enable, false to disable.
     * @returns {void}
     * @throws {Error} If G was not wired on the SiPo connection.
     */
    setOutputEnable(enabled) { this._conn.setOutputEnable(enabled); }

    /**
     * Write every cascaded device's byte in one call.
     *
     * Updates the whole shadow register and performs exactly one transmit
     * + latch. {@code values} is length-truncated or zero-extended to
     * {@code numDevices} as needed.
     *
     * @param {number[]|Buffer|Uint8Array} values - One byte per cascaded device.
     *   Index 0 is the byte for cascaded device 0 (nearest the controller).
     * @returns {void}
     */
    writeAll(values) {
        for (let i = 0; i < this._numDevices; i++) {
            const v = i < values.length ? values[i] : 0;
            this._shadow[i] = v & 0xFF;
        }
        this._flush();
    }
}

module.exports = { Tpic6b595Minimal, Tpic6b595Full };
