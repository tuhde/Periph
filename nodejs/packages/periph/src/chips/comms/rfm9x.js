'use strict';

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver — minimal interface.
 *
 * All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * The driver is built around an internal _RFM9xBase that owns all register
 * logic. Four thin variant subclasses — RFM95Minimal, RFM96Minimal,
 * RFM97Minimal, RFM98Minimal — supply the variant-specific frequency
 * limits, maximum SF, and band flag.
 */
class _RFM9xBase {
    static REG_FIFO            = 0x00;
    static REG_OP_MODE         = 0x01;
    static REG_FRF_MSB         = 0x06;
    static REG_FRF_MID         = 0x07;
    static REG_FRF_LSB         = 0x08;
    static REG_PA_CONFIG       = 0x09;
    static REG_OCP             = 0x0B;
    static REG_LNA             = 0x0C;
    static REG_FIFO_ADDR_PTR   = 0x0D;
    static REG_FIFO_TX_BASE    = 0x0E;
    static REG_FIFO_RX_BASE    = 0x0F;
    static REG_FIFO_RX_CURRENT = 0x10;
    static REG_IRQ_FLAGS       = 0x12;
    static REG_RX_NB_BYTES     = 0x13;
    static REG_PKT_SNR         = 0x19;
    static REG_PKT_RSSI        = 0x1A;
    static REG_RSSI            = 0x1B;
    static REG_MODEM_CONFIG_1  = 0x1D;
    static REG_MODEM_CONFIG_2  = 0x1E;
    static REG_PREAMBLE_LSB    = 0x21;
    static REG_PAYLOAD_LENGTH  = 0x22;
    static REG_MODEM_CONFIG_3  = 0x26;
    static REG_DETECTION_OPT   = 0x31;
    static REG_DETECTION_THR   = 0x37;
    static REG_DIO_MAPPING_1   = 0x40;
    static REG_VERSION         = 0x42;
    static REG_PA_DAC          = 0x4D;

    static MODE_LONG_RANGE = 0x80;
    static MODE_SLEEP      = 0x00;
    static MODE_STANDBY    = 0x01;
    static MODE_TX         = 0x03;
    static MODE_RX_CONT    = 0x05;
    static MODE_RX_SINGLE  = 0x06;

    static IRQ_TX_DONE    = 0x08;
    static IRQ_RX_DONE    = 0x40;
    static IRQ_RX_TIMEOUT = 0x80;

    static PA_BOOST          = 0x80;
    static PA_DAC_HIGH_POWER = 0x87;
    static PA_DAC_DEFAULT    = 0x84;
    static OCP_240MA         = 0x3B;
    static OCP_DEFAULT       = 0x2B;

    static DIO0_RX_DONE = 0x00;
    static DIO0_TX_DONE = 0x40;

    static FXOSC = 32000000;

    /**
     * @param {object} connection - SPI connection (write, read, writeRead).
     * @param {number} frequencyHz - Carrier frequency in Hz.
     */
    constructor(connection, frequencyHz) {
        this._connection = connection;
        this._frequencyHz = frequencyHz;
        if (frequencyHz < this.freqMinHz || frequencyHz > this.freqMaxHz) {
            throw new RangeError(`frequencyHz ${frequencyHz} out of range [${this.freqMinHz}, ${this.freqMaxHz}]`);
        }
    }

    async init() {
        await this._writeReg(this.REG_OP_MODE, 0x00);
        await this._sleep(1);
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | this.MODE_SLEEP);
        await this._sleep(1);

        if (this._lfBand) {
            const lna = await this._readReg(this.REG_LNA);
            await this._writeReg(this.REG_LNA, lna & 0x3F);
        } else {
            await this._writeReg(this.REG_LNA, 0x23);
        }
        await this._writeReg(this.REG_MODEM_CONFIG_3, (await this._readReg(this.REG_MODEM_CONFIG_3)) | 0x04);

        await this._writeReg(this.REG_FIFO_TX_BASE, 0x80);
        await this._writeReg(this.REG_FIFO_RX_BASE, 0x00);

        await this.setFrequency(this._frequencyHz);

