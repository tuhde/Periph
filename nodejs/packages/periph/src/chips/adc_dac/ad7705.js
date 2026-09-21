'use strict';

/**
 * AD7705 2-channel, 16-bit sigma-delta ADC driver (SPI).
 *
 * Two-phase register-access protocol: write the Communication Register
 * (selects target register + read/write direction + channel), then
 * transfer the data bytes in a single CS-held transaction. DRDY is
 * polled over SPI by inspecting bit 7 of the Communication Register,
 * matching the datasheet's 3-wire microcontroller interface technique
 * (no dedicated DRDY GPIO required).
 *
 * Minimal default configuration: gain 1, bipolar, unbuffered, 50 Hz
 * output rate on a 2.4576/4.9152 MHz clock (or 20 Hz on 1/2 MHz), with
 * Channel 1 self-calibrated once at construction.
 */

const MCLK_1MHZ      = 1000000;
const MCLK_2MHZ      = 2000000;
const MCLK_2_4576MHZ = 2457600;
const MCLK_4_9152MHZ = 4915200;
const VALID_MCLK = new Set([MCLK_1MHZ, MCLK_2MHZ, MCLK_2_4576MHZ, MCLK_4_9152MHZ]);

const REG_COMM    = 0x00;
const REG_SETUP   = 0x10;
const REG_CLOCK   = 0x20;
const REG_DATA    = 0x30;
const REG_OFFSET  = 0x60;
const REG_GAIN    = 0x70;

const RW_WRITE = 0x00;
const RW_READ  = 0x08;

const CH1 = 0x00;
const CH2 = 0x01;

const MODE_NORMAL   = 0x00;
const MODE_SELF_CAL = 0x40;
const MODE_ZERO_SYS = 0x80;
const MODE_FULL_SYS = 0xC0;

const GAIN_BITS = [0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38];
const GAIN_TO_IDX = { 1: 0, 2: 1, 4: 2, 8: 3, 16: 4, 32: 5, 64: 6, 128: 7 };

const BIPOLAR    = 0x00;
const UNIPOLAR   = 0x04;
const UNBUFFERED = 0x00;
const BUFFERED   = 0x02;
const FSYNC_RUN  = 0x00;
const STBY_RUN   = 0x00;
const STBY_SLEEP = 0x04;
const DRDY_MASK  = 0x80;

const FS_RATES_1MHZ   = [20, 25, 100, 200];
const FS_RATES_2_4MHZ = [50, 60, 250, 500];

function commByte(reg, read, channel) {
    return (reg | (read ? RW_READ : RW_WRITE) | (channel & 0x03)) & 0xFF;
}

async function waitDrdy(conn) {
    while (true) {
        const r = await conn.writeRead(Buffer.from([commByte(REG_COMM, true, CH1)]), 1);
        if (!(r[0] & DRDY_MASK)) return;
    }
}

async function writeRegChannel(conn, reg, value, channel, nBytes) {
    const buf = Buffer.alloc(1 + nBytes);
    buf[0] = commByte(reg, false, channel);
    for (let i = nBytes - 1; i >= 0; i--) {
        buf[1 + (nBytes - 1 - i)] = (value >> (8 * i)) & 0xFF;
    }
    await conn.write(buf);
}

async function readRegChannel(conn, reg, channel, nBytes) {
    const comm = Buffer.from([commByte(reg, true, channel)]);
    const raw = await conn.writeRead(comm, nBytes);
    let value = 0;
    for (let i = 0; i < nBytes; i++) {
        value = (value << 8) | raw[i];
    }
    return value;
}

async function configureClock(conn, mclkHz, outputRateHz) {
    const clkBit   = (mclkHz >= MCLK_2_4576MHZ) ? 0x04 : 0x00;
    const clkDivBit = (mclkHz === MCLK_2MHZ || mclkHz === MCLK_4_9152MHZ) ? 0x08 : 0x00;
    const rates = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ;
    const fsIdx = rates.indexOf(outputRateHz);
    await writeRegChannel(conn, REG_CLOCK, clkDivBit | clkBit | fsIdx, CH1, 1);
}

function codeToVoltage(code, gain, bipolar, vref) {
    if (bipolar) {
        return ((code - 32768) / 32768) * (vref / gain);
    }
    return (code / 65536) * (vref / gain);
}

