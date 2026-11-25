'use strict';

/**
 * MCP4XXX family — MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X I²C digital
 * potentiometers (Microchip).
 *
 * One driver for all sixteen parts. A part descriptor (`PARTS`) carries the channel count,
 * resolution (7-bit: max code 128, 8-bit: 256), pot / rheostat pinout, memory type and the
 * I²C address range; everything else is one code path. A transaction starts with a command
 * byte `AD3 AD2 AD1 AD0 C1 C0 D9 D8` that plays the role of the register address of the
 * RegisterConnection; a read returns two bytes (`0000 000D8`, `D7..D0`).
 */

const X1 = [0x2E, 0x2F];
const X2 = [0x58, 0x5B];
const P46X1 = [0x28, 0x2F];

/** Part descriptor table keyed by upper-case part name. */
const PARTS = {
    MCP4531: { channels: 1, maxCode: 128, pot: true,  nv: false, wpPin: false, addr: X1 },
    MCP4532: { channels: 1, maxCode: 128, pot: false, nv: false, wpPin: false, addr: X2 },
    MCP4551: { channels: 1, maxCode: 256, pot: true,  nv: false, wpPin: false, addr: X1 },
    MCP4552: { channels: 1, maxCode: 256, pot: false, nv: false, wpPin: false, addr: X2 },
    MCP4631: { channels: 2, maxCode: 128, pot: true,  nv: false, wpPin: false, addr: P46X1 },
    MCP4632: { channels: 2, maxCode: 128, pot: false, nv: false, wpPin: false, addr: X2 },
    MCP4651: { channels: 2, maxCode: 256, pot: true,  nv: false, wpPin: false, addr: P46X1 },
    MCP4652: { channels: 2, maxCode: 256, pot: false, nv: false, wpPin: false, addr: X2 },
    MCP4541: { channels: 1, maxCode: 128, pot: true,  nv: true,  wpPin: false, addr: X1 },
    MCP4542: { channels: 1, maxCode: 128, pot: false, nv: true,  wpPin: false, addr: X2 },
    MCP4561: { channels: 1, maxCode: 256, pot: true,  nv: true,  wpPin: false, addr: X1 },
    MCP4562: { channels: 1, maxCode: 256, pot: false, nv: true,  wpPin: false, addr: X2 },
    MCP4641: { channels: 2, maxCode: 128, pot: true,  nv: true,  wpPin: true,  addr: P46X1 },
    MCP4642: { channels: 2, maxCode: 128, pot: false, nv: true,  wpPin: false, addr: X2 },
    MCP4661: { channels: 2, maxCode: 256, pot: true,  nv: true,  wpPin: true,  addr: P46X1 },
    MCP4662: { channels: 2, maxCode: 256, pot: false, nv: true,  wpPin: false, addr: X2 },
};

const ADDR_WIPER0 = 0x00;
const ADDR_NVWIPER0 = 0x02;
const ADDR_TCON = 0x04;
const ADDR_STATUS = 0x05;
const ADDR_DATA0 = 0x06;
const ADDR_WP = 0x0F;

const OP_WRITE = 0x00;
const OP_INC = 0x04;
const OP_DEC = 0x08;
const OP_READ = 0x0C;

const TCON_DEFAULT = 0x1FF;
// Per-channel TCON bit positions: [HW, A, W, B].
const TCON_BITS = [[3, 2, 1, 0], [7, 6, 5, 4]];

const STATUS_EEWA = 0x08;
const STATUS_WL = [0x02, 0x04];
const STATUS_WP = 0x01;

const delayMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function descriptor(part) {
    const d = PARTS[String(part).toUpperCase()];
    if (!d) throw new RangeError('MCP4XXX: unknown part ' + part);
    return d;
}

/**
 * Default 7-bit I²C address (all address pins low) of a part.
 * @param {string} part - Part name, e.g. `'MCP4532'` (case-insensitive).
 * @returns {number} `0x2E` (45X1), `0x58` (45X2 / 46X2) or `0x28` (46X1).
 */
function defaultAddress(part) {
    return descriptor(part).addr[0];
}

