'use strict';

const _REG_VCELL   = 0x02;
const _REG_SOC     = 0x04;
const _REG_MODE    = 0x06;
const _REG_VERSION = 0x08;
const _REG_HIBRT   = 0x0A;
const _REG_CONFIG  = 0x0C;
const _REG_VALRT   = 0x14;
const _REG_CRATE   = 0x16;
const _REG_VRESET  = 0x18;
const _REG_STATUS  = 0x1A;
const _REG_CMD     = 0xFE;

const _SOURCES = 0x3F00;

function _sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function _clampByte(v) {
    return Math.max(0, Math.min(255, Math.round(v)));
}

/**
 * MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal interface.
 *
 * Reports the cell voltage and the state of charge. The constructor makes no bus access; the chip
 * runs with its power-on defaults. The first SOC result is valid about 1 s after power-up.
 */
class MAX17048Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x36).
     * @param {number} [cells=1] - 1 for the MAX17048, 2 for the MAX17049 (scales the voltage).
     */
    constructor(connection, cells = 1) {
        this._conn = connection;
        this._cells = cells === 2 ? 2 : 1;
    }

    async _readWord(reg) {
        return (await this._conn.readReg(reg, 2)).readUInt16BE(0);
    }

    async _writeWord(reg, value) {
        const buf = Buffer.alloc(2);
        buf.writeUInt16BE(value & 0xFFFF, 0);
        await this._conn.writeReg(reg, buf);
    }

    /** @returns {Promise<number>} Cell voltage in volts ((VCELL >> 4) × 1.25 mV × cells). */
    async voltage() {
        return ((await this._readWord(_REG_VCELL)) >> 4) * 1.25e-3 * this._cells;
    }

    /** @returns {Promise<number>} State of charge in %, clamped to 0–100 (SOC / 256). */
    async percent() {
        return Math.max(0, Math.min(100, (await this._readWord(_REG_SOC)) / 256));
    }
}

/**
 * MAX17048 / MAX17049 full interface — alerts, RCOMP, hibernate, sleep, reset.
 *
 * Interrupt sources (callback / pollInterrupt / enableInterrupt): SOURCE_RESET, SOURCE_OVERVOLTAGE,
 * SOURCE_UNDERVOLTAGE, SOURCE_VRESET, SOURCE_LOW_SOC, SOURCE_SOC_CHANGE.
 */
