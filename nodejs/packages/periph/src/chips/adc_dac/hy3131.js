'use strict';

/**
 * HYCON HY3131 50,000-count DMM analog front end driver (SPI).
 *
 * Minimal reads AD1 as a differential voltage; Full exposes AD1/AD2/AD3,
 * low-pass filter, RMS, peak hold, frequency counter, interrupt flags and raw
 * register access. See specs/adc_dac/hy3131.md.
 *
 * The chip's SPI framing is non-standard (7-bit address + R/W bit, one blank
 * clock on reads, auto-increment), so the driver uses the bare connection's
 * `write` / `writeRead`. JS constructors cannot be async: call `await
 * adc.init()` after construction (or use `HY3131Minimal.create`).
 */

const REG = {
    AD1: 0x00, AD2: 0x03, LPF: 0x06, RMS: 0x09, PKHMIN: 0x0E, PKHMAX: 0x11,
    CTSTA: 0x14, CTC: 0x15, CTB: 0x18, CTA: 0x1B, INTF: 0x1E, INTE: 0x1F,
    R20: 0x20, R22: 0x22, R23: 0x23, R24: 0x24, R25: 0x25, R26: 0x26, R27: 0x27,
    R28: 0x28, R29: 0x29, R2A: 0x2A, R2F: 0x2F, R30: 0x30, R31: 0x31, R32: 0x32,
    R33: 0x33, R34: 0x34, R35: 0x35, R36: 0x36, R37: 0x37,
};
const FLAG = { CTF: 0x01, AD2F: 0x02, AD1F: 0x04, LPFF: 0x08, RMSF: 0x10, BORF: 0x80 };
const AD1_GAINS = [0.9, 1.8, 2.7, 3.6];
const DCOS = [0, 0.25, 0.5, 0.75, 0, -0.25, -0.5, -0.75];
const TIMEOUT_MS = 2000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function sext(value, bits) {
    const sign = 2 ** (bits - 1);
    return value >= sign ? value - 2 * sign : value;
}

class HY3131Minimal {
    /**
     * @param {object} connection - SPI connection (mode 0, MSB first, CS active low).
     * @param {number} [vref=1.2] - Voltage on REFO in volts.
     */
    constructor(connection, vref = 1.2) {
        this._conn = connection;
        this._vref = Number(vref);
        this._flags = 0;
        this._ig = 0;
        this._rg = 0;
        this._dcos = 0;
    }

    /** Create and initialise a driver instance. */
    static async create(connection, vref = 1.2) {
        const adc = new this(connection, vref);
        await adc.init();
        return adc;
    }

    /** Run the initialisation sequence (reset, oscillator, reference, AD1 defaults). */
    async init() {
        await this.writeRegister(REG.R37, 0x60);
        await sleep(5);
        await this.writeRegister(REG.R33, 0x28);
        await sleep(10);
        await this.writeRegister(REG.R31, 0xC0);
        await this.writeRegister(REG.R30, 0x00);
        await sleep(5);
        await this.writeRegister(REG.R2F, 0x80);
        await this.writeRegister(REG.R2A, Buffer.alloc(5));
        for (const reg of [REG.R32, REG.R20, 0x21, REG.R34, REG.R35, REG.R36,
            REG.R29, REG.R26, REG.R27, REG.INTE]) {
            await this.writeRegister(reg, 0x00);
        }
        await this.writeRegister(REG.R24, 0xA3);
        await this.writeRegister(REG.R28, 0x11);
        this._ig = 0;
        this._rg = 0;
        this._dcos = 0;
        await this.writeRegister(REG.R25, 0x00);
        await this.writeRegister(REG.R22, 0x07);
        await this.writeRegister(REG.R23, 0x80);
        await this.readRegister(REG.INTF);
        this._flags = 0;
    }

