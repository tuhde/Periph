'use strict';

const _REG_CHIP_ID       = 0x01;
const _REG_REV_ID        = 0x02;
const _REG_INT_STATUS    = 0x27;
const _REG_STATUS        = 0x28;
const _REG_INT_SOURCE    = 0x15;
const _REG_INT_CONFIG    = 0x14;
const _REG_FIFO_SEL      = 0x18;
const _REG_FIFO_CONFIG   = 0x16;
const _REG_FIFO_COUNT    = 0x17;
const _REG_FIFO_DATA     = 0x29;
const _REG_DSP_CONFIG    = 0x30;
const _REG_DSP_IIR       = 0x31;
const _REG_OOR_THR_P_LSB = 0x32;
const _REG_OOR_THR_P_MSB = 0x33;
const _REG_OOR_RANGE     = 0x34;
const _REG_OOR_CONFIG    = 0x35;
const _REG_OSR_CONFIG    = 0x36;
const _REG_ODR_CONFIG    = 0x37;
const _REG_OSR_EFF       = 0x38;
const _REG_NVM_ADDR      = 0x2B;
const _REG_NVM_DATA_LSB  = 0x2C;
const _REG_NVM_DATA_MSB  = 0x2D;
const _REG_TEMP_XLSB     = 0x1D;
const _REG_PRESS_XLSB    = 0x20;
const _REG_CMD           = 0x7E;

const _CHIP_ID_EXPECTED  = 0x50;
const _SOFT_RESET_CMD    = 0xB6;
const _STATUS_NVM_RDY    = 0x02;
const _STATUS_NVM_ERR    = 0x04;
const _INT_STATUS_DRDY   = 0x01;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

function _u24(data, offset) {
    let raw = (data[offset + 2] << 16) | (data[offset + 1] << 8) | data[offset];
    if (raw & 0x800000) raw -= 0x1000000;
    return raw;
}

/**
 * BMP581 MEMS barometric pressure + temperature sensor — minimal interface.
 *
 * Provides calibrated temperature (°C) and pressure (Pa) with no
 * configuration beyond the connection. I²C address is 0x46 (SDO=GND)
 * or 0x47 (SDO=VDDIO).
 *
 * Default: NORMAL mode, ODR 1 Hz, press_en=1, osr_p=x1, osr_t=x1,
 * IIR bypass, FIFO disabled, INT_SOURCE=0.
 *
 * Constructor caveat: JS constructors cannot be async. The init sequence
 * (chip-ID check, soft reset, status poll) is fired off unawaited,
 * matching the fire-and-forget convention used in the BMP280 driver;
 * in practice these settle before any subsequent `await` in caller code
 * is scheduled since the underlying bus is synchronous, but call
 * `await sensor.temperature()` (or any other async method) once before
 * relying on calibrated readings if this matters.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BMP581Minimal {
    constructor(connection, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._odr = 0x1C;
        this._pwrMode = 0x01;
        this._osrP = 0;
        this._osrT = 0;
        this._pressEn = true;
        this._init();
    }

    async _init() {
        try {
            const cid = (await this._readReg(_REG_CHIP_ID, 1))[0];
            if (cid !== _CHIP_ID_EXPECTED) return;
            for (let i = 0; i < 50; i++) {
                const st = (await this._readReg(_REG_STATUS, 1))[0];
                if ((st & _STATUS_NVM_RDY) && !(st & _STATUS_NVM_ERR)) break;
                _delay(2);
            }
            await this._readReg(_REG_INT_STATUS, 1);
            try {
                await this._writeReg(_REG_CMD, _SOFT_RESET_CMD);
            } catch (e) { /* expected NACK */ }
            _delay(2);
            for (let i = 0; i < 50; i++) {
                const st = (await this._readReg(_REG_STATUS, 1))[0];
                if ((st & _STATUS_NVM_RDY) && !(st & _STATUS_NVM_ERR)) break;
                _delay(2);
            }
            await this._readReg(_REG_INT_STATUS, 1);
            await this._writeReg(_REG_OSR_CONFIG, 0x40);
            await this._writeReg(_REG_ODR_CONFIG, 0x71);
        } catch (e) { /* bus may be idle */ }
    }

    async _writeReg(reg, value) {
        const addr = this._busType === 'spi' ? (reg & 0x7F) : reg;
        await this._conn.write(Buffer.from([addr, value & 0xFF]));
    }

    async _readReg(reg, n) {
        return this._conn.writeRead(Buffer.from([reg]), n);
    }

    async _spiDummyRead() {
        if (this._busType !== 'spi') return;
        try {
            await this._conn.write(Buffer.from([_REG_CHIP_ID | 0x80]));
            await this._conn.read(1);
        } catch (e) { /* ignore */ }
    }

    async _waitForced() {
        if (this._pwrMode !== 2) return;
        for (let i = 0; i < 200; i++) {
            const st = (await this._readReg(_REG_INT_STATUS, 1))[0];
            if (st & _INT_STATUS_DRDY) return;
            _delay(5);
        }
    }

    /**
     * Read calibrated pressure.
     * @returns {Promise<number>} Pressure in Pa.
     */
    async pressure() {
        await this._waitForced();
        const buf = await this._readReg(_REG_PRESS_XLSB, 3);
        return _u24(buf, 0) / 64.0;
    }

    /**
     * Read calibrated temperature.
     * @returns {Promise<number>} Temperature in degrees Celsius.
     */
    async temperature() {
        await this._waitForced();
        const buf = await this._readReg(_REG_TEMP_XLSB, 3);
        return _u24(buf, 0) / 65536.0;
    }

    /**
     * Read both pressure and temperature atomically in a single burst.
     * @returns {Promise<{pressure: number, temperature: number}>}
     */
    async both() {
        await this._waitForced();
        const buf = await this._readReg(_REG_TEMP_XLSB, 6);
        return {
            pressure: _u24(buf, 3) / 64.0,
            temperature: _u24(buf, 0) / 65536.0,
        };
    }
}