        await this._writeReg(this.REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00);
        await this._writeReg(this.REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03);
        await this._writeReg(this.REG_PREAMBLE_LSB, 0x08);

        await this.setTxPower(17, true);
        await this.standby();
    }

    _sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    async _writeReg(reg, value) {
        await this._connection.write(Buffer.from([reg | 0x80, value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._connection.writeRead(Buffer.from([reg & 0x7F]), 1))[0];
    }

    async _burstWrite(reg, data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        await this._connection.write(Buffer.concat([Buffer.from([reg | 0x80]), buf]));
    }

    async _burstRead(reg, len) {
        return this._connection.writeRead(Buffer.from([reg & 0x7F]), len);
    }

    /**
     * Set the carrier frequency.
     * @param {number} frequencyHz - Carrier frequency in Hz; must lie in the variant's range.
     * @returns {Promise<void>}
     */
    async setFrequency(frequencyHz) {
        if (frequencyHz < this.freqMinHz || frequencyHz > this.freqMaxHz) {
            throw new RangeError(`frequencyHz ${frequencyHz} out of range [${this.freqMinHz}, ${this.freqMaxHz}]`);
        }
        const frf = Math.floor((frequencyHz * (1 << 19)) / this.FXOSC);
        await this._writeReg(this.REG_FRF_MSB, (frf >> 16) & 0xFF);
        await this._writeReg(this.REG_FRF_MID, (frf >> 8) & 0xFF);
        await this._writeReg(this.REG_FRF_LSB, frf & 0xFF);
        this._frequencyHz = frequencyHz;
    }

    /**
     * Set TX output power.
     * @param {number} powerDbm - Output power in dBm. −1 to +14 (RFO) or +2 to +20 (PA_BOOST).
     * @param {boolean} usePaBoost - True to use PA_BOOST pin (default), false for RFO.
     * @returns {Promise<void>}
     */
    async setTxPower(powerDbm, usePaBoost = true) {
        if (usePaBoost) {
            if (powerDbm > 17) {
                if (powerDbm > 20) powerDbm = 20;
                await this._writeReg(this.REG_PA_DAC, this.PA_DAC_HIGH_POWER);
                await this._writeReg(this.REG_OCP, this.OCP_240MA);
                await this._writeReg(this.REG_PA_CONFIG, this.PA_BOOST | 0x0F);
            } else {
                if (powerDbm < 2) powerDbm = 2;
                await this._writeReg(this.REG_PA_DAC, this.PA_DAC_DEFAULT);
                await this._writeReg(this.REG_OCP, this.OCP_DEFAULT);
                await this._writeReg(this.REG_PA_CONFIG, this.PA_BOOST | (powerDbm - 2));
            }
        } else {
            await this._writeReg(this.REG_PA_DAC, this.PA_DAC_DEFAULT);
            await this._writeReg(this.REG_OCP, this.OCP_DEFAULT);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let op = Math.floor(powerDbm - pmax + 15);
            if (op < 0) op = 0;
            if (op > 15) op = 15;
            await this._writeReg(this.REG_PA_CONFIG, (maxPower << 4) | op);
        }
    }

    /**
     * Configure LoRa modulation parameters.
     * @param {number} sf - Spreading factor 6–12 (variant-capped; RFM97W max 9).
     * @param {number} bandwidthKhz - Signal bandwidth in kHz.
     * @param {number} codingRate - Coding rate denominator 5–8.
     * @param {boolean} crc - True to enable CRC on RX payloads (default).
     * @returns {Promise<void>}
     */
    async configure(sf, bandwidthKhz, codingRate, crc = true) {
        const bwTable = [7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0];
        let bwCode = 0x07;
        for (let i = 0; i < 10; i++) {
            if (bandwidthKhz === bwTable[i]) { bwCode = i; break; }
        }
        if (sf < 6 || sf > this.maxSF) sf = Math.min(Math.max(sf, 6), this.maxSF);

        if (sf === 6) {
            await this._writeReg(this.REG_DETECTION_OPT, 0x05);
            await this._writeReg(this.REG_DETECTION_THR, 0x0C);
        } else {
            await this._writeReg(this.REG_DETECTION_OPT, 0x03);
            await this._writeReg(this.REG_DETECTION_THR, 0x0A);
        }

        const implicitHeader = (sf === 6);
        const cr = (codingRate >= 5 && codingRate <= 8) ? (codingRate - 4) : 0x01;
        await this._writeReg(this.REG_MODEM_CONFIG_1, (bwCode << 4) | (cr << 1) | (implicitHeader ? 1 : 0));
        await this._writeReg(this.REG_MODEM_CONFIG_2, (sf << 4) | ((crc ? 1 : 0) << 2) | 0x03);
    }

    /**
     * Send a packet.
     * @param {Buffer|Uint8Array|string} data - Payload; max 255 bytes.
     * @returns {Promise<void>}
     */
    async send(data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length > 255) throw new RangeError(`payload length ${buf.length} exceeds 255`);
        await this.standby();
        await this._writeReg(this.REG_FIFO_ADDR_PTR, 0x80);
        await this._burstWrite(this.REG_FIFO, buf);
        await this._writeReg(this.REG_PAYLOAD_LENGTH, buf.length);
        await this._writeReg(this.REG_DIO_MAPPING_1, this.DIO0_TX_DONE);
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_TX);

        while (true) {
            const irq = await this._readReg(this.REG_IRQ_FLAGS);
            if (irq & this.IRQ_TX_DONE) break;
            await this._sleep(2);
        }
        await this._writeReg(this.REG_IRQ_FLAGS, this.IRQ_TX_DONE);
        await this.standby();
    }

    /**
     * Receive a single packet.
     * @param {number} timeoutMs - Receive timeout in milliseconds (default 2000).
     * @returns {Promise<Buffer | null>} Received payload bytes, or null on timeout.
     */
    async receive(timeoutMs = 2000) {
        await this.standby();
        await this._writeReg(this.REG_DIO_MAPPING_1, this.DIO0_RX_DONE);
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_RX_SINGLE);

        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const irq = await this._readReg(this.REG_IRQ_FLAGS);
            if (irq & this.IRQ_RX_DONE) {
                await this._writeReg(this.REG_IRQ_FLAGS, this.IRQ_RX_DONE);
                return await this._readPayload();
            }
            if (irq & this.IRQ_RX_TIMEOUT) {
                await this._writeReg(this.REG_IRQ_FLAGS, this.IRQ_RX_TIMEOUT);
                return null;
            }
            await this._sleep(5);
        }
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_STANDBY);
        return null;
    }

    async _readPayload() {
        const current = await this._readReg(this.REG_FIFO_RX_CURRENT);
        await this._writeReg(this.REG_FIFO_ADDR_PTR, current);
        const n = await this._readReg(this.REG_RX_NB_BYTES);
        return await this._burstRead(this.REG_FIFO, n);
    }

    /** Enter continuous receive mode.
     * @returns {Promise<void>}
     */
    async receiveContinuous() {
        await this.standby();
        await this._writeReg(this.REG_DIO_MAPPING_1, this.DIO0_RX_DONE);
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_RX_CONT);
    }

    /**
     * Read one packet from the FIFO in continuous receive mode.
     * @returns {Promise<Buffer | null>} Payload bytes, or null if no packet is waiting.
     */
    async readPacket() {
        const irq = await this._readReg(this.REG_IRQ_FLAGS);
        if (!(irq & this.IRQ_RX_DONE)) return null;
        await this._writeReg(this.REG_IRQ_FLAGS, this.IRQ_RX_DONE);
        return await this._readPayload();
    }

    /** Return to STDBY from continuous receive mode.
     * @returns {Promise<void>}
     */
    async stopReceive() {
        await this.standby();
    }

    /** Enter STDBY mode.
     * @returns {Promise<void>}
     */
    async standby() {
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_STANDBY);
    }

    /** Enter SLEEP mode.
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | (this._lfBand ? 0x08 : 0x00) | this.MODE_SLEEP);
    }

    /** Read RegVersion. Expect 0x12 (SX1276).
     * @returns {Promise<number>}
     */
    async version() {
        return await this._readReg(this.REG_VERSION);
    }

    /** Current channel RSSI in dBm (readable in continuous RX).
     * @returns {Promise<number>}
     */
    async rssi() {
        return -137 + await this._readReg(this.REG_RSSI);
    }

    /** RSSI of last received packet in dBm.
     * @returns {Promise<number>}
     */
    async lastPacketRssi() {
        return -137 + await this._readReg(this.REG_PKT_RSSI);
    }

    /** SNR of last received packet in dB.
     * @returns {Promise<number>}
     */
    async lastPacketSnr() {
        let raw = await this._readReg(this.REG_PKT_SNR);
        if (raw & 0x80) raw = raw - 0x100;
        return raw / 4.0;
    }
}