/**
 * MCP4XXX family — minimal interface: set and read the volatile wiper or the W–B resistance.
 * Never touches the EEPROM.
 *
 * Default behaviour: part MCP4532, RAB = 10 kΩ, RW = 75 Ω; wipers keep their power-up value
 * until written; all terminals connected.
 */
class Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection pointing at the device.
     * @param {string} [part='MCP4532'] - Part name `'MCP4531'` … `'MCP4662'` (case-insensitive); an unknown part throws.
     * @param {number} [rAb=10000] - End-to-end resistance RAB in ohms (5 / 10 / 50 / 100 kΩ).
     * @param {number} [rWiper=75] - Wiper resistance RW in ohms.
     */
    constructor(connection, part = 'MCP4532', rAb = 10000.0, rWiper = 75.0) {
        const d = descriptor(part);
        this._conn = connection;
        this._part = String(part).toUpperCase();
        this._channels = d.channels;
        this._maxCode = d.maxCode;
        this._pot = d.pot;
        this._nv = d.nv;
        this._rAb = rAb;
        this._rWiper = rWiper;
    }

    /**
     * Bus check: reads the wiper register(s), `TCON` (and `STATUS` on non-volatile parts). Performs no writes.
     * @returns {Promise<void>}
     */
    async init() {
        for (let c = 0; c < this._channels; c++) await this._readWiper(c);
        await this._readRegister(ADDR_TCON);
        if (this._nv) await this._readRegister(ADDR_STATUS);
    }

    _checkChannel(channel) {
        if (!Number.isInteger(channel) || channel < 0 || channel >= this._channels) {
            throw new RangeError('MCP4XXX: channel out of range for ' + this._part);
        }
    }

    async _readRegister(address) {
        const d = await this._conn.readReg((address << 4) | OP_READ, 2);
        return ((d[0] & 0x01) << 8) | d[1];
    }

    async _writeRegister(address, value) {
        await this._conn.writeReg((address << 4) | OP_WRITE | ((value >> 8) & 0x01), [value & 0xFF]);
    }

    async _readWiper(channel) {
        return Math.min(await this._readRegister(ADDR_WIPER0 + channel), this._maxCode);
    }

    _clamp(code) {
        return Math.max(0, Math.min(this._maxCode, Math.round(code)));
    }

    /**
     * Set the volatile wiper position of a network (clamped to [0, max code]; a WiperLock-ed network ignores it).
     * @param {number} code - Wiper position (0 = W at B, max code 128 / 256 = W at A).
     * @param {number} [channel=0] - Network index, 0 or 1 (dual parts only).
     * @returns {Promise<void>}
     */
    async setWiper(code, channel = 0) {
        this._checkChannel(channel);
        await this._writeRegister(ADDR_WIPER0 + channel, this._clamp(code));
    }

    /**
     * Read the volatile wiper position of a network.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} Wiper position 0 … max code.
     */
    async getWiper(channel = 0) {
        this._checkChannel(channel);
        return this._readWiper(channel);
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     * @param {number} ohms - Target wiper-to-B resistance in ohms (clamped).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setResistance(ohms, channel = 0) {
        this._checkChannel(channel);
        await this.setWiper(Math.round((ohms - this._rWiper) / this._rAb * this._maxCode), channel);
    }

    /**
     * Read the W–B resistance for the current wiper position.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} Resistance in ohms, N / maxCode × RAB + RW.
     */
    async getResistance(channel = 0) {
        return (await this.getWiper(channel)) / this._maxCode * this._rAb + this._rWiper;
    }
}

/**
 * MCP4XXX family — full interface: ratio helpers, increment / decrement, shutdown and
 * terminal control, General Call, reset, raw access and (non-volatile parts only, marked
 * **NV**) EEPROM wiper, general-purpose EEPROM, STATUS, WiperLock and write protect.
 * NV operations reject with a RangeError on the volatile parts.
 */