/**
 * AD7705 minimal driver — Channel 1 voltage read with sensible defaults.
 */
class AD7705Minimal {
    /**
     * @param {object} connection - SPI connection bound to the device.
     * @param {number} vref - Externally-supplied reference voltage in V.
     * @param {number} mclkHz - Master clock frequency in Hz.
     * @param {object|null} [resetPin=null] - Optional OutputPin driving RESET.
     */
    constructor(connection, vref, mclkHz, resetPin = null) {
        if (!VALID_MCLK.has(mclkHz)) {
            throw new Error('mclkHz must be 1000000, 2000000, 2457600, or 4915200');
        }
        this._conn = connection;
        this._vref = Number(vref);
        this._mclkHz = mclkHz;
        this._resetPin = resetPin;
        this._gain = 1;
        this._bipolar = true;
        this._buffered = false;

        if (resetPin) {
            resetPin.set(false);
            resetPin.set(true);
        }

        const defaultRate = (mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ[0] : FS_RATES_1MHZ[0];
        configureClock(connection, mclkHz, defaultRate);

        const setup = MODE_SELF_CAL | GAIN_BITS[0] | BIPOLAR | UNBUFFERED | FSYNC_RUN;
        writeRegChannel(connection, REG_SETUP, setup, CH1, 1);
        return waitDrdy(connection);
    }

    /**
     * Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1.
     * @returns {Promise<number>} Raw 16-bit code (0–65535).
     */
    async readRaw() {
        await waitDrdy(this._conn);
        return await readRegChannel(this._conn, REG_DATA, CH1, 2);
    }

    /**
     * Block until DRDY, then return the input voltage on Channel 1 in V.
     * @returns {Promise<number>} Voltage in V.
     */
    async readVoltage() {
        const code = await this.readRaw();
        return codeToVoltage(code, this._gain, this._bipolar, this._vref);
    }
}

/**
 * AD7705 full driver — adds per-channel configuration, calibration, and power control.
 */
class AD7705Full extends AD7705Minimal {
    /**
     * Write the Setup and Clock Registers for the given channel.
     * Does not calibrate — call selfCalibrate() (or one of the
     * system-calibration methods) afterward.
     *
     * @param {number} channel - 1 or 2.
     * @param {number} gain - PGA gain: 1, 2, 4, 8, 16, 32, 64, or 128.
     * @param {boolean} bipolar - true for bipolar, false for unipolar.
     * @param {boolean} buffered - true to enable the analog input buffer.
     * @param {number} outputRateHz - One of the four rates in the mclkHz family.
     */
    async configure(channel, gain, bipolar, buffered, outputRateHz) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        if (!(gain in GAIN_TO_IDX)) {
            throw new Error('gain must be one of 1, 2, 4, 8, 16, 32, 64, 128');
        }
        const rates = (this._mclkHz >= MCLK_2_4576MHZ) ? FS_RATES_2_4MHZ : FS_RATES_1MHZ;
        if (!rates.includes(outputRateHz)) {
            throw new Error(`outputRateHz must be one of ${rates.join(', ')} Hz`);
        }
        const ch = (channel === 1) ? CH1 : CH2;

        await configureClock(this._conn, this._mclkHz, outputRateHz);

        const buBit = bipolar ? BIPOLAR : UNIPOLAR;
        const bufBit = buffered ? BUFFERED : UNBUFFERED;
        const setup = MODE_NORMAL | GAIN_BITS[GAIN_TO_IDX[gain]] | buBit | bufBit | FSYNC_RUN;
        await writeRegChannel(this._conn, REG_SETUP, setup, ch, 1);

        if (channel === 1) {
            this._gain = gain;
            this._bipolar = bipolar;
            this._buffered = buffered;
        }
    }

    /**
     * Block until DRDY, then read the raw 16-bit code for the channel.
     * @param {number} channel - 1 or 2.
     * @returns {Promise<number>} Raw 16-bit code (0–65535).
     */
    async readRawChannel(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        await waitDrdy(this._conn);
        return await readRegChannel(this._conn, REG_DATA, ch, 2);
    }

    /**
     * Block until DRDY, then return the input voltage on the channel in V.
     * @param {number} channel - 1 or 2.
     * @returns {Promise<number>} Voltage in V.
     */
    async readVoltageChannel(channel) {
        const code = await this.readRawChannel(channel);
        return codeToVoltage(code, this._gain, this._bipolar, this._vref);
    }