/**
 * RFM95W minimal driver — 868/915 MHz HF band, max SF=12.
 */
class RFM95Minimal extends _RFM9xBase {
    /**
     * @param {object} connection - SPI connection.
     * @param {number} frequencyHz - Carrier frequency in Hz (862–1020 MHz).
     */
    constructor(connection, frequencyHz) {
        super(connection, frequencyHz);
        this.freqMinHz = 862_000_000;
        this.freqMaxHz = 1_020_000_000;
        this.maxSF = 12;
        this._lfBand = false;
    }
}

/**
 * RFM96W minimal driver — 433/470 MHz LF band, max SF=12.
 */
class RFM96Minimal extends _RFM9xBase {
    /**
     * @param {object} connection - SPI connection.
     * @param {number} frequencyHz - Carrier frequency in Hz (410–525 MHz).
     */
    constructor(connection, frequencyHz) {
        super(connection, frequencyHz);
        this.freqMinHz = 410_000_000;
        this.freqMaxHz = 525_000_000;
        this.maxSF = 12;
        this._lfBand = true;
    }
}

/**
 * RFM97W minimal driver — 868/915 MHz HF band, max SF=9.
 */
class RFM97Minimal extends _RFM9xBase {
    /**
     * @param {object} connection - SPI connection.
     * @param {number} frequencyHz - Carrier frequency in Hz (862–1020 MHz).
     */
    constructor(connection, frequencyHz) {
        super(connection, frequencyHz);
        this.freqMinHz = 862_000_000;
        this.freqMaxHz = 1_020_000_000;
        this.maxSF = 9;
        this._lfBand = false;
    }
}