class Mcp4xxxFull extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {string} [part='MCP4532'] - Part name.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional pin switching an external circuit that drives HVC/A0 to 8.5–12.5 V (needed only by setWiperLock / setWriteProtect).
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, part = 'MCP4532', rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, part, rAb, rWiper);
        this._hvc = this._nv ? hvc : null;
        this._hvcSettleMs = hvcSettleMs;
    }

    _requireNv() {
        if (!this._nv) throw new RangeError('MCP4XXX: ' + this._part + ' has no EEPROM');
    }

    _requirePot() {
        if (!this._pot) throw new RangeError('MCP4XXX: ' + this._part + ' is a rheostat (terminal A not available)');
    }

    /** @returns {number} Number of resistor networks (1 or 2). */
    channels() { return this._channels; }

    /** @returns {number} Number of wiper positions (129 or 257). */
    steps() { return this._maxCode + 1; }

    /** @returns {boolean} True for the X1 parts (terminal A on a pin). */
    isPotentiometer() { return this._pot; }

    /** @returns {boolean} True for the 454X / 456X / 464X / 466X parts. */
    isNonvolatile() { return this._nv; }

    /**
     * Set the wiper to a fraction of the full scale.
     * @param {number} ratio - 0.0–1.0.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setRatio(ratio, channel = 0) {
        await this.setWiper(Math.round(Math.max(0, Math.min(1, ratio)) * this._maxCode), channel);
    }

    /**
     * Read the wiper position as a ratio.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} code / maxCode.
     */
    async getRatio(channel = 0) {
        return (await this.getWiper(channel)) / this._maxCode;
    }

    /**
     * Read the W–A resistance (potentiometer parts only).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} (maxCode − N) / maxCode × RAB + RW in ohms.
     */
    async getResistanceWa(channel = 0) {
        this._requirePot();
        return (this._maxCode - await this.getWiper(channel)) / this._maxCode * this._rAb + this._rWiper;
    }

    /**
     * Set the W–A resistance to the nearest reachable value (potentiometer parts only).
     * @param {number} ohms - Target wiper-to-A resistance in ohms.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setResistanceWa(ohms, channel = 0) {
        this._requirePot();
        this._checkChannel(channel);
        await this.setWiper(this._maxCode - Math.round((ohms - this._rWiper) / this._rAb * this._maxCode), channel);
    }

    /** @returns {number} Resistance of one step, RAB / maxCode, in ohms. */
    getResistanceStep() { return this._rAb / this._maxCode; }

    /**
     * Write both wipers of a dual part (two transactions).
     * @param {number} code0 - Network 0 wiper position.
     * @param {number} code1 - Network 1 wiper position.
     * @returns {Promise<void>}
     */
    async setBoth(code0, code1) {
        if (this._channels < 2) throw new RangeError('MCP4XXX: ' + this._part + ' has a single network');
        await this.setWiper(code0, 0);
        await this.setWiper(code1, 1);
    }

    async _step(op, steps, channel) {
        this._checkChannel(channel);
        const n = Math.trunc(steps);
        if (n < 1) return this.getWiper(channel);
        const command = ((ADDR_WIPER0 + channel) << 4) | op;
        // Continuous mode: one command byte per step, no data bytes, one transaction.
        await this._conn.writeReg(command, new Array(n - 1).fill(command));
        return this.getWiper(channel);
    }

    /**
     * Increment the wiper by `steps` in one continuous transaction (saturates at the max code).
     * @param {number} [steps=1] - Number of increment commands.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} New wiper code (read back).
     */
    async increment(steps = 1, channel = 0) { return this._step(OP_INC, steps, channel); }

    /**
     * Decrement the wiper by `steps` in one continuous transaction (saturates at 0).
     * @param {number} [steps=1] - Number of decrement commands.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} New wiper code (read back).
     */
    async decrement(steps = 1, channel = 0) { return this._step(OP_DEC, steps, channel); }

    async _modifyTcon(changes) {
        let value = await this._readRegister(ADDR_TCON);
        for (const [bit, on] of changes) value = on ? (value | (1 << bit)) : (value & ~(1 << bit));
        await this._writeRegister(ADDR_TCON, value & 0x1FF);
    }

    /**
     * Shut a network down (TCON.RxHW = 0): A disconnected, W connected to B; the wiper is kept.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async shutdown(channel = 0) {
        this._checkChannel(channel);
        await this._modifyTcon([[TCON_BITS[channel][0], false]]);
    }

    /**
     * Leave shutdown (TCON.RxHW = 1).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async resume(channel = 0) {
        this._checkChannel(channel);
        await this._modifyTcon([[TCON_BITS[channel][0], true]]);
    }

    /**
     * Whether the network is in shutdown (read from TCON).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<boolean>}
     */
    async isShutdown(channel = 0) {
        this._checkChannel(channel);
        return ((await this._readRegister(ADDR_TCON)) >> TCON_BITS[channel][0] & 1) === 0;
    }

    /**
     * Connect or disconnect the A, W and B terminals of a network (TCON.RxA / RxW / RxB).
     * @param {boolean} [a=true] - Connect terminal A.
     * @param {boolean} [w=true] - Connect the wiper terminal.
     * @param {boolean} [b=true] - Connect terminal B.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setTerminals(a = true, w = true, b = true, channel = 0) {
        this._checkChannel(channel);
        const bits = TCON_BITS[channel];
        await this._modifyTcon([[bits[1], a], [bits[2], w], [bits[3], b]]);
    }

    /**
     * Read the terminal connection state of a network from TCON.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<{a: boolean, w: boolean, b: boolean}>}
     */
    async getTerminals(channel = 0) {
        this._checkChannel(channel);
        const tcon = await this._readRegister(ADDR_TCON);
        const bits = TCON_BITS[channel];
        return { a: ((tcon >> bits[1]) & 1) === 1, w: ((tcon >> bits[2]) & 1) === 1, b: ((tcon >> bits[3]) & 1) === 1 };
    }

    /** @returns {Promise<number>} Raw 9-bit TCON register. */
    async getTcon() { return this._readRegister(ADDR_TCON); }

    /**
     * Enable or disable the General Call address (TCON.GCEN).
     * @param {boolean} enabled - True to answer General Call (address 0x00) commands.
     * @returns {Promise<void>}
     */
    async setGeneralCall(enabled) { await this._modifyTcon([[8, !!enabled]]); }

    /**
     * Emulate a power-on reset: write every volatile wiper with its power-up value (mid-scale, or
     * the EEPROM wiper on non-volatile parts) and TCON 0x1FF; networks locked by WiperLock are skipped.
     * @returns {Promise<void>}
     */
    async reset() {
        const locked = this._nv ? (await this.readStatus()).wiperLock : new Array(this._channels).fill(false);
        for (let c = 0; c < this._channels; c++) {
            if (locked[c]) continue;
            await this.setWiper(this._nv ? await this.getNvWiper(c) : this._maxCode / 2, c);
        }
        if (!locked.some((l) => l)) await this._writeRegister(ADDR_TCON, TCON_DEFAULT);
    }

    _checkAddress(address, writable) {
        if (!Number.isInteger(address) || address < 0 || address > 0x0F) throw new RangeError('MCP4XXX: address out of range');
        let ok = address === ADDR_TCON || address < this._channels;
        if (this._nv) {
            ok = ok || ((address === 2 || address === 3) && address - 2 < this._channels) || address >= ADDR_DATA0;
            if (address === ADDR_STATUS) ok = !writable;
        }
        if (!ok) throw new RangeError('MCP4XXX: address 0x' + address.toString(16) + ' not available on ' + this._part);
    }

    /**
     * Raw 9-bit read of a memory address the part has.
     * @param {number} address - Memory address 0–15.
     * @returns {Promise<number>} 9-bit value.
     */
    async readRegister(address) {
        this._checkAddress(address, false);
        return this._readRegister(address);
    }

    /**
     * Raw write of a writable memory address the part has.
     * @param {number} address - Memory address (volatile wipers, TCON; NV wipers and DATA on NV parts).
     * @param {number} value - Value (0–0x3FF for wipers, 0–0x1FF otherwise).
     * @returns {Promise<void>}
     */
    async writeRegister(address, value) {
        this._checkAddress(address, true);
        const limit = address < 2 ? 0x3FF : 0x1FF;
        await this._writeRegister(address, Math.max(0, Math.min(limit, Math.trunc(value))));
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     * @param {number} rAb - RAB in ohms.
     * @param {number} [rWiper] - RW in ohms; unchanged if omitted.
     */
    setRab(rAb, rWiper) {
        this._rAb = rAb;
        if (rWiper !== undefined && rWiper !== null) this._rWiper = rWiper;
    }

    /**
     * **NV.** Read STATUS.
     * @returns {Promise<{eepromBusy: boolean, wiperLock: boolean[], writeProtect: boolean}>}
     */
    async readStatus() {
        this._requireNv();
        const v = await this._readRegister(ADDR_STATUS);
        const wiperLock = [];
        for (let c = 0; c < this._channels; c++) wiperLock.push((v & STATUS_WL[c]) !== 0);
        return { eepromBusy: (v & STATUS_EEWA) !== 0, wiperLock, writeProtect: (v & STATUS_WP) !== 0 };
    }

    /**
     * **NV.** Poll STATUS.EEWA until the EEPROM write cycle has finished.
     * @param {number} [timeoutMs=20] - Maximum wait in ms (tWC is 5 ms typical, 10 ms maximum).
     * @returns {Promise<void>}
     * @throws {Error} If the cycle does not finish in time.
     */
    async waitReady(timeoutMs = 20) {
        this._requireNv();
        for (let i = 0; i <= Math.trunc(timeoutMs); i++) {
            if (((await this._readRegister(ADDR_STATUS)) & STATUS_EEWA) === 0) return;
            await delayMs(1);
        }
        throw new Error('MCP4XXX: EEPROM write did not finish');
    }

    /**
     * **NV.** Read the EEPROM wiper (power-up value).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} Stored code, clamped to the max code.
     */
    async getNvWiper(channel = 0) {
        this._requireNv();
        this._checkChannel(channel);
        return Math.min(await this._readRegister(ADDR_NVWIPER0 + channel), this._maxCode);
    }

    /**
     * **NV.** Write the EEPROM wiper; the volatile wiper is not changed.
     * @param {number} code - Power-up code (clamped).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setNvWiper(code, channel = 0) {
        this._requireNv();
        this._checkChannel(channel);
        await this._writeRegister(ADDR_NVWIPER0 + channel, this._clamp(code));
        await this.waitReady();
    }

    /**
     * **NV.** Write the current volatile wiper to the EEPROM wiper.
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<number>} The stored code.
     */
    async storeWiper(channel = 0) {
        this._requireNv();
        const code = await this.getWiper(channel);
        await this.setNvWiper(code, channel);
        return code;
    }

    /**
     * **NV.** Read general-purpose EEPROM register `index`.
     * @param {number} index - 0–9.
     * @returns {Promise<number>} 9-bit value.
     */
    async readData(index) {
        this._requireNv();
        if (!Number.isInteger(index) || index < 0 || index > 9) throw new RangeError('MCP4XXX: data index must be 0-9');
        return this._readRegister(ADDR_DATA0 + index);
    }

    /**
     * **NV.** Write general-purpose EEPROM register `index` (uses one write cycle).
     * @param {number} index - 0–9.
     * @param {number} value - 9-bit value 0–0x1FF.
     * @returns {Promise<void>}
     */
    async writeData(index, value) {
        this._requireNv();
        if (!Number.isInteger(index) || index < 0 || index > 9) throw new RangeError('MCP4XXX: data index must be 0-9');
        await this._writeRegister(ADDR_DATA0 + index, Math.max(0, Math.min(0x1FF, Math.trunc(value))));
        await this.waitReady();
    }

    /**
     * **NV.** Whether WiperLock is active for a network (STATUS.WLx).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<boolean>}
     */
    async isWiperLocked(channel = 0) {
        this._checkChannel(channel);
        return (await this.readStatus()).wiperLock[channel];
    }

    /** **NV.** @returns {Promise<boolean>} STATUS.WP (hardware WP pin OR the nonvolatile bit). */
    async isWriteProtected() { return (await this.readStatus()).writeProtect; }

    async _hvCommand(address, decrement) {
        if (!this._hvc) throw new RangeError('MCP4XXX: a high-voltage command needs the hvc pin');
        const command = (address << 4) | (decrement ? OP_DEC : OP_INC);
        await this._hvc.set(true);
        try {
            await delayMs(this._hvcSettleMs);
            await this._conn.writeReg(command, []);
        } finally {
            await this._hvc.set(false);
        }
        await this.waitReady();
    }

    /**
     * **NV, needs `hvc`.** Enable or disable WiperLock of a network (high-voltage command).
     * @param {boolean} locked - True to lock (decrement command), false to unlock (increment).
     * @param {number} [channel=0] - Network index.
     * @returns {Promise<void>}
     */
    async setWiperLock(locked, channel = 0) {
        this._requireNv();
        this._checkChannel(channel);
        await this._hvCommand(ADDR_NVWIPER0 + channel, !!locked);
    }

    /**
     * **NV, needs `hvc`.** Set the nonvolatile EEPROM write-protect bit (high-voltage command).
     * @param {boolean} enabled - True to enable write protect, false to disable.
     * @returns {Promise<void>}
     */
    async setWriteProtect(enabled) {
        this._requireNv();
        await this._hvCommand(ADDR_WP, !!enabled);
    }
}

