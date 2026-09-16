'use strict';

const _REG_CONFIG_A   = 0x00;
const _REG_CONFIG_B   = 0x01;
const _REG_MODE       = 0x02;
const _REG_DATA_X_MSB = 0x03;
const _REG_STATUS     = 0x09;
const _REG_ID_A       = 0x0A;
const _REG_ID_B       = 0x0B;
const _REG_ID_C       = 0x0C;

const _GAIN_LSB_PER_GAUSS = {
    0: 1370,  // GN=0: ±0.88 Ga
    1: 1090,  // GN=1: ±1.3 Ga (default)
    2: 820,   // GN=2: ±1.9 Ga
    3: 660,   // GN=3: ±2.5 Ga
    4: 440,   // GN=4: ±4.0 Ga
    5: 390,   // GN=5: ±4.7 Ga
    6: 330,   // GN=6: ±5.6 Ga
    7: 230,   // GN=7: ±8.1 Ga
};

/**
 * HMC5883L 3-axis magnetometer — minimal interface.
 *
 * Reads magnetic field on all three axes in continuous mode with sensible
 * defaults baked in. No configuration required beyond the connection.
 *
 * Default behaviour (baked into Minimal):
 * - Averaging: 8 samples (MA=11)
 * - ODR: 15 Hz (DO=100)
 * - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
 * - Mode: continuous measurement
 */
class HMC5883LMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (fixed address 0x1E).
     */
    constructor(connection) {
        this._conn = connection;
        this._gain = 1;
        this._gainLsbPerGauss = _GAIN_LSB_PER_GAUSS[this._gain];
        this._initMinimal();
    }

    async _initMinimal() {
        await this._writeReg8(_REG_CONFIG_A, 0x70);
        await this._writeReg8(_REG_CONFIG_B, 0x20);
        await this._writeReg8(_REG_MODE, 0x00);
        // 6 ms delay for first measurement - caller should wait if needed
    }

    async _readReg8(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 1))[0];
    }

    async _readReg16(reg) {
        const raw = await this._conn.writeRead(Buffer.from([reg]), 2);
        return raw.readInt16BE(0);
    }

    async _writeReg8(reg, value) {
        const buf = Buffer.alloc(2);
        buf[0] = reg;
        buf[1] = value;
        await this._conn.write(buf);
    }

    async _readDataBurst() {
        const raw = await this._conn.writeRead(Buffer.from([_REG_DATA_X_MSB]), 6);
        const rawX = raw.readInt16BE(0);
        const rawZ = raw.readInt16BE(2);
        const rawY = raw.readInt16BE(4);
        return { rawX, rawY, rawZ };
    }

    _rawToTesla(raw) {
        if (raw === -4096) {
            return null;
        }
        return (raw / this._gainLsbPerGauss) * 1e-4;
    }

    /**
     * Read magnetic field on all three axes.
     * @returns {Promise<{x: number|null, y: number|null, z: number|null}>} X, Y, Z field strength in Tesla.
     * Returns null for any axis that overflows (raw == -4096).
     */
    async magneticField() {
        const { rawX, rawY, rawZ } = await this._readDataBurst();
        return {
            x: this._rawToTesla(rawX),
            y: this._rawToTesla(rawY),
            z: this._rawToTesla(rawZ),
        };
    }
}

/**
 * HMC5883L full interface — extends HMC5883LMinimal with complete chip functionality.
 *
 * Adds configuration, single-shot mode, self-test, identification, and status access.
 */
