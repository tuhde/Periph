'use strict';

const REG_IE = 0x00;
const REG_CONTROL = 0x01;
const REG_ISTAT = 0x02;
const AI = 0x10;

const C_MYBUS = 0x01;
const C_NMYBUS = 0x02;
const C_BUSON = 0x04;
const C_NBUSON = 0x08;
const C_BUSINIT = 0x10;
const C_TESTON = 0x40;
const C_NTESTON = 0x80;

const MASKABLE = 0x0F;
const POLL_INTERVAL_MS = 100;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const hasControlBits = (c) => ((c & C_MYBUS) !== 0) === ((c & C_NMYBUS) !== 0);
const connectedBits = (c) => ((c & C_BUSON) !== 0) !== ((c & C_NBUSON) !== 0);
const nbuson = (c) => (c & C_NBUSON ? 1 : 0);
const nmybus = (c) => (c & C_NMYBUS ? 1 : 0);

/**
 * PCA9541 2-to-1 I²C-bus master selector (NXP) — minimal interface.
 *
 * Two upstream masters share one downstream bus of slave devices through the
 * PCA9541's pass gates; this driver is used by **one** of the two masters (the
 * same software runs on both). There is no arbitration: whichever master writes
 * its CONTROL register last before a STOP wins. Each master sees its own copy of
 * the IE, CONTROL and ISTAT registers behind the same addresses. The 7-bit I²C
 * address is 0x70 + A (A3..A0); prefer 0x70–0x77.
 *
 * `init()` reads CONTROL as a bus check (it rejects if the chip does not
 * acknowledge) and ISTAT once to clear stale flags; it writes nothing, so
 * initialising a master never changes the connection state. The CONTROL byte to
 * write depends on the read-only complement bits, so every change reads CONTROL
 * first and applies the datasheet's Table 12 rule.
 */
class PCA9541Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte command code).
     */
    constructor(connection) {
        this._conn = connection;
    }

    /** 7-bit I²C address with A3..A0 = 0. */
    static get I2C_ADDRESS() { return 0x70; }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    /**
     * Run the initialization sequence: read CONTROL as a bus check and ISTAT once
     * to clear stale BUSLOST / BUSOK / BUSINIT flags. Writes nothing.
     * @returns {Promise<void>}
     */
    async init() {
        await this._readReg(REG_CONTROL);
        await this._readReg(REG_ISTAT);
    }

    /**
     * Report whether this master's channel is connected to the downstream bus.
     * @returns {Promise<boolean>} true if MYBUS equals NMYBUS and the bus is connected (BUSON differs from NBUSON).
     */
    async hasControl() {
        const c = await this._readReg(REG_CONTROL);
        return hasControlBits(c) && connectedBits(c);
    }

    /**
     * Take the downstream bus. Reads CONTROL and writes MYBUS / BUSON per the
     * datasheet rule; the write ends with a STOP, which makes the switch. With
     * `busInit` the chip first sends nine clocks, a NACK and a STOP on the
     * downstream bus and flags BUSINIT in ISTAT.
     * @param {boolean} [busInit=false] - Request bus initialization / recovery.
     * @param {number} [timeoutMs=20] - Maximum time to wait for the switch, in milliseconds.
     * @returns {Promise<boolean>} true if this master has control afterwards, false if not within `timeoutMs`.
     */
    async acquire(busInit = false, timeoutMs = 20) {
        const c = await this._readReg(REG_CONTROL);
        let w = ((1 - nbuson(c)) << 2) | nmybus(c);
        if (busInit) w |= C_BUSINIT;
        await this._writeReg(REG_CONTROL, w);
        const attempts = Math.floor(timeoutMs) + 1;
        for (let i = 0; i < attempts; i++) {
            if (busInit && !((await this._readReg(REG_ISTAT)) & 0x02)) {
                await sleep(1);
                continue;
            }
            if (await this.hasControl()) return true;
            await sleep(1);
        }
        return false;
    }

    /**
     * Give the downstream bus to the other master (MYBUS = NOT NMYBUS; the bus stays connected).
     * @returns {Promise<void>}
     */
    async release() {
        const c = await this._readReg(REG_CONTROL);
        await this._writeReg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (1 - nmybus(c)));
    }
}

/**
 * PCA9541 full interface — extends Minimal with raw control access, connect /
 * disconnect, bus recovery, interrupt masks, the INT functional test and
 * interrupt delivery.
 */
