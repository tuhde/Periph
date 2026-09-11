'use strict';

const _REG_INTERRUPT_CFG = 0x0B;
const _REG_THS_P_L       = 0x0C;
const _REG_THS_P_H       = 0x0D;
const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x10;
const _REG_CTRL_REG2     = 0x11;
const _REG_CTRL_REG3     = 0x12;
const _REG_FIFO_CTRL     = 0x14;
const _REG_RPDS_L        = 0x18;
const _REG_RPDS_H        = 0x19;
const _REG_RES_CONF      = 0x1A;
const _REG_INT_SOURCE    = 0x25;
const _REG_FIFO_STATUS   = 0x26;
const _REG_STATUS        = 0x27;
const _REG_PRESS_XL      = 0x28;
const _REG_LPFP_RES      = 0x33;

const _CHIP_ID           = 0xB1;
const _STATUS_P_DA       = 0x01;
const _STATUS_T_DA       = 0x02;
const _CTRL_REG1_DEFAULT = 0x12;
const _CTRL_REG2_RESET   = 0x04;
const _CTRL_REG2_DEFAULT = 0x10;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) {}
}

function _signExtend24(v) { return (v & 0x800000) ? (v | 0xFF000000) : v; }
function _signExtend16(v) { return (v & 0x8000) ? (v | 0xFF00) : v; }

/**
 * LPS33HW water-resistant MEMS absolute pressure sensor - minimal interface.
 *
 * Provides calibrated pressure (Pa) and temperature (C) with no configuration
 * beyond the connection. I2C address is 0x5C (SA0=GND) or 0x5D (SA0=VDD).
 *
 * Default: ODR=1 Hz, BDU=1, EN_LPFP=0, IF_ADD_INC=1.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C or SPI connection.
 */
class LPS33HWMinimal {
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        let chipId = 0;
        try {
            const buf = await this._readReg(_REG_WHO_AM_I, 1);
            chipId = buf[0];
        } catch (e) {}
        if (chipId !== _CHIP_ID) {
            throw new Error('Unexpected LPS33HW chip ID 0x' + chipId.toString(16) + ', expected 0x' + _CHIP_ID.toString(16));
        }
        await this._writeReg(_REG_CTRL_REG2, _CTRL_REG2_RESET);
        _delay(1);
        await this._writeReg(_REG_CTRL_REG2, _CTRL_REG2_DEFAULT);
        await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg, value]));
    }

    async _readReg(reg, n) {
        return this._conn.writeRead(Buffer.from([reg]), n);
    }

    async _waitStatus(mask) {
        for (let i = 0; i < 50; i++) {
            const buf = await this._readReg(_REG_STATUS, 1);
            if ((buf[0] & mask) === mask) return;
            _delay(5);
        }
        throw new Error('LPS33HW data not ready');
    }

    async pressure() {
        await this._waitStatus(_STATUS_P_DA);
        const raw = await this._readReg(_REG_PRESS_XL, 5);
        const v = (raw[2] << 16) | (raw[1] << 8) | raw[0];
        return _signExtend24(v) * 100.0 / 4096.0;
    }

    async temperature() {
        await this._waitStatus(_STATUS_T_DA);
        const raw = await this._readReg(_REG_PRESS_XL, 5);
        const v = (raw[4] << 8) | raw[3];
        return _signExtend16(v) / 100.0;
    }
}

/**
 * LPS33HW full interface - extends LPS33HWMinimal.
 */
class LPS33HWFull extends LPS33HWMinimal {
    static ODR_POWER_DOWN = 0;
    static ODR_1_HZ       = 1;
    static ODR_10_HZ      = 2;
    static ODR_25_HZ      = 3;
    static ODR_50_HZ      = 4;
    static ODR_75_HZ      = 5;
    static LPFP_BW_ODR_9  = 0;
    static LPFP_BW_ODR_20 = 1;
    static FIFO_MODE_BYPASS            = 0;
    static FIFO_MODE_FIFO              = 1;
    static FIFO_MODE_STREAM            = 2;
    static FIFO_MODE_STREAM_TO_FIFO    = 3;
    static FIFO_MODE_BYPASS_TO_STREAM  = 4;
    static FIFO_MODE_DYNAMIC_STREAM    = 6;
    static FIFO_MODE_BYPASS_TO_FIFO    = 7;
    static INT_S_DATA_SIGNALS   = 0;
    static INT_S_PRESSURE_HIGH  = 1;
    static INT_S_PRESSURE_LOW   = 2;
    static INT_S_PRESSURE_BOTH  = 3;

    constructor(connection) {
        super(connection);
    }

    async configure(odr, bdu, enLpfp, lpfpCfg, lcEn, sim) {
        const ctrl1 = ((odr & 7) << 4)
                    | (enLpfp ? (1 << 3) : 0)
                    | ((lpfpCfg & 1) << 2)
                    | (bdu ? (1 << 1) : 0)
                    | (sim ? 1 : 0);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        const cur = await this._readReg(_REG_RES_CONF, 1);
        await this._writeReg(_REG_RES_CONF, (cur[0] & 0xFE) | (lcEn ? 1 : 0));
    }

