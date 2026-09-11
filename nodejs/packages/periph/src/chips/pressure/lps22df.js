'use strict';

const _REG_INTERRUPT_CFG = 0x0B;
const _REG_THS_P_L       = 0x0C;
const _REG_THS_P_H       = 0x0D;
const _REG_IF_CTRL       = 0x0E;
const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x10;
const _REG_CTRL_REG2     = 0x11;
const _REG_CTRL_REG3     = 0x12;
const _REG_CTRL_REG4     = 0x13;
const _REG_FIFO_CTRL     = 0x14;
const _REG_FIFO_WTM      = 0x15;
const _REG_REF_P_L       = 0x16;
const _REG_REF_P_H       = 0x17;
const _REG_RPDS_L        = 0x1A;
const _REG_RPDS_H        = 0x1B;
const _REG_INT_SOURCE    = 0x24;
const _REG_FIFO_STATUS1  = 0x25;
const _REG_STATUS        = 0x27;
const _REG_PRESS_OUT_XL  = 0x28;
const _REG_TEMP_OUT_L    = 0x2B;
const _REG_FIFO_PRESS_XL = 0x78;

const _CHIP_ID = 0xB4;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * LPS22DF absolute pressure and temperature sensor — minimal interface.
 *
 * Provides pressure (Pa) and temperature (°C) readings with no configuration
 * beyond the connection. I²C address is 0x5C (SDO=GND) or 0x5D (SDO=VDDIO).
 *
 * Default: ODR=10 Hz, AVG=4 samples, BDU enabled, low-pass filter off,
 * FIFO bypass mode.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class LPS22DFMinimal {
    constructor(connection, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._init();
    }

    async _init() {
        const who = await this._readReg(_REG_WHO_AM_I, 1);
        if (who[0] !== _CHIP_ID) {
            throw new Error(`LPS22DF not found: WHO_AM_I expected 0x${_CHIP_ID.toString(16)}, got 0x${who[0].toString(16)}`);
        }
        await this._writeReg(_REG_CTRL_REG2, 0x04);  // SWRESET=1
        _delay(1);
        await this._writeReg(_REG_CTRL_REG1, (3 << 3) | 0);  // ODR=10 Hz, AVG=4
        await this._writeReg(_REG_CTRL_REG2, 0x08);          // BDU=1
    }

    async _writeReg(reg, value) {
        const addr = this._busType === 'spi' ? (reg & 0x7F) : reg;
        await this._conn.write(Buffer.from([addr, value & 0xFF]));
    }

    async _readReg(reg, n) {
        const addr = this._busType === 'spi' ? (reg | 0x80) : reg;
        return this._conn.writeRead(Buffer.from([addr]), n);
    }

    async _waitPDa() {
        while (true) {
            const status = await this._readReg(_REG_STATUS, 1);
            if (status[0] & 0x01) return;
            _delay(1);
        }
    }

    /**
     * Read absolute pressure.
     *
     * Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H. Sign-extends the
     * 24-bit two's complement value and converts to pascals (4096 LSB/hPa).
     *
     * @returns {Promise<number>} Pressure in pascals.
     */
    async pressure() {
        await this._waitPDa();
        const raw = await this._readReg(_REG_PRESS_OUT_XL, 3);
        let value = raw[0] | (raw[1] << 8) | (raw[2] << 16);
        if (value & 0x800000) value -= 0x1000000;
        return (value / 4096.0) * 100.0;
    }

    /**
     * Read temperature.
     *
     * Reads TEMP_OUT_L..H. Sign-extends the 16-bit two's complement value
     * and converts to °C (100 LSB/°C).
     *
     * @returns {Promise<number>} Temperature in degrees Celsius.
     */
    async temperature() {
        const raw = await this._readReg(_REG_TEMP_OUT_L, 2);
        let value = raw[0] | (raw[1] << 8);
        if (value & 0x8000) value -= 0x10000;
        return value / 100.0;
    }
}

