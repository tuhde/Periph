'use strict';

const _TIMEOUT_MS = 500;
// tBOOT ≤ 1.2 ms, rounded up to the timer resolution.
const _BOOT_MS = 2;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Shared base for ST's VL53 FlightSense Time-of-Flight ranging family.
 *
 * Internal — not exported from the package index and never instantiated
 * directly. VL53L0X and VL53L1X have different register maps (8-bit vs
 * 16-bit register index), so this base holds no register addresses and no
 * ranging logic, only the plumbing both chips share: big-endian register
 * access with a 1- or 2-byte index, the bounded poll helper, the XSHUT boot
 * wait, the promise queue that serializes multi-register sequences against
 * the interrupt poller, interrupt delivery (intPin edge or 5 ms polling
 * timer), the volatile re-addressing helper, and the family's shared
 * constants. See specs/tof/_vl53_base.md.
 *
 * Subclasses implement `_pollInterruptStatus()`.
 */
class VL53Base {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     * @param {number} indexBytes - Register index width on the wire, 1 or 2.
     * @param {string} chipName - Chip name used in error messages.
     */
    constructor(connection, indexBytes, chipName) {
        this._conn = connection;
        this._indexBytes = indexBytes;
        this._chipName = chipName;
        this._queue = Promise.resolve();
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    _locked(fn) {
        const run = this._queue.then(fn);
        this._queue = run.catch(() => {});
        return run;
    }

    _index(reg) {
        return this._indexBytes === 2 ? [(reg >> 8) & 0xFF, reg & 0xFF] : [reg & 0xFF];
    }

    async _wrBlock(reg, data) {
        await this._conn.write(Buffer.from([...this._index(reg), ...data]));
    }

    async _rdBlock(reg, n) {
        return Buffer.from(await this._conn.writeRead(Buffer.from(this._index(reg)), n));
    }

    async _wr8(reg, value) {
        await this._wrBlock(reg, [value & 0xFF]);
    }

    async _rd8(reg) {
        return (await this._rdBlock(reg, 1))[0];
    }

    async _wr16(reg, value) {
        await this._wrBlock(reg, [(value >> 8) & 0xFF, value & 0xFF]);
    }

    async _rd16(reg) {
        return (await this._rdBlock(reg, 2)).readUInt16BE(0);
    }

    async _wr32(reg, value) {
        const buf = Buffer.alloc(4);
        buf.writeUInt32BE(value >>> 0, 0);
        await this._wrBlock(reg, buf);
    }

    async _rd32(reg) {
        return (await this._rdBlock(reg, 4)).readUInt32BE(0);
    }

    async _waitUntil(predicate, what) {
        const start = Date.now();
        for (;;) {
            if (await predicate()) return;
            if (Date.now() - start > _TIMEOUT_MS) throw new Error(`${this._chipName} timeout waiting for ${what}`);
        }
    }

    async _bootWait() {
        if (this._conn.enPin) await this._conn.enable();
        await _sleep(_BOOT_MS);
    }

    async _setAddressReg(reg, address) {
        if (!(address >= 0x08 && address <= 0x77)) throw new RangeError('address must be 0x08 to 0x77');
        await this._wr8(reg, address & 0x7F);
    }

    /**
     * Read and clear a pending interrupt (under the lock).
     * @returns {Promise<number>} The SOURCE_* value, or 0.
     * @abstract
     */
    async _pollInterruptStatus() {
        throw new Error('not implemented');
    }

    async _subscribe(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this._pollInterruptStatus();
                if (status && this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this._pollInterruptStatus();
                    if (status && this._callback) this._callback(status);
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    async _unsubscribe() {
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

/** Default 7-bit I²C address of every family member. */
VL53Base.I2C_ADDRESS = 0x29;
/** Range < low threshold. */
VL53Base.SOURCE_LEVEL_LOW = 1;
/** Range > high threshold. */
VL53Base.SOURCE_LEVEL_HIGH = 2;
/** Range < low threshold or > high threshold. */
VL53Base.SOURCE_OUT_OF_WINDOW = 3;
/** A new measurement is available (driver default). */
VL53Base.SOURCE_NEW_SAMPLE_READY = 4;
/** low ≤ range ≤ high (VL53L1X only). */
VL53Base.SOURCE_IN_WINDOW = 5;

module.exports = { VL53Base, _sleep };