    /**
     * Read `n` consecutive registers starting at `reg` (auto-increment).
     * @param {number} reg - Start address 0x00–0x37.
     * @param {number} [n=1] - Number of bytes.
     * @returns {Promise<Buffer>}
     */
    async readRegister(reg, n = 1) {
        const rx = await this._conn.writeRead(Buffer.from([((reg & 0x7F) << 1) | 1]), n + 1);
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) out[i] = ((rx[i] << 1) | (rx[i + 1] >> 7)) & 0xFF;
        return out;
    }

    /**
     * Write a byte or Buffer to `reg` (auto-increment).
     * @param {number} reg
     * @param {number|Buffer} data
     */
    async writeRegister(reg, data) {
        const payload = typeof data === 'number' ? Buffer.from([data]) : Buffer.from(data);
        await this._conn.write(Buffer.concat([Buffer.from([(reg & 0x7F) << 1]), payload]));
    }

    async _waitFlag(mask) {
        const start = Date.now();
        for (;;) {
            this._flags |= (await this.readRegister(REG.INTF))[0];
            if (this._flags & mask) {
                this._flags &= ~mask;
                return true;
            }
            if (Date.now() - start > TIMEOUT_MS) return false;
            await sleep(1);
        }
    }

    async _read24(reg) {
        const b = await this.readRegister(reg, 3);
        return b[0] | (b[1] << 8) | (b[2] << 16);
    }

    /** @returns {Promise<boolean>} True if a new AD1 conversion is pending (latches INTF). */
    async dataReady() {
        this._flags |= (await this.readRegister(REG.INTF))[0];
        return Boolean(this._flags & FLAG.AD1F);
    }

    /** @returns {Promise<number>} Signed 24-bit AD1 code. */
    async readRaw() {
        if (!(await this._waitFlag(FLAG.AD1F))) throw new Error('HY3131 AD1 timeout');
        return sext(await this._read24(REG.AD1), 24);
    }

    /** @returns {Promise<number>} AD1 differential input voltage in volts. */
    async readVoltage() {
        const code = await this.readRaw();
        return (code / 8388608 * (this._rg === 0 ? 1 : 0.3333333333) - this._dcos)
            * this._vref / AD1_GAINS[this._ig];
    }
}

class HY3131Full extends HY3131Minimal {
    /** Software-reset the chip and re-apply the Minimal defaults. */
    async reset() { await this.init(); }

    /**
     * Configure AD1.
     * @param {object} [o]
     * @param {number} [o.osr=7] 0–7 → OSR1 256 … 32768
     * @param {number} [o.chop=0] 0–3 (1x enables the chopper, halves the rate)
     * @param {number} [o.inputGain=0] 0–3 → 0.9, 1.8, 2.7, 3.6
     * @param {number} [o.refGain=0] 0 → 1.0, 1 → 0.333
     * @param {number} [o.offset=0] 0–7 DCOS code
     * @param {number} [o.buffers=0] 4-bit mask: bit3 RH, bit2 RL, bit1 IP, bit0 IN
     */
    async configureAd1({ osr = 7, chop = 0, inputGain = 0, refGain = 0, offset = 0, buffers = 0 } = {}) {
        const r25 = (await this.readRegister(REG.R25))[0];
        await this.writeRegister(REG.R25, (r25 & 0xCF) | ((inputGain & 3) << 4));
        await this.writeRegister(REG.R22, ((offset & 7) << 5) | ((chop & 3) << 3) | (osr & 7));
        await this.writeRegister(REG.R23, 0x80 | ((refGain & 1) << 4) | (buffers & 0x0F));
        this._ig = inputGain & 3;
        this._rg = refGain & 1;
        this._dcos = DCOS[offset & 7];
        await this.readRegister(REG.INTF);
        this._flags = 0;
    }

    /** Select AD1 inputs: pos 0–15 (SAD1FP), neg 0–7 (SAD1FN), filterR 0–3 (SFT1). */
    async setAd1Input(pos, neg, filterR = 2) {
        await this.writeRegister(REG.R24, ((pos & 15) << 4) | (neg & 7));
        const r33 = (await this.readRegister(REG.R33))[0];
        await this.writeRegister(REG.R33, (r33 & 0xF3) | ((filterR & 3) << 2));
    }

    /** Select AD1 reference: high 0–6 (SAD1RH), low 0–6 (SAD1RL). */
    async setAd1Reference(high, low) {
        await this.writeRegister(REG.R28, ((high & 7) << 4) | (low & 7));
    }

    /** @returns {Promise<number>} TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2. */
    async readTemperatureCode() {
        const r33 = (await this.readRegister(REG.R33))[0];
        const codes = [];
        for (const sel of [2, 3]) {
            await this.writeRegister(REG.R33, (r33 & 0xFC) | sel);
            await this.readRegister(REG.INTF);
            this._flags = 0;
            await this._waitFlag(FLAG.AD1F); // discard settling sample
            codes.push(await this.readRaw());
        }
        await this.writeRegister(REG.R33, r33);
        this._flags = 0;
        return Math.trunc((codes[1] - codes[0]) / 2);
    }

    /**
     * @param {number} tcode25 - readTemperatureCode() measured at 25 °C.
     * @returns {Promise<number>} Die temperature in °C.
     */
    async readTemperature(tcode25) {
        const tOs = -3.15;
        const g = tcode25 / (25 + 273.15 + tOs);
        return (await this.readTemperatureCode()) / g - 273.15 - tOs;
    }