/**
 * RFM98W minimal driver — 433/470 MHz LF band, max SF=12.
 */
class RFM98Minimal extends _RFM9xBase {
    /**
     * @param {object} connection - SPI connection.
     * @param {number} frequencyHz - Carrier frequency in Hz (410–525 MHz).
     */
    constructor(connection, frequencyHz) {
        super(connection, frequencyHz);
        this.freqMinHz = 410_000_000;
        this.freqMaxHz = 525_000_000;
        this.maxSF = 12;
        this._lfBand = true;
    }
}

/**
 * RFM95W full driver — extends RFM95Minimal with hardware reset.
 */
class RFM95Full extends RFM95Minimal {
    /**
     * Hardware reset (POR wait fallback; pin-driven reset wired in examples).
     * @returns {Promise<void>}
     */
    async reset() {
        await this._sleep(5);
        await this._writeReg(this.REG_OP_MODE, 0x00);
        await this._sleep(1);
        await this._writeReg(this.REG_OP_MODE, this.MODE_LONG_RANGE | this.MODE_SLEEP);
        await this._sleep(1);
        await this.init();
    }
}

/** RFM96W full driver. */
class RFM96Full extends RFM96Minimal {
    async reset() { await super.reset(); }
}

/** RFM97W full driver. */
class RFM97Full extends RFM97Minimal {
    async reset() { await super.reset(); }
}

/** RFM98W full driver. */
class RFM98Full extends RFM98Minimal {
    async reset() { await super.reset(); }
}

module.exports = { RFM95Minimal, RFM96Minimal, RFM97Minimal, RFM98Minimal,
                   RFM95Full, RFM96Full, RFM97Full, RFM98Full };