/**
 * LPS22DF full interface — extends LPS22DFMinimal with configuration,
 * threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class LPS22DFFull extends LPS22DFMinimal {
    static ODR_POWER_DOWN = 0;
    static ODR_1_HZ       = 1;
    static ODR_4_HZ       = 2;
    static ODR_10_HZ      = 3;
    static ODR_25_HZ      = 4;
    static ODR_50_HZ       = 5;
    static ODR_75_HZ      = 6;
    static ODR_100_HZ     = 7;
    static ODR_200_HZ     = 8;

    static AVG_4   = 0;
    static AVG_8   = 1;
    static AVG_16  = 2;
    static AVG_32  = 3;
    static AVG_64  = 4;
    static AVG_128 = 5;
    static AVG_512 = 7;

    static FIFO_BYPASS         = 0;
    static FIFO_FIFO           = 1;
    static FIFO_CONTINUOUS     = 2;
    static FIFO_BYPASS_TO_FIFO = 3;
    static FIFO_BYPASS_TO_CONT = 4;
    static FIFO_CONT_TO_FIFO   = 5;

    constructor(connection, busType = 'i2c') {
        super(connection, busType);
    }

    /**
     * Write CTRL_REG1 and CTRL_REG2.
     * @param {number} odr - Output data rate (0=power-down, 1..7=1..100 Hz, 8=200 Hz).
     * @param {number} avg - Averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512).
     * @param {boolean} enLpfp - Enable low-pass filter on pressure output.
     * @param {number} lfpfCfg - 0=ODR/4 cutoff, 1=ODR/9 cutoff.
     * @param {boolean} bdu - Block data update.
     * @returns {Promise<void>}
     */
    async configure(odr, avg, enLpfp, lfpfCfg, bdu) {
        const ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07);
        let ctrl2 = 0;
        if (enLpfp)  ctrl2 |= 0x10;
        if (lfpfCfg) ctrl2 |= 0x20;
        if (bdu)      ctrl2 |= 0x08;
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        await this._writeReg(_REG_CTRL_REG2, ctrl2);
    }

    /** @returns {Promise<void>} */
    async oneshot() {
        await this._writeReg(_REG_CTRL_REG1, 0x00);
        await this._writeReg(_REG_CTRL_REG2, 0x08 | 0x01);
        await this._waitPDa();
    }

    /**
     * Compute altitude above sea level from the current pressure.
     * @param {number} [seaLevelPa=101325] - Reference sea-level pressure in pascals.
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelPa = 101325.0) {
        const p = await this.pressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }

    /** @returns {Promise<void>} */
    async softwareReset() {
        await this._writeReg(_REG_CTRL_REG2, 0x04);
        _delay(1);
    }

    /**
     * Write a one-point calibration offset.
     * @param {number} offsetPa - Offset in pascals (signed; persisted in NVM).
     * @returns {Promise<void>}
     */
    async setPressureOffset(offsetPa) {
        const offsetHpa = offsetPa / 100.0;
        let raw = Math.round(offsetHpa * 4096.0);
        if (raw < 0) raw += 0x10000;
        await this._writeReg(_REG_RPDS_L, raw & 0xFF);
        await this._writeReg(_REG_RPDS_H, (raw >> 8) & 0xFF);
    }

    /**
     * Write a 15-bit unsigned pressure threshold.
     * @param {number} thresholdPa - Threshold in pascals.
     * @returns {Promise<void>}
     */
    async setPressureThreshold(thresholdPa) {
        const thresholdHpa = thresholdPa / 100.0;
        const raw = (Math.round(thresholdHpa * 16.0)) & 0x7FFF;
        await this._writeReg(_REG_THS_P_L, raw & 0xFF);
        await this._writeReg(_REG_THS_P_H, (raw >> 8) & 0xFF);
    }

    /**
     * Configure the INT pin and routing.
     * @returns {Promise<void>}
     */
    async configureInterrupt(intHL, ppOd, drdy, drdyPls, intEn, intFWtm, intFFull, intFOvr) {
        let ctrl3 = 0x01;  // IF_ADD_INC=1
        if (intHL) ctrl3 |= 0x08;
        if (ppOd)   ctrl3 |= 0x02;
        let ctrl4 = 0;
        if (drdyPls)  ctrl4 |= 0x40;
        if (drdy)      ctrl4 |= 0x20;
        if (intEn)     ctrl4 |= 0x10;
        if (intFFull)  ctrl4 |= 0x04;
        if (intFWtm)   ctrl4 |= 0x02;
        if (intFOvr)   ctrl4 |= 0x01;
        await this._writeReg(_REG_CTRL_REG3, ctrl3);
        await this._writeReg(_REG_CTRL_REG4, ctrl4);
    }

    /**
     * Configure pressure-event interrupts.
     * @returns {Promise<void>}
     */
    async configurePressureEvent(phe, ple, lir) {
        let cfg = 0;
        if (phe) cfg |= 0x01;
        if (ple) cfg |= 0x02;
        if (lir) cfg |= 0x04;
        await this._writeReg(_REG_INTERRUPT_CFG, cfg);
    }

    /** @returns {Promise<void>} */
    async autozero() {
        await this._writeReg(_REG_INTERRUPT_CFG, 0x20);
    }

    /** @returns {Promise<void>} */
    async autorefp() {
        await this._writeReg(_REG_INTERRUPT_CFG, 0x80);
    }

    /** @returns {Promise<void>} */
    async resetReference() {
        await this._writeReg(_REG_INTERRUPT_CFG, 0x50);
    }

    /**
     * Read the stored AUTOZERO/AUTOREFP reference pressure.
     * @returns {Promise<number>} Reference pressure in pascals.
     */
    async referencePressure() {
        const raw = await this._readReg(_REG_REF_P_L, 2);
        let value = raw[0] | (raw[1] << 8);
        if (value & 0x8000) value -= 0x10000;
        return (value / 4096.0) * 100.0;
    }

    /**
     * Set the FIFO mode.
     * @param {number} mode - 0=bypass, 1=FIFO, 2=continuous, 3=bypass-to-FIFO, 4=bypass-to-continuous, 5=continuous-to-FIFO.
     * @returns {Promise<void>}
     */
    async setFifoMode(mode) {
        let trig = 0, fm = 0;
        if (mode === 0)      { trig = 0; fm = 0; }
        else if (mode === 1) { trig = 0; fm = 1; }
        else if (mode === 2) { trig = 0; fm = 2; }
        else if (mode === 3) { trig = 1; fm = 1; }
        else if (mode === 4) { trig = 1; fm = 2; }
        else                { trig = 1; fm = 3; }
        await this._writeReg(_REG_FIFO_CTRL, (trig << 2) | (fm & 0x03));
    }

    /**
     * Set the FIFO watermark level.
     * @param {number} level - 0..127.
     * @returns {Promise<void>}
     */
    async setFifoWatermark(level) {
        await this._writeReg(_REG_FIFO_WTM, level & 0x7F);
    }

    /**
     * Read the FIFO sample count.
     * @returns {Promise<number>} Stored FIFO samples (0..127).
     */
    async fifoSampleCount() {
        const v = await this._readReg(_REG_FIFO_STATUS1, 1);
        return v[0];
    }

    /**
     * Read every available FIFO sample.
     * @returns {Promise<number[]>} Pressure readings in pascals, oldest first.
     */
    async readFifo() {
        const count = await this.fifoSampleCount();
        if (count === 0) return [];
        const raw = await this._readReg(_REG_FIFO_PRESS_XL, count * 3);
        const out = new Array(count);
        for (let i = 0; i < count; i++) {
            const base = i * 3;
            let value = raw[base] | (raw[base + 1] << 8) | (raw[base + 2] << 16);
            if (value & 0x800000) value -= 0x1000000;
            out[i] = (value / 4096.0) * 100.0;
        }
        return out;
    }

    /**
     * Read and clear the INT_SOURCE register.
     * @returns {Promise<{boot_on:boolean,ia:boolean,ph:boolean,pl:boolean}>}
     */
    async interruptSource() {
        const raw = await this._readReg(_REG_INT_SOURCE, 1);
        const v = raw[0];
        return {
            boot_on: !!(v & 0x80),
            ia:      !!(v & 0x04),
            ph:      !!(v & 0x01),
            pl:      !!(v & 0x02),
        };
    }
}

module.exports = { LPS22DFMinimal, LPS22DFFull };