    /**
     * Run an internal self-calibration on the channel.
     * @param {number} channel - 1 or 2.
     */
    async selfCalibrate(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        const setup = MODE_SELF_CAL | GAIN_BITS[GAIN_TO_IDX[this._gain]] | (this._bipolar ? BIPOLAR : UNIPOLAR) | (this._buffered ? BUFFERED : UNBUFFERED) | FSYNC_RUN;
        await writeRegChannel(this._conn, REG_SETUP, setup, ch, 1);
        await waitDrdy(this._conn);
    }

    /**
     * Run a zero-scale system calibration. The caller must present the zero-scale voltage at AIN first.
     * @param {number} channel - 1 or 2.
     */
    async systemCalibrateZero(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        const setup = MODE_ZERO_SYS | GAIN_BITS[GAIN_TO_IDX[this._gain]] | (this._bipolar ? BIPOLAR : UNIPOLAR) | (this._buffered ? BUFFERED : UNBUFFERED) | FSYNC_RUN;
        await writeRegChannel(this._conn, REG_SETUP, setup, ch, 1);
        await waitDrdy(this._conn);
    }

    /**
     * Run a full-scale system calibration. The caller must present the full-scale voltage at AIN first.
     * @param {number} channel - 1 or 2.
     */
    async systemCalibrateFull(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        const setup = MODE_FULL_SYS | GAIN_BITS[GAIN_TO_IDX[this._gain]] | (this._bipolar ? BIPOLAR : UNIPOLAR) | (this._buffered ? BUFFERED : UNBUFFERED) | FSYNC_RUN;
        await writeRegChannel(this._conn, REG_SETUP, setup, ch, 1);
        await waitDrdy(this._conn);
    }

    /**
     * Read the 24-bit Zero-Scale Calibration Register for the channel.
     * @param {number} channel - 1 or 2.
     * @returns {Promise<number>} 24-bit unsigned offset coefficient.
     */
    async getOffsetCalibration(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        return await readRegChannel(this._conn, REG_OFFSET, ch, 3);
    }

    /**
     * Write a 24-bit Zero-Scale Calibration Register for the channel.
     * @param {number} value - 24-bit unsigned offset coefficient.
     * @param {number} channel - 1 or 2.
     */
    async setOffsetCalibration(value, channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        await writeRegChannel(this._conn, REG_OFFSET, value & 0xFFFFFF, ch, 3);
    }

    /**
     * Read the 24-bit Full-Scale Calibration Register for the channel.
     * @param {number} channel - 1 or 2.
     * @returns {Promise<number>} 24-bit unsigned gain coefficient.
     */
    async getGainCalibration(channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        return await readRegChannel(this._conn, REG_GAIN, ch, 3);
    }

    /**
     * Write a 24-bit Full-Scale Calibration Register for the channel.
     * @param {number} value - 24-bit unsigned gain coefficient.
     * @param {number} channel - 1 or 2.
     */
    async setGainCalibration(value, channel) {
        if (channel !== 1 && channel !== 2) {
            throw new Error('channel must be 1 or 2');
        }
        const ch = (channel === 1) ? CH1 : CH2;
        await writeRegChannel(this._conn, REG_GAIN, value & 0xFFFFFF, ch, 3);
    }

    /**
     * Enter standby (~10 µA, registers retained).
     */
    async standby() {
        const comm = Buffer.from([(commByte(REG_COMM, false, CH1) | STBY_SLEEP) & 0xFF]);
        await this._conn.write(comm);
    }

    /**
     * Exit standby and block until a fresh conversion is available.
     */
    async wakeup() {
        const comm = Buffer.from([(commByte(REG_COMM, false, CH1) | STBY_RUN) & 0xFF]);
        await this._conn.write(comm);
        await waitDrdy(this._conn);
    }

    /**
     * Pulse the hardware RESET line. Requires a resetPin to have been supplied.
     */
    async reset() {
        if (!this._resetPin) {
            throw new Error('reset() requires a resetPin to have been supplied at construction');
        }
        this._resetPin.set(false);
        this._resetPin.set(true);
    }
}

module.exports = { AD7705Minimal, AD7705Full };