class HMC5883LFull extends HMC5883LMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (fixed address 0x1E).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Write Configuration Registers A and B.
     * @param {number} [odr=15] - Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
     * @param {number} [averaging=8] - Samples averaged per output. Valid: 1, 2, 4, 8.
     * @param {number} [gain=1] - Gain index 0–7.
     * @returns {Promise<void>}
     */
    async configure(odr = 15, averaging = 8, gain = 1) {
        const maMap = { 1: 0b00, 2: 0b01, 4: 0b10, 8: 0b11 };
        const doMap = { 0.75: 0b000, 1.5: 0b001, 3: 0b010, 7.5: 0b011,
                        15: 0b100, 30: 0b101, 75: 0b110 };

        if (!maMap[averaging]) throw new Error('averaging must be 1, 2, 4, or 8');
        if (!doMap[odr]) throw new Error('odr must be 0.75, 1.5, 3, 7.5, 15, 30, or 75');
        if (gain < 0 || gain > 7) throw new Error('gain must be 0–7');

        const ma = maMap[averaging];
        const doBits = doMap[odr];
        const configA = (ma << 5) | (doBits << 2);
        await this._writeReg8(_REG_CONFIG_A, configA);

        const configB = (gain << 5);
        await this._writeReg8(_REG_CONFIG_B, configB);

        this._gain = gain;
        this._gainLsbPerGauss = _GAIN_LSB_PER_GAUSS[gain];
    }

    /**
     * Update the gain setting (GN bits in Config B).
     * @param {number} gain - Gain index 0–7.
     * @returns {Promise<void>}
     */
    async setGain(gain) {
        if (gain < 0 || gain > 7) throw new Error('gain must be 0–7');
        await this._writeReg8(_REG_CONFIG_B, gain << 5);
        this._gain = gain;
        this._gainLsbPerGauss = _GAIN_LSB_PER_GAUSS[gain];
    }

    /**
     * Set the operating mode.
     * @param {string} mode - 'continuous', 'single', or 'idle'.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        const modeMap = { continuous: 0b00, single: 0b01, idle: 0b10 };
        if (!modeMap[mode]) throw new Error("mode must be 'continuous', 'single', or 'idle'");
        await this._writeReg8(_REG_MODE, modeMap[mode]);
    }

    /**
     * Check if new measurement data is ready.
     * @returns {Promise<boolean>} True if RDY bit is set in Status Register.
     */
    async dataReady() {
        const status = await this._readReg8(_REG_STATUS);
        return !!(status & 0x01);
    }

    /**
     * Read the raw Status Register.
     * @returns {Promise<number>} Raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
     */
    async status() {
        return this._readReg8(_REG_STATUS);
    }

    /**
     * Take a single measurement in single-shot mode.
     * @returns {Promise<{x: number|null, y: number|null, z: number|null}>} X, Y, Z field strength in Tesla.
     * Returns null for any axis that overflows (raw == -4096).
     */
    async singleMeasurement() {
        await this._writeReg8(_REG_MODE, 0x01);
        // Caller should wait 6 ms before reading
        return this.magneticField();
    }

    /**
     * Read the identification registers.
     * @returns {Promise<[number, number, number]>} (id_a, id_b, id_c) — expected (0x48, 0x34, 0x33) = ASCII 'H43'.
     */
    async identify() {
        const idA = await this._readReg8(_REG_ID_A);
        const idB = await this._readReg8(_REG_ID_B);
        const idC = await this._readReg8(_REG_ID_C);
        return [idA, idB, idC];
    }

    /**
     * Run self-test with positive or negative bias.
     * @param {boolean} [positive=true] - True for positive bias (MS=01), false for negative bias (MS=10).
     * @returns {Promise<{x: number|null, y: number|null, z: number|null}>} X, Y, Z field deflection in Tesla.
     * Returns null for any axis that overflows.
     */
    async selfTest(positive = true) {
        const configA = await this._readReg8(_REG_CONFIG_A);
        const ms = positive ? 0b01 : 0b10;
        await this._writeReg8(_REG_CONFIG_A, (configA & 0xFC) | ms);

        await this._writeReg8(_REG_MODE, 0x01);
        // Caller should wait 6 ms
        const result = await this.magneticField();

        await this._writeReg8(_REG_CONFIG_A, (configA & 0xFC) | 0b00);
        return result;
    }
}

module.exports = { HMC5883LMinimal, HMC5883LFull };