/**
 * BMP581 full interface — extends BMP581Minimal with configuration, FIFO,
 * interrupts, OOR detection, and NVM access.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BMP581Full extends BMP581Minimal {
    static OSR_1X   = 0;
    static OSR_2X   = 1;
    static OSR_4X   = 2;
    static OSR_8X   = 3;
    static OSR_16X  = 4;
    static OSR_32X  = 5;
    static OSR_64X  = 6;
    static OSR_128X = 7;

    static MODE_STANDBY    = 0;
    static MODE_NORMAL     = 1;
    static MODE_FORCED     = 2;
    static MODE_CONTINUOUS = 3;

    static IIR_BYPASS    = 0;
    static IIR_COEFF_1   = 1;
    static IIR_COEFF_3   = 2;
    static IIR_COEFF_7   = 3;
    static IIR_COEFF_15  = 4;
    static IIR_COEFF_31  = 5;
    static IIR_COEFF_63  = 6;
    static IIR_COEFF_127 = 7;

    static FIFO_DISABLED = 0;
    static FIFO_TEMP     = 1;
    static FIFO_PRESS    = 2;
    static FIFO_BOTH     = 3;

    static FIFO_STREAM       = 0;
    static FIFO_STOP_ON_FULL = 1;

    static INT_SOURCE_DRDY       = 0x01;
    static INT_SOURCE_FIFO_FULL  = 0x02;
    static INT_SOURCE_FIFO_THS   = 0x04;
    static INT_SOURCE_OOR_P      = 0x08;

    constructor(connection, busType = 'i2c') {
        super(connection, busType);
    }

    /**
     * Write OSR_CONFIG and ODR_CONFIG atomically.
     * @param {number} odr - ODR field 0x00-0x1F (default 0x1C = 1 Hz).
     * @param {number} osrP - Pressure oversampling 0-7 (default 0 = x1).
     * @param {number} osrT - Temperature oversampling 0-7 (default 0 = x1).
     * @param {boolean} pressEn - True to enable pressure measurements.
     * @returns {Promise<void>}
     */
    async configure(odr, osrP, osrT, pressEn) {
        this._odr = odr;
        this._osrP = osrP;
        this._osrT = osrT;
        this._pressEn = pressEn;
        const osr = (pressEn ? 0x40 : 0) | ((osrP & 0x7) << 3) | (osrT & 0x7);
        await this._writeReg(_REG_OSR_CONFIG, osr);
        const odrByte = ((odr & 0x1F) << 2) | (this._pwrMode & 0x3);
        await this._writeReg(_REG_ODR_CONFIG, odrByte);
    }

    /**
     * Set the power mode (preserves the current ODR setting).
     * @param {number} mode MODE_STANDBY (0), MODE_NORMAL (1), MODE_FORCED (2), MODE_CONTINUOUS (3).
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        this._pwrMode = mode;
        const odrByte = ((this._odr & 0x1F) << 2) | (mode & 0x3);
        await this._writeReg(_REG_ODR_CONFIG, odrByte);
    }

    /**
     * Trigger a single FORCED measurement, wait for completion, return readings.
     * @returns {Promise<{pressure: number, temperature: number}>}
     */
    async forced() {
        const prev = this._pwrMode;
        if (prev !== 2) await this.setMode(2);
        for (let i = 0; i < 400; i++) {
            const st = (await this._readReg(_REG_INT_STATUS, 1))[0];
            if (st & _INT_STATUS_DRDY) break;
            _delay(5);
        }
        const buf = await this._readReg(_REG_TEMP_XLSB, 6);
        return {
            pressure: _u24(buf, 3) / 64.0,
            temperature: _u24(buf, 0) / 65536.0,
        };
    }

    /**
     * Compute altitude from the current pressure reading.
     * @param {number} [seaLevelPa=101325] - Reference pressure in Pa.
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelPa = 101325) {
        const p = await this.pressure();
        if (p <= 0) return 0.0;
        return 44330 * (1 - Math.pow(p / seaLevelPa, 1 / 5.255));
    }

    /** Issue a soft reset and re-initialise the chip. @returns {Promise<void>} */
    async softwareReset() {
        try {
            await this._writeReg(_REG_CMD, _SOFT_RESET_CMD);
        } catch (e) { /* expected NACK */ }
        _delay(2);
        await this._init();
    }

    /**
     * Read CHIP_ID.
     * @returns {Promise<number>} 0x50 for a genuine BMP581.
     */
    async chipId() {
        const buf = await this._readReg(_REG_CHIP_ID, 1);
        return buf[0];
    }

    /**
     * Read REV_ID.
     * @returns {Promise<number>} ASIC revision identifier.
     */
    async revId() {
        const buf = await this._readReg(_REG_REV_ID, 1);
        return buf[0];
    }

    /**
     * Read STATUS.
     * @returns {Promise<number>} Raw status byte.
     */
    async status() {
        const buf = await this._readReg(_REG_STATUS, 1);
        return buf[0];
    }

    /**
     * Read INT_STATUS (clear-on-read).
     * @returns {Promise<number>} Raw interrupt status byte.
     */
    async interruptStatus() {
        const buf = await this._readReg(_REG_INT_STATUS, 1);
        return buf[0];
    }

    /**
     * Check whether a new data sample is available.
     * @returns {Promise<boolean>} True if drdy_data_reg is set.
     */
    async dataReady() {
        return (await this.interruptStatus() & _INT_STATUS_DRDY) !== 0;
    }

    /**
     * Configure INT pin: latching, polarity, drive mode, pin enable.
     * @param {number} mode - 0 = pulsed, 1 = latched.
     * @param {number} polarity - 0 = active-low, 1 = active-high.
     * @param {boolean} openDrain - true for open-drain output.
     * @param {boolean} enable - true to enable the INT pin driver.
     * @returns {Promise<void>}
     */
    async configureInterrupt(mode, polarity, openDrain, enable) {
        let val = enable ? 0x08 : 0;
        if (openDrain) val |= 0x04;
        if (polarity)   val |= 0x02;
        if (mode)       val |= 0x01;
        await this._writeReg(_REG_INT_CONFIG, val);
    }

    async _setIntSource(source, enable) {
        const buf = await this._readReg(_REG_INT_SOURCE, 1);
        let cur = buf[0];
        if (enable) cur |= source;
        else        cur &= ~source;
        await this._writeReg(_REG_INT_SOURCE, cur);
    }

    /** Enable/disable the data-ready interrupt source. @returns {Promise<void>} */
    async enableDrdyInterrupt(enable) {
        return this._setIntSource(BMP581Full.INT_SOURCE_DRDY, enable);
    }

    /** Enable/disable FIFO threshold and FIFO-full interrupt sources. @returns {Promise<void>} */
    async enableFifoInterrupt(threshold, full) {
        const buf = await this._readReg(_REG_INT_SOURCE, 1);
        let cur = buf[0] & ~(BMP581Full.INT_SOURCE_FIFO_FULL | BMP581Full.INT_SOURCE_FIFO_THS);
        if (threshold) cur |= BMP581Full.INT_SOURCE_FIFO_THS;
        if (full)      cur |= BMP581Full.INT_SOURCE_FIFO_FULL;
        await this._writeReg(_REG_INT_SOURCE, cur);
    }

    /** Enable/disable the pressure out-of-range interrupt source. @returns {Promise<void>} */
    async enableOorInterrupt(enable) {
        return this._setIntSource(BMP581Full.INT_SOURCE_OOR_P, enable);
    }

    /**
     * Set IIR filter coefficients for pressure and temperature.
     * Also sets shdw_sel_iir_p/t in DSP_CONFIG.
     * @param {number} coeffP - Pressure filter coefficient 0-7.
     * @param {number} coeffT - Temperature filter coefficient 0-7.
     * @returns {Promise<void>}
     */
    async setIirFilter(coeffP, coeffT) {
        const buf = await this._readReg(_REG_DSP_CONFIG, 1);
        let dsp = buf[0] | 0x28;
        await this._writeReg(_REG_DSP_CONFIG, dsp);
        const iirVal = ((coeffP & 0x7) << 3) | (coeffT & 0x7);
        await this._writeReg(_REG_DSP_IIR, iirVal);
    }

    /**
     * Configure FIFO source, mode, and threshold. Must be called in STANDBY mode.
     * @param {number} frameSel - FIFO_DISABLED (0), FIFO_TEMP (1), FIFO_PRESS (2), FIFO_BOTH (3).
     * @param {number} mode - FIFO_STREAM (0) or FIFO_STOP_ON_FULL (1).
     * @param {number} threshold - 0-31 frames (0 = disabled).
     * @returns {Promise<void>}
     */
    async configureFifo(frameSel, mode, threshold) {
        const prev = this._pwrMode;
        if (prev !== 0) await this.setMode(0);
        await this._writeReg(_REG_FIFO_SEL, frameSel & 0x3);
        await this._writeReg(_REG_FIFO_CONFIG, ((mode & 0x1) << 5) | (threshold & 0x1F));
        if (prev !== 0) await this.setMode(prev);
    }

    /**
     * Read the number of frames currently in the FIFO.
     * @returns {Promise<number>} Frame count 0-32.
     */
    async fifoCount() {
        const buf = await this._readReg(_REG_FIFO_COUNT, 1);
        return buf[0] & 0x3F;
    }

    /**
     * Read OSR_EFF.
     * @returns {Promise<{osrP: number, osrT: number}>}
     */
    async effectiveOsr() {
        const buf = await this._readReg(_REG_OSR_EFF, 1);
        return {
            osrP: (buf[0] >> 3) & 0x7,
            osrT: buf[0] & 0x7,
        };
    }

    /**
     * Check whether the current ODR/OSR combination is valid.
     * @returns {Promise<boolean>}
     */
    async odrIsValid() {
        const buf = await this._readReg(_REG_OSR_EFF, 1);
        return (buf[0] & 0x80) !== 0;
    }

    /**
     * Configure the out-of-range pressure detector.
     * @param {number} thresholdPa - Pressure threshold in Pa.
     * @param {number} rangePa - Symmetric +/- window around the threshold in Pa.
     * @param {number} countLimit - 0-3 successive over-threshold events required.
     * @returns {Promise<void>}
     */
    async setOorThreshold(thresholdPa, rangePa, countLimit) {
        let thr17 = Math.floor(thresholdPa * 64.0) >> 7;
        await this._writeReg(_REG_OOR_THR_P_LSB, thr17 & 0xFF);
        await this._writeReg(_REG_OOR_THR_P_MSB, (thr17 >> 8) & 0xFF);
        const range8 = (Math.floor(rangePa * 64.0) >> 7) & 0xFF;
        await this._writeReg(_REG_OOR_RANGE, range8);
        const oorCfg = ((countLimit & 0x3) << 6) | ((thr17 >> 16) & 0x01);
        await this._writeReg(_REG_OOR_CONFIG, oorCfg);
    }

    /**
     * Read one user NVM row.
     * @param {number} row - NVM row address 0x20-0x22.
     * @returns {Promise<number>} 16-bit value.
     */
    async nvmRead(row) {
        const prev = this._pwrMode;
        if (prev !== 0) await this.setMode(0);
        try {
            await this._writeReg(_REG_NVM_ADDR, 0x5D);
            await this._writeReg(_REG_CMD, 0xA5);
            _delay(2);
            await this._writeReg(_REG_NVM_ADDR, 0x40 | (row & 0x3F));
            await this._writeReg(_REG_CMD, 0xA5);
            _delay(2);
            const buf = await this._readReg(_REG_NVM_DATA_LSB, 2);
            return (buf[1] << 8) | buf[0];
        } finally {
            if (prev !== 0) await this.setMode(prev);
        }
    }

    /**
     * Write one user NVM row. Limited to 10,000 total write cycles.
     * @param {number} row - NVM row address 0x20-0x22.
     * @param {number} value - 16-bit value to store.
     * @returns {Promise<void>}
     */
    async nvmWrite(row, value) {
        const prev = this._pwrMode;
        if (prev !== 0) await this.setMode(0);
        try {
            await this._writeReg(_REG_NVM_ADDR, 0x40 | (row & 0x3F));
            await this._writeReg(_REG_NVM_DATA_LSB, value & 0xFF);
            await this._writeReg(_REG_NVM_DATA_MSB, (value >> 8) & 0xFF);
            await this._writeReg(_REG_NVM_ADDR, 0x5D);
            await this._writeReg(_REG_CMD, 0xA0);
            _delay(5);
        } finally {
            if (prev !== 0) await this.setMode(prev);
        }
    }
}

module.exports = { BMP581Minimal, BMP581Full };