class MAX17048Full extends MAX17048Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x36).
     * @param {number} [cells=1] - 1 for the MAX17048, 2 for the MAX17049.
     */
    constructor(connection, cells = 1) {
        super(connection, cells);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._sleepEnabled = false;
        this._edgeHandler = () => { this._service(); };
    }

    async _modify(reg, clear, set) {
        await this._writeWord(reg, (((await this._readWord(reg)) & ~clear) | set) & 0xFFFF);
    }

    /** @returns {Promise<number>} Raw 16-bit VCELL register. */
    async rawVoltage() { return this._readWord(_REG_VCELL); }

    /** @returns {Promise<number>} Raw 16-bit SOC register. */
    async rawSoc() { return this._readWord(_REG_SOC); }

    /** @returns {Promise<number>} Charge/discharge rate in %/h (signed CRATE × 0.208), positive while charging. */
    async chargeRate() {
        return (await this._conn.readReg(_REG_CRATE, 2)).readInt16BE(0) * 0.208;
    }

    /** @returns {Promise<number>} VERSION register (0x001x). */
    async version() { return this._readWord(_REG_VERSION); }

    /** @returns {Promise<number>} Low byte of VRESET/ID (factory ID). */
    async chipId() { return (await this._readWord(_REG_VRESET)) & 0xFF; }

    /** Restart the SOC estimate from the present voltage (MODE.QuickStart); only if the first SOC is clearly wrong. @returns {Promise<void>} */
    async quickStart() { await this._writeWord(_REG_MODE, 0x4000 | (this._sleepEnabled ? 0x2000 : 0)); }

    /** Power-on reset (CMD ← 0x5400); tolerates the missing ACK and waits 1 s. @returns {Promise<void>} */
    async reset() {
        try { await this._writeWord(_REG_CMD, 0x5400); } catch (e) { /* the chip does not ACK */ }
        await _sleep(1000);
    }

    /** Write RCOMP (CONFIG bits 15:8). @param {number} value - 0–255. @returns {Promise<void>} */
    async setRcomp(value) { await this._modify(_REG_CONFIG, 0xFF00, (value & 0xFF) << 8); }

    /** @returns {Promise<number>} RCOMP 0–255. */
    async getRcomp() { return (await this._readWord(_REG_CONFIG)) >> 8; }

    /**
     * Set RCOMP from the datasheet temperature formula with the default coefficients (RCOMP0 = 0x97).
     * @param {number} celsius - Cell temperature in °C.
     * @returns {Promise<void>}
     */
    async compensateTemperature(celsius) {
        const coefficient = celsius >= 20 ? -0.5 : -5.0;
        await this.setRcomp(_clampByte(0x97 + (celsius - 20) * coefficient));
    }

    /** Set the empty-alert threshold (CONFIG.ATHD = 32 − percent). @param {number} percent - 1–32. @returns {Promise<void>} */
    async setAlertSoc(percent) {
        const p = Math.max(1, Math.min(32, Math.round(percent)));
        await this._modify(_REG_CONFIG, 0x001F, 32 - p);
    }

    /** @returns {Promise<number>} Empty-alert threshold in % (1–32). */
    async getAlertSoc() { return 32 - ((await this._readWord(_REG_CONFIG)) & 0x1F); }

    /** Enable the alert on a SOC change of at least 1 % (CONFIG.ALSC). @param {boolean} enabled @returns {Promise<void>} */
    async setSocChangeAlert(enabled) { await this._modify(_REG_CONFIG, 0x0040, enabled ? 0x0040 : 0); }

    /**
     * Set the voltage alert window in 20 mV steps (VALRT).
     * @param {number} minV - Under-voltage threshold in V; 0 disables it.
     * @param {number} maxV - Over-voltage threshold in V; 5.1 disables it.
     * @returns {Promise<void>}
     * @throws {RangeError} If minV > maxV.
     */
    async setVoltageAlert(minV, maxV) {
        if (minV > maxV) throw new RangeError('minV must not exceed maxV');
        await this._writeWord(_REG_VALRT, (_clampByte(minV / 0.020) << 8) | _clampByte(maxV / 0.020));
    }

    /** @returns {Promise<{minV: number, maxV: number}>} Voltage alert window in volts. */
    async getVoltageAlert() {
        const raw = await this._readWord(_REG_VALRT);
        return { minV: (raw >> 8) * 0.020, maxV: (raw & 0xFF) * 0.020 };
    }

    /** Set the reset voltage (VRESET bits 15:9, 40 mV steps). @param {number} volts - 0.0–5.08 V. @returns {Promise<void>} */
    async setResetVoltage(volts) {
        const steps = Math.max(0, Math.min(127, Math.round(volts / 0.040)));
        await this._modify(_REG_VRESET, 0xFE00, steps << 9);
    }

    /** @returns {Promise<number>} Reset voltage in volts. */
    async getResetVoltage() { return ((await this._readWord(_REG_VRESET)) >> 9) * 0.040; }

    /** Let a voltage-reset event assert ALRT (STATUS.ENVR). @param {boolean} enabled @returns {Promise<void>} */
    async enableVoltageResetAlert(enabled) { await this._modify(_REG_STATUS, 0x4000, enabled ? 0x4000 : 0); }

    /** Disable the analog comparator in hibernate mode (VRESET/ID.DIS). @param {boolean} disabled @returns {Promise<void>} */
    async setComparatorDisabled(disabled) { await this._modify(_REG_VRESET, 0x0100, disabled ? 0x0100 : 0); }

    /**
     * Program the hibernate thresholds (HIBRT).
     * @param {number} hibThrPctPerH - Hibernate below this |CRATE| in %/h (0.208 %/h steps).
     * @param {number} actThrV - Leave hibernate above this |OCV − VCELL| in V (1.25 mV steps).
     * @returns {Promise<void>}
     */
    async setHibernateThresholds(hibThrPctPerH, actThrV) {
        await this._writeWord(_REG_HIBRT, (_clampByte(hibThrPctPerH / 0.208) << 8) | _clampByte(actThrV / 0.00125));
    }

    /** @returns {Promise<{hibThrPctPerH: number, actThrV: number}>} Hibernate thresholds. */
    async getHibernateThresholds() {
        const raw = await this._readWord(_REG_HIBRT);
        return { hibThrPctPerH: (raw >> 8) * 0.208, actThrV: (raw & 0xFF) * 0.00125 };
    }

    /** Never hibernate (HIBRT ← 0x0000). @returns {Promise<void>} */
    async disableHibernate() { await this._writeWord(_REG_HIBRT, 0x0000); }

    /** Always stay in hibernate (HIBRT ← 0xFFFF). @returns {Promise<void>} */
    async forceHibernate() { await this._writeWord(_REG_HIBRT, 0xFFFF); }

    /** @returns {Promise<boolean>} MODE.HibStat; false if the write-only register cannot be read. */
    async isHibernating() {
        try { return ((await this._readWord(_REG_MODE)) & 0x1000) !== 0; } catch (e) { return false; }
    }

    /** Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole). @param {boolean} enabled @returns {Promise<void>} */
    async enableSleep(enabled) {
        this._sleepEnabled = !!enabled;
        await this._writeWord(_REG_MODE, enabled ? 0x2000 : 0x0000);
    }

    /** Enter sleep mode (CONFIG.SLEEP = 1); needs enableSleep(true). @returns {Promise<void>} */
    async sleep() { await this._modify(_REG_CONFIG, 0, 0x0080); }

    /** Leave sleep mode (CONFIG.SLEEP = 0). @returns {Promise<void>} */
    async wake() { await this._modify(_REG_CONFIG, 0x0080, 0); }

    /** @returns {Promise<number>} Raw STATUS register. */
    async readStatus() { return this._readWord(_REG_STATUS); }

    /** Clear the given STATUS bits and CONFIG.ALRT (releases ALRT). @param {number} mask @returns {Promise<void>} */
    async clearStatus(mask) {
        await this._modify(_REG_STATUS, mask & 0xFFFF, 0);
        await this._modify(_REG_CONFIG, 0x0020, 0);
    }

    /** @returns {Promise<number>} Raw CONFIG register. */
    async readConfig() { return this._readWord(_REG_CONFIG); }

    /** Write the raw CONFIG register. @param {number} value @returns {Promise<void>} */
    async writeConfig(value) { await this._writeWord(_REG_CONFIG, value); }

    /** Enable an alert source with a control bit (SOURCE_SOC_CHANGE, SOURCE_VRESET). @param {number} source @returns {Promise<void>} */
    async enableInterrupt(source) {
        if (source === MAX17048Full.SOURCE_SOC_CHANGE) await this.setSocChangeAlert(true);
        else if (source === MAX17048Full.SOURCE_VRESET) await this.enableVoltageResetAlert(true);
    }

    /** Disable an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF). @param {number} source @returns {Promise<void>} */
    async disableInterrupt(source) {
        if (source === MAX17048Full.SOURCE_SOC_CHANGE) await this.setSocChangeAlert(false);
        else if (source === MAX17048Full.SOURCE_VRESET) await this.enableVoltageResetAlert(false);
        else if (source === MAX17048Full.SOURCE_UNDERVOLTAGE) await this._modify(_REG_VALRT, 0xFF00, 0);
        else if (source === MAX17048Full.SOURCE_OVERVOLTAGE) await this._modify(_REG_VALRT, 0x00FF, 0x00FF);
    }

    /**
     * Subscribe to ALRT (falling edge). `callback(status)` receives the STATUS word masked to the SOURCE_* bits.
     * Delivery is via the INT line (options.intPin or connection.intPin) or a 1 s polling timer.
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const pin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = pin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (pin) {
            await pin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 1000);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
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

    /**
     * Read STATUS, clear the reported flags and CONFIG.ALRT.
     * @returns {Promise<number>} STATUS word masked to the SOURCE_* bits (0 = no alert).
     */
    async pollInterrupt() {
        const flags = (await this._readWord(_REG_STATUS)) & _SOURCES;
        if (flags) await this.clearStatus(flags);
        return flags;
    }
}

MAX17048Full.SOURCE_RESET = 0x0100;
MAX17048Full.SOURCE_OVERVOLTAGE = 0x0200;
MAX17048Full.SOURCE_UNDERVOLTAGE = 0x0400;
MAX17048Full.SOURCE_VRESET = 0x0800;
MAX17048Full.SOURCE_LOW_SOC = 0x1000;
MAX17048Full.SOURCE_SOC_CHANGE = 0x2000;

module.exports = { MAX17048Minimal, MAX17048Full };