// Named aliases: one thin Minimal / Full subclass per family member (only the part is preset).

/** MCP4531 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4531Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4531', rAb, rWiper);
    }
}

/** MCP4531 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4531Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4531', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4532 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4532Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4532', rAb, rWiper);
    }
}

/** MCP4532 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4532Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4532', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4551 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4551Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4551', rAb, rWiper);
    }
}

/** MCP4551 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4551Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4551', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4552 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4552Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4552', rAb, rWiper);
    }
}

/** MCP4552 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4552Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4552', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4631 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4631Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4631', rAb, rWiper);
    }
}

/** MCP4631 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4631Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4631', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4632 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4632Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4632', rAb, rWiper);
    }
}

/** MCP4632 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4632Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4632', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4651 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4651Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4651', rAb, rWiper);
    }
}

/** MCP4651 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4651Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4651', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4652 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4652Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4652', rAb, rWiper);
    }
}

/** MCP4652 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4652Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4652', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4541 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4541Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4541', rAb, rWiper);
    }
}

/** MCP4541 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4541Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4541', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4542 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4542Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4542', rAb, rWiper);
    }
}

/** MCP4542 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4542Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4542', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4561 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4561Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4561', rAb, rWiper);
    }
}

/** MCP4561 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4561Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4561', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4562 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4562Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4562', rAb, rWiper);
    }
}

/** MCP4562 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4562Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4562', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4641 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4641Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4641', rAb, rWiper);
    }
}

/** MCP4641 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4641Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4641', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4642 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4642Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4642', rAb, rWiper);
    }
}

/** MCP4642 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4642Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4642', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4661 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4661Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4661', rAb, rWiper);
    }
}

/** MCP4661 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4661Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4661', rAb, rWiper, hvc, hvcSettleMs);
    }
}

/** MCP4662 minimal interface: {@link Mcp4xxxMinimal} with the part preset. */
class Mcp4662Minimal extends Mcp4xxxMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        super(connection, 'MCP4662', rAb, rWiper);
    }
}

