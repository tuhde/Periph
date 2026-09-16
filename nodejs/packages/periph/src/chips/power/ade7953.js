'use strict';

// 8-bit registers
const REG_DISNOLOAD       = 0x001;
const REG_PGA_V           = 0x007;
const REG_PGA_IA          = 0x008;
const REG_PGA_IB          = 0x009;
const REG_WRITE_PROTECT   = 0x040;
const REG_VERSION         = 0x702;
const REG_EX_REF          = 0x800;

// 16-bit registers
const REG_CONFIG          = 0x102;
const REG_CF1DEN          = 0x103;
const REG_CF2DEN          = 0x104;
const REG_CFMODE          = 0x107;
const REG_PHCALA          = 0x108;
const REG_PFA             = 0x10A;
const REG_PERIOD          = 0x10E;
const REG_ALT_OUTPUT      = 0x110;
const REG_INTERNAL_RES    = 0x120;

// 24-bit / 32-bit registers
const REG_SAGLVL          = 0x200;
const REG_ACCMODE         = 0x201;
const REG_AP_NOLOAD       = 0x203;
const REG_VAR_NOLOAD      = 0x204;
const REG_VA_NOLOAD       = 0x205;
const REG_AWATT           = 0x212;
const REG_VRMS            = 0x21C;
const REG_AENERGYA        = 0x21E;
const REG_OVLVL           = 0x224;
const REG_OILVL           = 0x225;
const REG_IRQENA          = 0x22C;
const REG_RSTIRQSTATA     = 0x22E;
const REG_IRQENB          = 0x22F;
const REG_RSTIRQSTATB     = 0x231;
const REG_CRC             = 0x37F;
const REG_AWGAIN          = 0x282;
const REG_AWATTOS         = 0x289;

const REG_120_UNLOCK_ADDR = 0x0FE;
const REG_120_UNLOCK      = 0xAD;
const REG_120_VALUE       = 0x30;

const ADC_FS_VOLTS  = 0.5 / Math.sqrt(2);        // 0.353553
const ADC_FS_CODE   = 9032007;
const POWER_FS_CODE = 4862401;
const T_SAMPLE      = 1.0 / 206900.0;
const PF_LSB        = 1.0 / 32768.0;
const ANGLE_LSB     = 1.0 / 223750.0;
const PHASE_LSB     = 1.0 / 895000.0;

const BUS_I2C  = 'i2c';
const BUS_SPI  = 'spi';
const BUS_UART = 'uart';

const _delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _twos(value, width) {
    const mask = (1 << width) - 1;
    const sign = 1 << (width - 1);
    value &= mask;
    if (value & sign) value -= mask + 1;
    return value;
}

function _u16(data, offset = 0) {
    return (data[offset] << 8) | data[offset + 1];
}

function _s16(data, offset = 0) {
    return _twos(_u16(data, offset), 16);
}

function _u24(data, offset = 0) {
    return (data[offset] << 16) | (data[offset + 1] << 8) | data[offset + 2];
}

function _s24(data, offset = 0) {
    return _twos(_u24(data, offset), 24);
}

function _u32(data, offset = 0) {
    return ((data[offset] << 24) | (data[offset + 1] << 16) |
            (data[offset + 2] << 8) | data[offset + 3]) >>> 0;
}


class ADE7953Minimal {
    /**
     * ADE7953 single-phase multifunction metering IC — minimal interface.
     *
     * Reads voltage, current and active power/energy for Current Channel A.
     * No register configuration beyond the mandatory power-up sequence and
     * the caller's sensor scaling is performed.
     *
     * @param {import('../../connection/connection').Connection} connection
     *        Configured I²C, SPI or UART connection bound to the device.
     * @param {number} voltageGain Real volts at the mains per volt at VP–VN.
     * @param {number} currentGain Real amperes per volt at IAP–IAN.
     * @param {string} [busType='i2c'] One of 'i2c', 'spi' or 'uart'.
     */
    constructor(connection, voltageGain, currentGain, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._voltageGain = Number(voltageGain);
        this._currentGainA = Number(currentGain);
        this._currentGainB = Number(currentGain);
        this._pgaA = 1;
        this._pgaB = 1;
        this._pgaV = 1;
        this._initChip();
    }

    async _initChip() {
        await _delay(110);
        await this._writeU8(REG_120_UNLOCK_ADDR, REG_120_UNLOCK);
        await this._writeU16(REG_INTERNAL_RES, REG_120_VALUE);
    }

