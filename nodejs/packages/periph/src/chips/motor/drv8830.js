'use strict';

const _REG_CONTROL = 0x00;
const _REG_FAULT   = 0x01;

const _CTRL_IN1 = 0x01;
const _CTRL_IN2 = 0x02;

const _FAULT_FAULT  = 0x01;
const _FAULT_OCP    = 0x02;
const _FAULT_UVLO   = 0x04;
const _FAULT_OTS    = 0x08;
const _FAULT_ILIMIT = 0x10;
const _FAULT_CLEAR  = 0x80;

const _VREF     = 1.285;
const _VSET_MIN = 6;
const _VSET_MAX = 63;

const _DIRECTIONS = ['coast', 'forward', 'reverse', 'brake'];

/** Map |voltage| to a VSET code; 0 means coast (below the vset=6 floor). */
function _voltageToVset(voltage) {
    const vset = Math.floor(Math.abs(voltage) * 16 / _VREF + 0.5);
    if (vset < _VSET_MIN) return 0;
    if (vset > _VSET_MAX) return _VSET_MAX;
    return vset;
}

function _vsetToVoltage(vset) {
    if (vset < _VSET_MIN) return 0;
    return _VREF * vset / 16;
}

/**
 * DRV8830 low-voltage motor driver with I²C interface (Texas Instruments)
 * — minimal interface.
 *
 * H-bridge driver for a single brushed DC motor, controlled entirely over
 * I²C. The host commands a target output *voltage*; the chip PWM-regulates
 * the bridge to hold that average voltage regardless of supply sag. Nine
 * selectable addresses (0x60–0x68) via the tri-state A0/A1 strap pins.
 *
 * No register writes are needed at construction — the chip's POR default
 * already leaves the motor in standby/coast.
 */
class DRV8830Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x60–0x68).
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg & 0xFF, value & 0xFF]));
    }

    async _readReg(reg) {
        const buf = await this._conn.writeRead(Buffer.from([reg & 0xFF]), 1);
        return buf[0];
    }

    /**
     * Confirm the device answers on the bus. The DRV8830 has no
     * WHO_AM_I/identity register, so this is a plain CONTROL read; it makes
     * no register writes.
     * @returns {Promise<void>}
     */
    async init() {
        await this._readReg(_REG_CONTROL);
    }

    /**
     * Drive the motor at a regulated output voltage. Writes VSET and
     * IN1/IN2 together in one CONTROL write. A magnitude below the ~0.48 V
     * floor is treated as 0 V (coast); above ~5.06 V it is clamped.
     * @param {number} voltage - Signed target voltage in V: positive = forward, negative = reverse, 0 = coast.
     * @returns {Promise<void>}
     */
    async drive(voltage) {
        const vset = _voltageToVset(voltage);
        if (vset === 0) {
            await this._writeReg(_REG_CONTROL, 0x00);
        } else if (voltage > 0) {
            await this._writeReg(_REG_CONTROL, (vset << 2) | _CTRL_IN1);
        } else {
            await this._writeReg(_REG_CONTROL, (vset << 2) | _CTRL_IN2);
        }
    }

    /**
     * Short-brake the motor (IN1 = IN2 = 1, both outputs high).
     * @returns {Promise<void>}
     */
    async brake() {
        await this._writeReg(_REG_CONTROL, _CTRL_IN1 | _CTRL_IN2);
    }

    /**
     * Put the bridge in standby/coast (IN1 = IN2 = 0) — same as `drive(0)`.
     * @returns {Promise<void>}
     */
    async stop() {
        await this._writeReg(_REG_CONTROL, 0x00);
    }
}

/**
 * DRV8830 full interface — extends Minimal with raw CONTROL access, output
 * read-back, fault reporting/clearing, and the FAULTn interrupt API.
 *
 * Faults are never cleared implicitly: a latched OCP/ILIMIT fault also
 * disables the H-bridge, so clearing is always an explicit `clearFault()`.
 */
class DRV8830Full extends DRV8830Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x60–0x68).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    /**
     * Write the CONTROL register from raw fields.
     * @param {number} vset - VSET DAC code, 6–63 (0–5 are reserved).
     * @param {boolean} in1 - H-bridge input 1.
     * @param {boolean} in2 - H-bridge input 2.
     * @returns {Promise<void>}
     * @throws {RangeError} If vset is outside 6–63.
     */
    async setOutput(vset, in1, in2) {
        if (!Number.isInteger(vset) || vset < _VSET_MIN || vset > _VSET_MAX) {
            throw new RangeError('vset must be an integer 6-63');
        }
        await this._writeReg(_REG_CONTROL, (vset << 2) | (in1 ? _CTRL_IN1 : 0) | (in2 ? _CTRL_IN2 : 0));
    }

    /**
     * Read back and decode the CONTROL register.
     * @returns {Promise<{voltage: number, direction: ('forward'|'reverse'|'coast'|'brake')}>}
     *   voltage is the commanded magnitude in V (0 for a reserved VSET code).
     */
    async readOutput() {
        const ctrl = await this._readReg(_REG_CONTROL);
        return { voltage: _vsetToVoltage(ctrl >> 2), direction: _DIRECTIONS[ctrl & 0x03] };
    }

    /**
     * Read the FAULT register without clearing it.
     * @returns {Promise<{fault: boolean, ocp: boolean, uvlo: boolean, ots: boolean, ilimit: boolean}>}
     */
    async readFault() {
        const f = await this._readReg(_REG_FAULT);
        return {
            fault:  (f & _FAULT_FAULT) !== 0,
            ocp:    (f & _FAULT_OCP) !== 0,
            uvlo:   (f & _FAULT_UVLO) !== 0,
            ots:    (f & _FAULT_OTS) !== 0,
            ilimit: (f & _FAULT_ILIMIT) !== 0,
        };
    }

    /**
     * Clear all fault status bits (CLEAR = 1); re-enables the H-bridge if an
     * OCP/ILIMIT fault had latched it off.
     * @returns {Promise<void>}
     */
    async clearFault() {
        await this._writeReg(_REG_FAULT, _FAULT_CLEAR);
    }

    // -- Interrupt API (Level 1: FAULTn) ------------------------------------

    /**
     * Read the fault status — equivalent to `readFault()`, does not clear.
     * @returns {Promise<{fault: boolean, ocp: boolean, uvlo: boolean, ots: boolean, ilimit: boolean}>}
     */
    async pollInterrupt() {
        return this.readFault();
    }

    /**
     * Subscribe to fault interrupts. Uses `connection.intPin.onEdge()` (FAULTn
     * falling edge) when an INT-line InputPin is wired; otherwise falls back
     * to a 5 ms polling loop that fires on each new fault (FAULT bit rising).
     * The fault is not cleared.
     * @param {function({fault: boolean, ocp: boolean, uvlo: boolean, ots: boolean, ilimit: boolean}): void} callback
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this.pollInterrupt();
                if (status.fault && this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            let wasFault = false;
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this.pollInterrupt();
                    if (status.fault && !wasFault && this._callback) this._callback(status);
                    wasFault = status.fault;
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /**
     * Unsubscribe from fault interrupts.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

/** Default 7-bit I²C address (A0 = A1 = GND). Valid range 0x60–0x68. */
DRV8830Minimal.I2C_ADDRESS = 0x60;

module.exports = { DRV8830Minimal, DRV8830Full };