/** MCP4662 full interface: {@link Mcp4xxxFull} with the part preset. */
class Mcp4662Full extends Mcp4xxxFull {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
     * @param {number} [rAb=10000] - RAB in ohms.
     * @param {number} [rWiper=75] - RW in ohms.
     * @param {import('../../connection/output_pin').OutputPin|null} [hvc=null] - Optional high-voltage command pin.
     * @param {number} [hvcSettleMs=1] - Delay in ms between asserting `hvc` and the first SCL edge.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0, hvc = null, hvcSettleMs = 1) {
        super(connection, 'MCP4662', rAb, rWiper, hvc, hvcSettleMs);
    }
}

module.exports = { Mcp4xxxMinimal, Mcp4xxxFull, PARTS, defaultAddress,
    Mcp4531Minimal, Mcp4531Full, Mcp4532Minimal, Mcp4532Full, Mcp4551Minimal, Mcp4551Full, Mcp4552Minimal, Mcp4552Full, Mcp4631Minimal, Mcp4631Full, Mcp4632Minimal, Mcp4632Full, Mcp4651Minimal, Mcp4651Full, Mcp4652Minimal, Mcp4652Full, Mcp4541Minimal, Mcp4541Full, Mcp4542Minimal, Mcp4542Full, Mcp4561Minimal, Mcp4561Full, Mcp4562Minimal, Mcp4562Full, Mcp4641Minimal, Mcp4641Full, Mcp4642Minimal, Mcp4642Full, Mcp4661Minimal, Mcp4661Full, Mcp4662Minimal, Mcp4662Full };