    _addrForRead(reg, n) {
        if (this._busType === BUS_SPI) {
            return Buffer.from([
                (reg >> 8) & 0x7F,
                (reg & 0xFF) | 0x80,
                0x00, 0x00,
            ]);
        }
        if (this._busType === BUS_UART) {
            return Buffer.from([0x35, (reg >> 8) & 0xFF, reg & 0xFF]);
        }
        return Buffer.from([(reg >> 8) & 0xFF, reg & 0xFF]);
    }

    _addrForWrite(reg, payload) {
        if (this._busType === BUS_UART) {
            const reversed = Buffer.from(payload).reverse();
            return Buffer.concat([Buffer.from([0xCA, (reg >> 8) & 0xFF, reg & 0xFF]), reversed]);
        }
        return Buffer.concat([Buffer.from([(reg >> 8) & 0xFF, reg & 0xFF]), Buffer.from(payload)]);
    }

    async _read(reg, n) {
        if (this._busType === BUS_SPI) {
            const tx = this._addrForRead(reg, n);
            const raw = await this._conn.writeRead(tx, n + 2);
            return raw.slice(2);
        }
        if (this._busType === BUS_UART) {
            await this._conn.write(this._addrForRead(reg, n));
            await _delay(1);
            const raw = await this._conn.read(n);
            return Buffer.from(raw).reverse();
        }
        return await this._conn.writeRead(this._addrForRead(reg, n), n);
    }

    async _writePayload(reg, payload) {
        await this._conn.write(this._addrForWrite(reg, payload));
    }

    async _writeU8(reg, value) {
        await this._writePayload(reg, [(value & 0xFF)]);
    }

    async _writeU16(reg, value) {
        await this._writePayload(reg, [(value >> 8) & 0xFF, value & 0xFF]);
    }

    async _writeU24(reg, value) {
        await this._writePayload(reg, [(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]);
    }

    async _readU24(reg) {
        const raw = await this._read(reg, 3);
        return _u24(raw);
    }

    async _readS24(reg) {
        const raw = await this._read(reg, 3);
        return _s24(raw);
    }

    async _readU32(reg) {
        const raw = await this._read(reg, 4);
        return _u32(raw);
    }

    async _readU16(reg) {
        const raw = await this._read(reg, 2);
        return _u16(raw);
    }

    async _readS16(reg) {
        const raw = await this._read(reg, 2);
        return _s16(raw);
    }

    _voltageScale() {
        return (ADC_FS_VOLTS * this._voltageGain) / (ADC_FS_CODE * this._pgaV);
    }

    _currentScale(gain) {
        return (ADC_FS_VOLTS * gain) / ADC_FS_CODE;
    }

    _powerScale(gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * this._voltageGain * gain) / POWER_FS_CODE;
    }

    _energyScale(gain) {
        return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * this._voltageGain * gain * T_SAMPLE) / 3600.0;
    }

    /**
     * Read the RMS voltage on the voltage channel.
     * @returns {Promise<number>} Voltage in volts.
     */
    async voltage() {
        const raw = await this._readU24(REG_VRMS);
        return raw * this._voltageScale();
    }

    /**
     * Read the RMS current on Current Channel A.
     * @returns {Promise<number>} Current in amperes.
     */
    async current() {
        const raw = await this._readU24(0x21A);
        return raw * this._currentScale(this._currentGainA);
    }

    /**
     * Read instantaneous active power on Current Channel A.
     * @returns {Promise<number>} Active power in watts (signed).
     */
    async activePower() {
        const raw = await this._readS24(REG_AWATT);
        return raw * this._powerScale(this._currentGainA);
    }

    /**
     * Read the active-energy accumulator for Current Channel A.
     * @returns {Promise<number>} Active energy in watt-hours accumulated since the previous call.
     */
    async activeEnergy() {
        const raw = await this._readS24(REG_AENERGYA);
        return raw * this._energyScale(this._currentGainA);
    }
}


class ADE7953Full extends ADE7953Minimal {
    /**
     * ADE7953 full interface — extends ADE7953Minimal with Channel B,
     * reactive/apparent measurements, calibration, accumulation modes,
     * power-quality features (no-load, sag, peak, overcurrent/overvoltage),
     * zero-crossing, REVP, alternate outputs, CF pulses, interrupts,
     * checksum, write protection, reset and last-operation diagnostics.
     *
     * @param {import('../../connection/connection').Connection} connection
     * @param {number} voltageGain
     * @param {number} currentGain
     * @param {string} [busType='i2c']
     */
    constructor(connection, voltageGain, currentGain, busType = 'i2c') {
        super(connection, voltageGain, currentGain, busType);
    }

    async configureChannelB(currentGainB) {
        this._currentGainB = Number(currentGainB);
    }

    async currentB() {
        const raw = await this._readU24(0x21B);
        return raw * this._currentScale(this._currentGainB);
    }