    async oneShot() {
        const cur = await this._readReg(_REG_CTRL_REG2, 1);
        await this._writeReg(_REG_CTRL_REG2, cur[0] | 0x01);
        for (let i = 0; i < 50; i++) {
            const statusBuf = await this._readReg(_REG_STATUS, 1);
            if ((statusBuf[0] & 0x03) === 0x03) {
                return { pressure_Pa: await this.pressure(),
                         temperature_C: await this.temperature() };
            }
            _delay(5);
        }
        throw new Error('LPS33HW one-shot timeout');
    }

    async status() {
        const buf = await this._readReg(_REG_STATUS, 1);
        return buf[0];
    }

    async reset() {
        await this._writeReg(_REG_CTRL_REG2, _CTRL_REG2_RESET);
        for (let i = 0; i < 50; i++) {
            const buf = await this._readReg(_REG_CTRL_REG2, 1);
            if (!(buf[0] & 0x04)) break;
            _delay(1);
        }
        await this._writeReg(_REG_CTRL_REG2, _CTRL_REG2_DEFAULT);
        await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);
    }

    async reboot() {
        await this._writeReg(_REG_CTRL_REG2, 0x80);
        for (let i = 0; i < 100; i++) {
            const buf = await this._readReg(_REG_INT_SOURCE, 1);
            if (!(buf[0] & 0x80)) break;
            _delay(5);
        }
    }

    async setPressureOffset(offsetHPa) {
        let raw = Math.round(offsetHPa * 16);
        if (raw < 0) raw += 0x10000;
        await this._writeReg(_REG_RPDS_L, raw & 0xFF);
        await this._writeReg(_REG_RPDS_H, (raw >> 8) & 0xFF);
    }

    async setAutozero() {
        const cur = await this._readReg(_REG_INTERRUPT_CFG, 1);
        await this._writeReg(_REG_INTERRUPT_CFG, cur[0] | 0x20);
    }

    async clearAutozero() {
        const cur = await this._readReg(_REG_INTERRUPT_CFG, 1);
        await this._writeReg(_REG_INTERRUPT_CFG, cur[0] | 0x10);
    }

    async setAutorifp() {
        const cur = await this._readReg(_REG_INTERRUPT_CFG, 1);
        await this._writeReg(_REG_INTERRUPT_CFG, cur[0] | 0x80);
    }

    async clearAutorifp() {
        const cur = await this._readReg(_REG_INTERRUPT_CFG, 1);
        await this._writeReg(_REG_INTERRUPT_CFG, cur[0] | 0x40);
    }

    async configureInterrupt(drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain) {
        const ctrl3 = (activeLow ? 0x80 : 0)
                    | (openDrain ? 0x40 : 0)
                    | (fFss5 ? 0x20 : 0)
                    | (fFth ? 0x10 : 0)
                    | (fOvr ? 0x08 : 0)
                    | (drdy ? 0x04 : 0)
                    | (intS & 0x03);
        await this._writeReg(_REG_CTRL_REG3, ctrl3);
    }

    async configurePressureInterrupt(highEn, lowEn, thresholdHPa, latch) {
        const rawThs = Math.round(thresholdHPa * 16) & 0xFFFF;
        await this._writeReg(_REG_THS_P_L, rawThs & 0xFF);
        await this._writeReg(_REG_THS_P_H, (rawThs >> 8) & 0xFF);
        const cur = await this._readReg(_REG_INTERRUPT_CFG, 1);
        const newCfg = (cur[0] & 0xF0)
                     | (latch ? 0x04 : 0)
                     | (highEn ? 0x02 : 0)
                     | (lowEn ? 0x01 : 0);
        await this._writeReg(_REG_INTERRUPT_CFG, newCfg);
    }

    async interruptStatus() {
        const buf = await this._readReg(_REG_INT_SOURCE, 1);
        return buf[0];
    }

    async enableFifo(mode, watermark) {
        if (mode === 5) throw new Error('FIFO mode 5 is reserved');
        const ctrl = ((mode & 7) << 5) | (watermark & 0x1F);
        await this._writeReg(_REG_FIFO_CTRL, ctrl);
        const cur = await this._readReg(_REG_CTRL_REG2, 1);
        await this._writeReg(_REG_CTRL_REG2, cur[0] | 0x40);
    }

    async disableFifo() {
        const cur = await this._readReg(_REG_CTRL_REG2, 1);
        await this._writeReg(_REG_CTRL_REG2, cur[0] & ~0x40);
        await this._writeReg(_REG_FIFO_CTRL, 0);
    }

    async fifoStatus() {
        const buf = await this._readReg(_REG_FIFO_STATUS, 1);
        return buf[0];
    }

    async resetLpf() {
        await this._readReg(_REG_LPFP_RES, 1);
    }
}

module.exports = { LPS33HWMinimal, LPS33HWFull };