    /**
     * Enable AD2 (and AD3 when useAd3).
     * @param {object} [o]
     */
    async enableAd2({ osr = 5, inputGain = 1, refGain = 0, chop = false, clkDiv4 = false,
        source = 0, useAd3 = false } = {}) {
        const r25 = (await this.readRegister(REG.R25))[0];
        await this.writeRegister(REG.R25, (r25 & 0x3F) | ((inputGain & 3) << 6));
        await this.writeRegister(REG.R27, ((source & 3) << 6) | 0x15);
        await this.writeRegister(REG.R26, 0x80 | (chop ? 0x20 : 0) | ((refGain & 1) << 4)
            | (clkDiv4 ? 0x08 : 0) | (osr & 7));
        if (useAd3) {
            await this.writeRegister(REG.R35, ((source & 3) << 6) | (1 << 4) | ((inputGain & 3) << 2));
            await this.writeRegister(REG.R34, 0x80 | (chop ? 0x20 : 0) | ((refGain & 1) << 4) | 0x04);
        } else {
            await this.writeRegister(REG.R34, 0x00);
        }
        await this.readRegister(REG.INTF);
        this._flags = 0;
    }

    /** @returns {Promise<number>} Signed 19-bit AD2 (or AD3) code. */
    async readAd2Raw() {
        if (!(await this._waitFlag(FLAG.AD2F))) throw new Error('HY3131 AD2 timeout');
        return sext(await this._read24(REG.AD2), 24);
    }

    /** Enable the low-pass filter; bw 0–7 → OSR4 128 … 16384. */
    async enableLpf(bw = 0) {
        const r29 = (await this.readRegister(REG.R29))[0];
        await this.writeRegister(REG.R29, (r29 & 0x87) | 0x40 | ((bw & 7) << 3));
    }

    /** @returns {Promise<number>} Signed 19-bit LPF code. */
    async readLpfRaw() {
        if (!(await this._waitFlag(FLAG.LPFF))) throw new Error('HY3131 LPF timeout');
        return sext(await this._read24(REG.LPF), 24);
    }

    /** Enable the RMS converter (requires AD2 and the LPF). */
    async enableRms() {
        const r29 = (await this.readRegister(REG.R29))[0];
        await this.writeRegister(REG.R29, r29 | 0x80);
    }

    /** @returns {Promise<number>} sqrt(RMS accumulator) in AD2 LSB units. */
    async readRms() {
        if (!(await this._waitFlag(FLAG.RMSF))) throw new Error('HY3131 RMS timeout');
        const b = await this.readRegister(REG.RMS, 5);
        const value = sext(b[0] + b[1] * 2 ** 8 + b[2] * 2 ** 16 + b[3] * 2 ** 24 + b[4] * 2 ** 32, 40);
        return Math.sqrt(Math.max(value, 0));
    }

    /** Enable peak hold; source 0–3 → AD2, AD1<23:5>, LPF, LPF. Re-enabling resets min/max. */
    async enablePeakHold(source = 0) {
        const r29 = (await this.readRegister(REG.R29))[0];
        await this.writeRegister(REG.R29, r29 & ~0x07);
        await this.writeRegister(REG.R29, (r29 & ~0x07) | 0x04 | (source & 3));
    }

    /** @returns {Promise<{min:number,max:number}>} Signed 19-bit peak-hold codes. */
    async readPeak() {
        return {
            min: sext(await this._read24(REG.PKHMIN), 24),
            max: sext(await this._read24(REG.PKHMAX), 24),
        };
    }

    /** Start the frequency counter; gatePreload 0–0xFFFF goes to CTA<23:8>. */
    async startCounter(gatePreload = 0) {
        const r20 = (await this.readRegister(REG.R20))[0];
        await this.writeRegister(REG.R20, r20 & ~0x02);
        await this.writeRegister(REG.CTA + 1, Buffer.from([gatePreload & 0xFF, (gatePreload >> 8) & 0xFF]));
        await this.readRegister(REG.INTF);
        this._flags = 0;
        await this.writeRegister(REG.R20, r20 | 0x0A);
    }

    /** @returns {Promise<{cta:number,ctb:number,ctc:number,overflow:boolean}>} */
    async readCounter() {
        if (!(await this._waitFlag(FLAG.CTF))) throw new Error('HY3131 counter timeout');
        const overflow = Boolean((await this.readRegister(REG.CTSTA))[0] & 0x01);
        return {
            cta: await this._read24(REG.CTA),
            ctb: await this._read24(REG.CTB),
            ctc: await this._read24(REG.CTC),
            overflow,
        };
    }

    /** @returns {Promise<number>} INTF (clears it); latched flags included and cleared. */
    async readFlags() {
        const flags = this._flags | (await this.readRegister(REG.INTF))[0];
        this._flags = 0;
        return flags;
    }

    /** Write INTE (bits: RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0). */
    async enableInterrupts(mask) {
        await this.writeRegister(REG.INTE, mask & 0x1F);
    }
}

module.exports = { HY3131Minimal, HY3131Full, FLAG };