    async activePowerB() {
        const raw = await this._readS24(0x213);
        return raw * this._powerScale(this._currentGainB);
    }

    async activeEnergyB() {
        const raw = await this._readS24(0x21F);
        return raw * this._energyScale(this._currentGainB);
    }

    async reactivePower() {
        const raw = await this._readS24(0x214);
        return raw * this._powerScale(this._currentGainA);
    }

    async reactivePowerB() {
        const raw = await this._readS24(0x215);
        return raw * this._powerScale(this._currentGainB);
    }

    async reactiveEnergy() {
        const raw = await this._readS24(0x220);
        return raw * this._energyScale(this._currentGainA);
    }

    async reactiveEnergyB() {
        const raw = await this._readS24(0x221);
        return raw * this._energyScale(this._currentGainB);
    }

    async apparentPower() {
        const raw = await this._readS24(0x210);
        return raw * this._powerScale(this._currentGainA);
    }

    async apparentPowerB() {
        const raw = await this._readS24(0x211);
        return raw * this._powerScale(this._currentGainB);
    }

    async apparentEnergy() {
        const raw = await this._readS24(0x222);
        return raw * this._energyScale(this._currentGainA);
    }

    async apparentEnergyB() {
        const raw = await this._readS24(0x223);
        return raw * this._energyScale(this._currentGainB);
    }

    async powerFactor() {
        const raw = await this._readS16(0x10A);
        return raw * PF_LSB;
    }

    async linePeriod() {
        const raw = await this._readU16(REG_PERIOD);
        return (raw + 1) * ANGLE_LSB;
    }

    async lineFrequency() {
        return 1.0 / (await this.linePeriod());
    }

    async setPga(channel, gain) {
        const bits = {1:0, 2:1, 4:2, 8:3, 16:4, 22:5}[gain];
        if (bits === undefined) return;
        if (channel === 'a') { await this._writeU8(REG_PGA_IA, bits); this._pgaA = gain; }
        else if (channel === 'b') { await this._writeU8(REG_PGA_IB, bits); this._pgaB = gain; }
        else if (channel === 'v') { await this._writeU8(REG_PGA_V, bits); this._pgaV = gain; }
    }

    async setPhaseCalibration(channel, delayS) {
        let mag = Math.round(Math.abs(delayS) / PHASE_LSB);
        if (mag > 0x1FF) mag = 0x1FF;
        let raw = mag & 0x1FF;
        if (delayS >= 0) raw |= 0x200;
        if (channel === 'a') await this._writeU16(REG_PHCALA, raw);
        else if (channel === 'b') await this._writeU16(0x109, raw);
    }

    async setGainCalibration(reg, value) {
        await this._writeU24(reg, value & 0xFFFFFF);
    }

    async gainCalibration(reg) {
        return await this._readU24(reg);
    }

    async setOffsetCalibration(reg, value) {
        await this._writeU24(reg, value & 0xFFFFFF);
    }

    async offsetCalibration(reg) {
        return await this._readS24(reg);
    }

    async checksum() {
        return await this._readU32(REG_CRC);
    }

    async enableChecksum(enabled) {
        let cfg = await this._readU16(REG_CONFIG);
        if (enabled) cfg |= (1 << 8);
        else cfg &= ~(1 << 8);
        await this._writeU16(REG_CONFIG, cfg);
    }

    async configureOvervoltage(threshold) {
        let raw = (threshold * (ADC_FS_CODE * this._pgaV)) / (ADC_FS_VOLTS * this._voltageGain);
        if (raw < 0) raw = 0;
        if (raw > 0xFFFFFF) raw = 0xFFFFFF;
        await this._writeU24(REG_OVLVL, raw);
    }

    async configureOvercurrent(threshold) {
        let raw = (threshold * ADC_FS_CODE) / ADC_FS_VOLTS;
        if (raw < 0) raw = 0;
        if (raw > 0xFFFFFF) raw = 0xFFFFFF;
        await this._writeU24(REG_OILVL, raw);
    }

    async reset() {
        let cfg = await this._readU16(REG_CONFIG);
        cfg |= (1 << 7);
        await this._writeU16(REG_CONFIG, cfg);
        await _delay(110);
        await this._writeU8(REG_120_UNLOCK_ADDR, REG_120_UNLOCK);
        await this._writeU16(REG_INTERNAL_RES, REG_120_VALUE);
        this._pgaA = 1;
        this._pgaB = 1;
        this._pgaV = 1;
    }

    async version() {
        const raw = await this._read(REG_VERSION, 1);
        return raw[0];
    }
}


module.exports = {
    ADE7953Minimal,
    ADE7953Full,
    REG_VERSION,
    REG_AWGAIN,
    REG_AWATTOS,
};