class PCA9541Full extends PCA9541Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    static get SOURCE_INT_IN() { return 0x01; }
    static get SOURCE_BUS_INIT() { return 0x02; }
    static get SOURCE_BUS_NOT_IDLE() { return 0x04; }
    static get SOURCE_BUS_LOST() { return 0x08; }
    static get SOURCE_TEST() { return 0x40; }
    static get SOURCE_OTHER_TEST() { return 0x80; }

    /**
     * Read the raw CONTROL byte.
     * @returns {Promise<number>}
     */
    async getControl() {
        return this._readReg(REG_CONTROL);
    }

    /**
     * Whether the downstream bus is connected to some master (BUSON differs from NBUSON).
     * @returns {Promise<boolean>}
     */
    async isConnected() {
        return connectedBits(await this._readReg(REG_CONTROL));
    }

    /**
     * Decode the CONTROL register.
     * @returns {Promise<{hasControl: boolean, connected: boolean, busInitRequested: boolean, ownTest: boolean, otherTest: boolean}>}
     */
    async status() {
        const c = await this._readReg(REG_CONTROL);
        return {
            hasControl: hasControlBits(c),
            connected: connectedBits(c),
            busInitRequested: Boolean(c & C_BUSINIT),
            ownTest: Boolean(c & C_TESTON),
            otherTest: Boolean(c & C_NTESTON),
        };
    }

    /**
     * Turn the connection on without changing ownership (BUSON = NOT NBUSON; MYBUS as read).
     * @returns {Promise<void>}
     */
    async connect() {
        const c = await this._readReg(REG_CONTROL);
        await this._writeReg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (c & C_MYBUS));
    }

    /**
     * Disconnect both masters from the downstream bus (BUSON = NBUSON; ownership bits left).
     * @returns {Promise<void>}
     */
    async disconnect() {
        const c = await this._readReg(REG_CONTROL);
        await this._writeReg(REG_CONTROL, (nbuson(c) << 2) | (c & C_MYBUS));
    }

    /**
     * Recover a hung downstream bus and take it (acquire with bus initialization).
     * @param {number} [timeoutMs=20] - Maximum time to wait for BUSINIT, in milliseconds.
     * @returns {Promise<boolean>} true if this master has control afterwards.
     */
    async recoverBus(timeoutMs = 20) {
        return this.acquire(true, timeoutMs);
    }

    /**
     * Write the IE register (true = masked, no interrupt); all-false re-enables every interrupt.
     * @param {object} [mask]
     * @param {boolean} [mask.busLost=false] - Mask the "other master took the bus" interrupt.
     * @param {boolean} [mask.busNotIdle=false] - Mask the "bus non-idle at the switch" interrupt.
     * @param {boolean} [mask.busInit=false] - Mask the "bus initialization done" interrupt.
     * @param {boolean} [mask.intIn=false] - Mask the downstream INT_IN interrupt.
     * @returns {Promise<void>}
     */
    async setInterruptMask({ busLost = false, busNotIdle = false, busInit = false, intIn = false } = {}) {
        await this._writeReg(REG_IE, (busLost ? 8 : 0) | (busNotIdle ? 4 : 0) | (busInit ? 2 : 0) | (intIn ? 1 : 0));
    }

    /**
     * Read the IE register (bit set = interrupt masked).
     * @returns {Promise<number>}
     */
    async getInterruptMask() {
        return this._readReg(REG_IE);
    }

    /**
     * Drive the INT lines for the functional test; the bus bits are written back unchanged.
     * @param {boolean} [own=false] - Drive this master's INT low (TESTON).
     * @param {boolean} [other=false] - Drive the other master's INT low (NTESTON).
     * @returns {Promise<void>}
     */
    async testInterrupt(own = false, other = false) {
        const c = await this._readReg(REG_CONTROL);
        await this._writeReg(REG_CONTROL, (c & (C_BUSON | C_MYBUS)) | (own ? C_TESTON : 0) | (other ? C_NTESTON : 0));
    }

    /**
     * Read IE, CONTROL and ISTAT in one auto-increment transaction (clears the self-clearing ISTAT flags).
     * @returns {Promise<{ie: number, control: number, istat: number}>}
     */
    async readAll() {
        const b = await this._conn.readReg(AI | REG_IE, 3);
        return { ie: b[0], control: b[1], istat: b[2] };
    }

    /**
     * Enable an interrupt source (clear its mask bit in IE); the test sources need no enable.
     * @param {number} source - One of SOURCE_INT_IN, SOURCE_BUS_INIT, SOURCE_BUS_NOT_IDLE, SOURCE_BUS_LOST.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        const bit = source & MASKABLE;
        if (bit) await this._writeReg(REG_IE, (await this._readReg(REG_IE)) & ~bit);
    }

    /**
     * Disable an interrupt source (set its mask bit in IE).
     * @param {number} source - One of SOURCE_INT_IN, SOURCE_BUS_INIT, SOURCE_BUS_NOT_IDLE, SOURCE_BUS_LOST.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        const bit = source & MASKABLE;
        if (bit) await this._writeReg(REG_IE, (await this._readReg(REG_IE)) | bit);
    }

    /**
     * Read ISTAT (clears BUSLOST / BUSOK / BUSINIT).
     * @returns {Promise<number>} ISTAT byte; bits as the SOURCE_* constants.
     */
    async pollInterrupt() {
        return this._readReg(REG_ISTAT);
    }

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, POLL_INTERVAL_MS);
        }
    }

    /**
     * Subscribe to this master's INT pin (falling edge). `callback(status)`
     * receives the ISTAT byte read (which clears the self-clearing flags).
     * Delivery is via the INT line (options.intPin or connection.intPin) or a
     * 100 ms polling timer.
     * @param {function} callback - Called with (status).
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
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { PCA9541Minimal, PCA9541Full };
