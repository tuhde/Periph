'use strict';

const FXOSC = 32000000;

const REG_FIFO              = 0x00;
const REG_OP_MODE           = 0x01;
const REG_FRF_MSB           = 0x06;
const REG_FRF_MID           = 0x07;
const REG_FRF_LSB           = 0x08;
const REG_PA_CONFIG         = 0x09;
const REG_OCP               = 0x0B;
const REG_LNA               = 0x0C;
const REG_FIFO_ADDR_PTR     = 0x0D;
const REG_FIFO_TX_BASE_ADDR = 0x0E;
const REG_FIFO_RX_BASE_ADDR = 0x0F;
const REG_FIFO_RX_CURRENT_ADDR = 0x10;
const REG_IRQ_FLAGS         = 0x12;
const REG_RX_NB_BYTES       = 0x13;
const REG_PKT_SNR_VALUE     = 0x19;
const REG_PKT_RSSI_VALUE    = 0x1A;
const REG_RSSI_VALUE        = 0x1B;
const REG_MODEM_CONFIG1     = 0x1D;
const REG_MODEM_CONFIG2     = 0x1E;
const REG_MODEM_CONFIG3     = 0x26;
const REG_VERSION           = 0x42;
const REG_PA_DAC            = 0x4D;

const MODE_SLEEP        = 0;
const MODE_STDBY        = 1;
const MODE_TX           = 3;
const MODE_RXSINGLE     = 6;
const MODE_RXCONTINUOUS = 5;

const IRQ_TX_DONE    = 0x08;
const IRQ_RX_DONE    = 0x40;
const IRQ_RX_TIMEOUT = 0x80;

const BW_MAP = {
    7.8: 0x00, 10.4: 0x01, 15.6: 0x02, 20.8: 0x03,
    31.25: 0x04, 41.7: 0x05, 62.5: 0x06,
    125: 0x07, 250: 0x08, 500: 0x09
};

const CR_MAP = { 5: 1, 6: 2, 7: 3, 8: 4 };

class _RFM9xBase {
    constructor(transport, frequency_hz) {
        this._transport = transport;
        this._frequency_hz = frequency_hz;
        this._sf = 7;
        this._bw = 125000;
        this._cr = 5;
        this._crc = true;
        this._init();
    }

    _write_reg(reg, value) {
        const addr = (reg & 0x7F) | 0x80;
        this._transport.write(Buffer.from([addr, value]));
    }

    _read_reg(reg) {
        const addr = reg & 0x7F;
        const buf = this._transport.writeRead(Buffer.from([addr]), 2);
        return (buf[0] << 8) | buf[1];
    }

    _set_mode(mode) {
        const current = this._read_reg(REG_OP_MODE);
        const newVal = (current & 0xF8) | (mode & 0x07);
        this._write_reg(REG_OP_MODE, newVal);
    }

    _init() {
        this._transport.write(Buffer.from([REG_OP_MODE, 0x00]));
        this._transport.write(Buffer.from([REG_OP_MODE, 0x80]));
        this._set_mode(MODE_SLEEP);
        const opMode = 0x80 | (this._LF_BAND ? 0x01 : 0x00);
        this._write_reg(REG_OP_MODE, opMode);
        this._write_reg(REG_FIFO_TX_BASE_ADDR, 0x80);
        this._write_reg(REG_FIFO_RX_BASE_ADDR, 0x00);
        if (!this._LF_BAND) this._write_reg(REG_LNA, 0x23);
        this._write_reg(REG_MODEM_CONFIG3, 0x04);
        this._set_frequency(this._frequency_hz);
        this._write_reg(REG_MODEM_CONFIG1, 0x72);
        this._write_reg(REG_MODEM_CONFIG2, (7 << 4) | 0x04);
        this._set_mode(MODE_STDBY);
    }

    _set_frequency(frequency_hz) {
        const frf = Math.round(frequency_hz * 524288 / FXOSC);
        this._write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
        this._write_reg(REG_FRF_MID, (frf >> 8) & 0xFF);
        this._write_reg(REG_FRF_LSB, frf & 0xFF);
    }

    _poll_irq(irq_mask, timeout_ms) {
        const deadline = Date.now() + timeout_ms;
        while (Date.now() < deadline) {
            const flags = this._read_reg(REG_IRQ_FLAGS);
            if (flags & irq_mask) return true;
        }
        return false;
    }

    /**
     * Transmit a packet.
     * @param {Buffer} data - Bytes to transmit (max 255 bytes).
     */
    send(data) {
        if (data.length > 255) throw new Error('payload exceeds 255 bytes');
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._write_reg(REG_FIFO_ADDR_PTR, 0x80);
        const fifo_data = Buffer.from([data.length, ...data]);
        this._transport.write(fifo_data);
        this._set_mode(MODE_TX);
        this._poll_irq(IRQ_TX_DONE, 10000);
        this._write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
        this._set_mode(MODE_STDBY);
    }

    /**
     * Receive a packet (single shot).
     * @param {number} [timeout_ms=2000] - Timeout in milliseconds.
     * @returns {Buffer|null} Received payload, or null on timeout.
     */
    receive(timeout_ms = 2000) {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._write_reg(REG_FIFO_RX_CURRENT_ADDR, 0x00);
        this._set_mode(MODE_RXSINGLE);
        if (!this._poll_irq(IRQ_TX_DONE | IRQ_RX_TIMEOUT, timeout_ms)) {
            this._set_mode(MODE_STDBY);
            return null;
        }
        const flags = this._read_reg(REG_IRQ_FLAGS);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        if (flags & IRQ_RX_TIMEOUT) { this._set_mode(MODE_STDBY); return null; }
        if (!(flags & IRQ_RX_DONE)) return null;
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        const fifo_data = this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
        this._set_mode(MODE_STDBY);
        return fifo_data;
    }

    /**
     * Read silicon revision.
     * @returns {number} RegVersion value (expect 0x12 for SX1276).
     */
    version() {
        return this._read_reg(REG_VERSION);
    }

    standby() { this._set_mode(MODE_STDBY); }
    sleep() { this._set_mode(MODE_SLEEP); }
}

class RFM95Minimal extends _RFM9xBase {
    constructor(transport, frequency_hz) {
        super(transport, frequency_hz);
        this._LF_BAND = false;
    }

    static get FREQ_MIN_HZ() { return 862000000; }
    static get FREQ_MAX_HZ() { return 1020000000; }
    static get MAX_SF() { return 12; }
}

class RFM96Minimal extends _RFM9xBase {
    constructor(transport, frequency_hz) {
        super(transport, frequency_hz);
        this._LF_BAND = true;
    }

    static get FREQ_MIN_HZ() { return 410000000; }
    static get FREQ_MAX_HZ() { return 525000000; }
    static get MAX_SF() { return 12; }
}

class RFM97Minimal extends _RFM9xBase {
    constructor(transport, frequency_hz) {
        super(transport, frequency_hz);
        this._LF_BAND = false;
    }

    static get FREQ_MIN_HZ() { return 862000000; }
    static get FREQ_MAX_HZ() { return 1020000000; }
    static get MAX_SF() { return 9; }
}

class RFM98Minimal extends _RFM9xBase {
    constructor(transport, frequency_hz) {
        super(transport, frequency_hz);
        this._LF_BAND = true;
    }

    static get FREQ_MIN_HZ() { return 410000000; }
    static get FREQ_MAX_HZ() { return 525000000; }
    static get MAX_SF() { return 12; }
}

class RFM95Full extends RFM95Minimal {
    /**
     * RFM95W full interface — extends RFM95Minimal with configuration and GPIO support.
     * @param {object} transport - Configured SPI transport.
     * @param {number} frequency_hz - Carrier frequency in Hz.
     * @param {number|null} resetPin - Optional GPIO pin for hardware reset.
     * @param {number|null} dio0Pin - Optional GPIO pin for DIO0 interrupt.
     */
    constructor(transport, frequency_hz, resetPin = null, dio0Pin = null) {
        super(transport, frequency_hz);
        this._resetPin = resetPin;
        this._dio0Pin = dio0Pin;
    }

    reset() {
        if (!this._resetPin) throw new Error('reset_pin not configured');
        this._digitalWrite(this._resetPin, 0);
        this._delay_us(100);
        this._digitalWrite(this._resetPin, 1);
        this._delay_us(5000);
        this._init();
    }

    configure(sf, bandwidth_khz, coding_rate, crc = true) {
        if (sf < 6 || sf > this.MAX_SF) throw new Error(`sf ${sf} out of range 6-${this.MAX_SF}`);
        if (this._LF_BAND && bandwidth_khz > 62.5) throw new Error(`bandwidth ${bandwidth_khz} kHz not supported on LF band`);
        if (!BW_MAP[bandwidth_khz]) throw new Error(`unsupported bandwidth ${bandwidth_khz}`);
        if (!CR_MAP[coding_rate]) throw new Error(`coding_rate ${coding_rate} must be 5-8`);
        this._sf = sf;
        this._bw = bandwidth_khz * 1000;
        this._cr = coding_rate;
        this._crc = crc;
        const bw_bits = BW_MAP[bandwidth_khz];
        const cr_bits = CR_MAP[coding_rate];
        this._write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
        const implicit = (sf === 6) ? 1 : 0;
        this._write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
        if (sf === 6) {
            this._write_reg(0x31, 0x05);
            this._write_reg(0x37, 0x0C);
        }
    }

    set_frequency(frequency_hz) {
        if (frequency_hz < this.FREQ_MIN_HZ || frequency_hz > this.FREQ_MAX_HZ) throw new Error('frequency out of range');
        this._frequency_hz = frequency_hz;
        this._set_frequency(frequency_hz);
    }

    set_tx_power(power_dbm, use_pa_boost = true) {
        if (use_pa_boost) {
            if (power_dbm < 2 || power_dbm > 20) throw new Error(`PA_BOOST power ${power_dbm} dBm out of range 2-20`);
            if (power_dbm > 17) {
                this._write_reg(REG_PA_DAC, 0x87);
                this._write_reg(REG_OCP, 0x3B);
            } else {
                this._write_reg(REG_PA_DAC, 0x84);
                this._write_reg(REG_OCP, 0x2B);
            }
            this._write_reg(REG_PA_CONFIG, 0x80 | ((power_dbm - 2) & 0x0F));
        } else {
            if (power_dbm < -1 || power_dbm > 14) throw new Error(`RFO power ${power_dbm} dBm out of range -1-14`);
            this._write_reg(REG_PA_DAC, 0x84);
            this._write_reg(REG_OCP, 0x2B);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let outputPower = Math.round(power_dbm - pmax + 15);
            outputPower = Math.max(0, Math.min(15, outputPower));
            this._write_reg(REG_PA_CONFIG, (maxPower << 4) | (outputPower & 0x0F));
        }
    }

    receive_continuous() {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._set_mode(MODE_RXCONTINUOUS);
    }

    read_packet() {
        if (!(this._read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return null;
        this._write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        return this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
    }

    stop_receive() { this._set_mode(MODE_STDBY); }

    rssi() {
        const r = this._read_reg(REG_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_rssi() {
        const r = this._read_reg(REG_PKT_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_snr() {
        let snr = this._read_reg(REG_PKT_SNR_VALUE);
        if (snr >= 128) snr -= 256;
        return snr / 4.0;
    }

    /**
     * Hardware reset via NRESET pin.
     * @throws {Error} If reset_pin not configured.
     */
    reset() {
        if (!this._resetPin) throw new Error('reset_pin not configured');
        this._digitalWrite(this._resetPin, 0);
        this._delay_us(100);
        this._digitalWrite(this._resetPin, 1);
        this._delay_us(5000);
        this._init();
    }

    /**
     * Configure modem parameters.
     * @param {number} sf - Spreading factor 6-12 (capped at variant MAX_SF).
     * @param {number} bandwidth_khz - Signal bandwidth in kHz (7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500).
     * @param {number} coding_rate - Coding rate denominator 5-8 (4/5, 4/6, 4/7, 4/8).
     * @param {boolean} [crc=true] - Enable CRC generation and verification.
     */
    configure(sf, bandwidth_khz, coding_rate, crc = true) {
        if (sf < 6 || sf > this.MAX_SF) throw new Error(`sf ${sf} out of range 6-${this.MAX_SF}`);
        if (this._LF_BAND && bandwidth_khz > 62.5) throw new Error(`bandwidth ${bandwidth_khz} kHz not supported on LF band`);
        if (!BW_MAP[bandwidth_khz]) throw new Error(`unsupported bandwidth ${bandwidth_khz}`);
        if (!CR_MAP[coding_rate]) throw new Error(`coding_rate ${coding_rate} must be 5-8`);
        this._sf = sf;
        this._bw = bandwidth_khz * 1000;
        this._cr = coding_rate;
        this._crc = crc;
        const bw_bits = BW_MAP[bandwidth_khz];
        const cr_bits = CR_MAP[coding_rate];
        this._write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
        const implicit = (sf === 6) ? 1 : 0;
        this._write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
        if (sf === 6) {
            this._write_reg(0x31, 0x05);
            this._write_reg(0x37, 0x0C);
        }
    }

    /**
     * Change carrier frequency.
     * @param {number} frequency_hz - New carrier frequency in Hz.
     */
    set_frequency(frequency_hz) {
        if (frequency_hz < this.FREQ_MIN_HZ || frequency_hz > this.FREQ_MAX_HZ) throw new Error('frequency out of range');
        this._frequency_hz = frequency_hz;
        this._set_frequency(frequency_hz);
    }

    /**
     * Set TX output power.
     * @param {number} power_dbm - Output power in dBm (2-20 for PA_BOOST, -1-14 for RFO).
     * @param {boolean} [use_pa_boost=true] - Use PA_BOOST pin (max +20 dBm) if true, RFO pin (max +14 dBm) if false.
     */
    set_tx_power(power_dbm, use_pa_boost = true) {
        if (use_pa_boost) {
            if (power_dbm < 2 || power_dbm > 20) throw new Error(`PA_BOOST power ${power_dbm} dBm out of range 2-20`);
            if (power_dbm > 17) {
                this._write_reg(REG_PA_DAC, 0x87);
                this._write_reg(REG_OCP, 0x3B);
            } else {
                this._write_reg(REG_PA_DAC, 0x84);
                this._write_reg(REG_OCP, 0x2B);
            }
            this._write_reg(REG_PA_CONFIG, 0x80 | ((power_dbm - 2) & 0x0F));
        } else {
            if (power_dbm < -1 || power_dbm > 14) throw new Error(`RFO power ${power_dbm} dBm out of range -1-14`);
            this._write_reg(REG_PA_DAC, 0x84);
            this._write_reg(REG_OCP, 0x2B);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let outputPower = Math.round(power_dbm - pmax + 15);
            outputPower = Math.max(0, Math.min(15, outputPower));
            this._write_reg(REG_PA_CONFIG, (maxPower << 4) | (outputPower & 0x0F));
        }
    }

    /**
     * Enter continuous receive mode.
     */
    receive_continuous() {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._set_mode(MODE_RXCONTINUOUS);
    }

    /**
     * Read one packet from FIFO in continuous mode.
     * @returns {Buffer|null} Received payload, or null if no packet available.
     */
    read_packet() {
        if (!(this._read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return null;
        this._write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        return this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
    }

    /**
     * Return to STANDBY from RXCONTINUOUS.
     */
    stop_receive() { this._set_mode(MODE_STDBY); }

    /**
     * Read current channel RSSI.
     * @returns {number} RSSI in dBm.
     */
    rssi() {
        const r = this._read_reg(REG_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    /**
     * Read RSSI of last received packet.
     * @returns {number} Packet RSSI in dBm.
     */
    last_packet_rssi() {
        const r = this._read_reg(REG_PKT_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    /**
     * Read SNR of last received packet.
     * @returns {number} Packet SNR in dB.
     */
    last_packet_snr() {
        let snr = this._read_reg(REG_PKT_SNR_VALUE);
        if (snr >= 128) snr -= 256;
        return snr / 4.0;
    }

    _digital_write(pin, value) { (void)pin; (void)value; }
    _delay_us(us) { const end = Date.now() + Math.ceil(us / 1000); while (Date.now() < end) {} }
}

class RFM96Full extends RFM96Minimal {
    /**
     * RFM96W full interface — extends RFM96Minimal with configuration and GPIO support.
     * @param {object} transport - Configured SPI transport.
     * @param {number} frequency_hz - Carrier frequency in Hz.
     * @param {number|null} resetPin - Optional GPIO pin for hardware reset.
     * @param {number|null} dio0Pin - Optional GPIO pin for DIO0 interrupt.
     */
    constructor(transport, frequency_hz, resetPin = null, dio0Pin = null) {
        super(transport, frequency_hz);
        this._resetPin = resetPin;
        this._dio0Pin = dio0Pin;
    }

    /**
     * Hardware reset via NRESET pin.
     * @throws {Error} If reset_pin not configured.
     */
    reset() {
        if (!this._resetPin) throw new Error('reset_pin not configured');
        this._digitalWrite(this._resetPin, 0);
        this._delay_us(100);
        this._digitalWrite(this._resetPin, 1);
        this._delay_us(5000);
        this._init();
    }

    /**
     * Configure modem parameters.
     * @param {number} sf - Spreading factor 6-12 (capped at variant MAX_SF).
     * @param {number} bandwidth_khz - Signal bandwidth in kHz (7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500).
     * @param {number} coding_rate - Coding rate denominator 5-8 (4/5, 4/6, 4/7, 4/8).
     * @param {boolean} [crc=true] - Enable CRC generation and verification.
     */
    configure(sf, bandwidth_khz, coding_rate, crc = true) {
        if (sf < 6 || sf > this.MAX_SF) throw new Error(`sf ${sf} out of range 6-${this.MAX_SF}`);
        if (this._LF_BAND && bandwidth_khz > 62.5) throw new Error(`bandwidth ${bandwidth_khz} kHz not supported on LF band`);
        if (!BW_MAP[bandwidth_khz]) throw new Error(`unsupported bandwidth ${bandwidth_khz}`);
        if (!CR_MAP[coding_rate]) throw new Error(`coding_rate ${coding_rate} must be 5-8`);
        this._sf = sf;
        this._bw = bandwidth_khz * 1000;
        this._cr = coding_rate;
        this._crc = crc;
        const bw_bits = BW_MAP[bandwidth_khz];
        const cr_bits = CR_MAP[coding_rate];
        this._write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
        const implicit = (sf === 6) ? 1 : 0;
        this._write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
        if (sf === 6) {
            this._write_reg(0x31, 0x05);
            this._write_reg(0x37, 0x0C);
        }
    }

    set_frequency(frequency_hz) {
        if (frequency_hz < this.FREQ_MIN_HZ || frequency_hz > this.FREQ_MAX_HZ) throw new Error('frequency out of range');
        this._frequency_hz = frequency_hz;
        this._set_frequency(frequency_hz);
    }

    set_tx_power(power_dbm, use_pa_boost = true) {
        if (use_pa_boost) {
            if (power_dbm < 2 || power_dbm > 20) throw new Error(`PA_BOOST power ${power_dbm} dBm out of range 2-20`);
            if (power_dbm > 17) {
                this._write_reg(REG_PA_DAC, 0x87);
                this._write_reg(REG_OCP, 0x3B);
            } else {
                this._write_reg(REG_PA_DAC, 0x84);
                this._write_reg(REG_OCP, 0x2B);
            }
            this._write_reg(REG_PA_CONFIG, 0x80 | ((power_dbm - 2) & 0x0F));
        } else {
            if (power_dbm < -1 || power_dbm > 14) throw new Error(`RFO power ${power_dbm} dBm out of range -1-14`);
            this._write_reg(REG_PA_DAC, 0x84);
            this._write_reg(REG_OCP, 0x2B);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let outputPower = Math.round(power_dbm - pmax + 15);
            outputPower = Math.max(0, Math.min(15, outputPower));
            this._write_reg(REG_PA_CONFIG, (maxPower << 4) | (outputPower & 0x0F));
        }
    }

    receive_continuous() {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._set_mode(MODE_RXCONTINUOUS);
    }

    read_packet() {
        if (!(this._read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return null;
        this._write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        return this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
    }

    stop_receive() { this._set_mode(MODE_STDBY); }

    rssi() {
        const r = this._read_reg(REG_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_rssi() {
        const r = this._read_reg(REG_PKT_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_snr() {
        let snr = this._read_reg(REG_PKT_SNR_VALUE);
        if (snr >= 128) snr -= 256;
        return snr / 4.0;
    }

    _digital_write(pin, value) { (void)pin; (void)value; }
    _delay_us(us) { const end = Date.now() + Math.ceil(us / 1000); while (Date.now() < end) {} }
}

class RFM97Full extends RFM97Minimal {
    /**
     * RFM97W full interface — extends RFM97Minimal with configuration and GPIO support.
     * @param {object} transport - Configured SPI transport.
     * @param {number} frequency_hz - Carrier frequency in Hz.
     * @param {number|null} resetPin - Optional GPIO pin for hardware reset.
     * @param {number|null} dio0Pin - Optional GPIO pin for DIO0 interrupt.
     */
    constructor(transport, frequency_hz, resetPin = null, dio0Pin = null) {
        super(transport, frequency_hz);
        this._resetPin = resetPin;
        this._dio0Pin = dio0Pin;
    }

    /**
     * Hardware reset via NRESET pin.
     * @throws {Error} If reset_pin not configured.
     */
    reset() {
        if (!this._resetPin) throw new Error('reset_pin not configured');
        this._digitalWrite(this._resetPin, 0);
        this._delay_us(100);
        this._digitalWrite(this._resetPin, 1);
        this._delay_us(5000);
        this._init();
    }

    /**
     * Configure modem parameters.
     * @param {number} sf - Spreading factor 6-12 (capped at variant MAX_SF).
     * @param {number} bandwidth_khz - Signal bandwidth in kHz (7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500).
     * @param {number} coding_rate - Coding rate denominator 5-8 (4/5, 4/6, 4/7, 4/8).
     * @param {boolean} [crc=true] - Enable CRC generation and verification.
     */
    configure(sf, bandwidth_khz, coding_rate, crc = true) {
        if (sf < 6 || sf > this.MAX_SF) throw new Error(`sf ${sf} out of range 6-${this.MAX_SF}`);
        if (this._LF_BAND && bandwidth_khz > 62.5) throw new Error(`bandwidth ${bandwidth_khz} kHz not supported on LF band`);
        if (!BW_MAP[bandwidth_khz]) throw new Error(`unsupported bandwidth ${bandwidth_khz}`);
        if (!CR_MAP[coding_rate]) throw new Error(`coding_rate ${coding_rate} must be 5-8`);
        this._sf = sf;
        this._bw = bandwidth_khz * 1000;
        this._cr = coding_rate;
        this._crc = crc;
        const bw_bits = BW_MAP[bandwidth_khz];
        const cr_bits = CR_MAP[coding_rate];
        this._write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
        const implicit = (sf === 6) ? 1 : 0;
        this._write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
        if (sf === 6) {
            this._write_reg(0x31, 0x05);
            this._write_reg(0x37, 0x0C);
        }
    }

    set_frequency(frequency_hz) {
        if (frequency_hz < this.FREQ_MIN_HZ || frequency_hz > this.FREQ_MAX_HZ) throw new Error('frequency out of range');
        this._frequency_hz = frequency_hz;
        this._set_frequency(frequency_hz);
    }

    set_tx_power(power_dbm, use_pa_boost = true) {
        if (use_pa_boost) {
            if (power_dbm < 2 || power_dbm > 20) throw new Error(`PA_BOOST power ${power_dbm} dBm out of range 2-20`);
            if (power_dbm > 17) {
                this._write_reg(REG_PA_DAC, 0x87);
                this._write_reg(REG_OCP, 0x3B);
            } else {
                this._write_reg(REG_PA_DAC, 0x84);
                this._write_reg(REG_OCP, 0x2B);
            }
            this._write_reg(REG_PA_CONFIG, 0x80 | ((power_dbm - 2) & 0x0F));
        } else {
            if (power_dbm < -1 || power_dbm > 14) throw new Error(`RFO power ${power_dbm} dBm out of range -1-14`);
            this._write_reg(REG_PA_DAC, 0x84);
            this._write_reg(REG_OCP, 0x2B);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let outputPower = Math.round(power_dbm - pmax + 15);
            outputPower = Math.max(0, Math.min(15, outputPower));
            this._write_reg(REG_PA_CONFIG, (maxPower << 4) | (outputPower & 0x0F));
        }
    }

    receive_continuous() {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._set_mode(MODE_RXCONTINUOUS);
    }

    read_packet() {
        if (!(this._read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return null;
        this._write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        return this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
    }

    stop_receive() { this._set_mode(MODE_STDBY); }

    rssi() {
        const r = this._read_reg(REG_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_rssi() {
        const r = this._read_reg(REG_PKT_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_snr() {
        let snr = this._read_reg(REG_PKT_SNR_VALUE);
        if (snr >= 128) snr -= 256;
        return snr / 4.0;
    }

    _digital_write(pin, value) { (void)pin; (void)value; }
    _delay_us(us) { const end = Date.now() + Math.ceil(us / 1000); while (Date.now() < end) {} }
}

class RFM98Full extends RFM98Minimal {
    /**
     * RFM98W full interface — extends RFM98Minimal with configuration and GPIO support.
     * @param {object} transport - Configured SPI transport.
     * @param {number} frequency_hz - Carrier frequency in Hz.
     * @param {number|null} resetPin - Optional GPIO pin for hardware reset.
     * @param {number|null} dio0Pin - Optional GPIO pin for DIO0 interrupt.
     */
    constructor(transport, frequency_hz, resetPin = null, dio0Pin = null) {
        super(transport, frequency_hz);
        this._resetPin = resetPin;
        this._dio0Pin = dio0Pin;
    }

    /**
     * Hardware reset via NRESET pin.
     * @throws {Error} If reset_pin not configured.
     */
    reset() {
        if (!this._resetPin) throw new Error('reset_pin not configured');
        this._digitalWrite(this._resetPin, 0);
        this._delay_us(100);
        this._digitalWrite(this._resetPin, 1);
        this._delay_us(5000);
        this._init();
    }

    /**
     * Configure modem parameters.
     * @param {number} sf - Spreading factor 6-12 (capped at variant MAX_SF).
     * @param {number} bandwidth_khz - Signal bandwidth in kHz (7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500).
     * @param {number} coding_rate - Coding rate denominator 5-8 (4/5, 4/6, 4/7, 4/8).
     * @param {boolean} [crc=true] - Enable CRC generation and verification.
     */
    configure(sf, bandwidth_khz, coding_rate, crc = true) {
        if (sf < 6 || sf > this.MAX_SF) throw new Error(`sf ${sf} out of range 6-${this.MAX_SF}`);
        if (this._LF_BAND && bandwidth_khz > 62.5) throw new Error(`bandwidth ${bandwidth_khz} kHz not supported on LF band`);
        if (!BW_MAP[bandwidth_khz]) throw new Error(`unsupported bandwidth ${bandwidth_khz}`);
        if (!CR_MAP[coding_rate]) throw new Error(`coding_rate ${coding_rate} must be 5-8`);
        this._sf = sf;
        this._bw = bandwidth_khz * 1000;
        this._cr = coding_rate;
        this._crc = crc;
        const bw_bits = BW_MAP[bandwidth_khz];
        const cr_bits = CR_MAP[coding_rate];
        this._write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
        const implicit = (sf === 6) ? 1 : 0;
        this._write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
        if (sf === 6) {
            this._write_reg(0x31, 0x05);
            this._write_reg(0x37, 0x0C);
        }
    }

    set_frequency(frequency_hz) {
        if (frequency_hz < this.FREQ_MIN_HZ || frequency_hz > this.FREQ_MAX_HZ) throw new Error('frequency out of range');
        this._frequency_hz = frequency_hz;
        this._set_frequency(frequency_hz);
    }

    set_tx_power(power_dbm, use_pa_boost = true) {
        if (use_pa_boost) {
            if (power_dbm < 2 || power_dbm > 20) throw new Error(`PA_BOOST power ${power_dbm} dBm out of range 2-20`);
            if (power_dbm > 17) {
                this._write_reg(REG_PA_DAC, 0x87);
                this._write_reg(REG_OCP, 0x3B);
            } else {
                this._write_reg(REG_PA_DAC, 0x84);
                this._write_reg(REG_OCP, 0x2B);
            }
            this._write_reg(REG_PA_CONFIG, 0x80 | ((power_dbm - 2) & 0x0F));
        } else {
            if (power_dbm < -1 || power_dbm > 14) throw new Error(`RFO power ${power_dbm} dBm out of range -1-14`);
            this._write_reg(REG_PA_DAC, 0x84);
            this._write_reg(REG_OCP, 0x2B);
            const maxPower = 7;
            const pmax = 10.8 + 0.6 * maxPower;
            let outputPower = Math.round(power_dbm - pmax + 15);
            outputPower = Math.max(0, Math.min(15, outputPower));
            this._write_reg(REG_PA_CONFIG, (maxPower << 4) | (outputPower & 0x0F));
        }
    }

    receive_continuous() {
        this._set_mode(MODE_STDBY);
        this._write_reg(REG_IRQ_FLAGS, 0xFF);
        this._set_mode(MODE_RXCONTINUOUS);
    }

    read_packet() {
        if (!(this._read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return null;
        this._write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
        const nbBytes = this._read_reg(REG_RX_NB_BYTES);
        this._write_reg(REG_FIFO_ADDR_PTR, this._read_reg(REG_FIFO_RX_CURRENT_ADDR));
        return this._transport.writeRead(Buffer.from([REG_FIFO]), nbBytes);
    }

    stop_receive() { this._set_mode(MODE_STDBY); }

    rssi() {
        const r = this._read_reg(REG_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_rssi() {
        const r = this._read_reg(REG_PKT_RSSI_VALUE);
        return -137 + r * 0.5;
    }

    last_packet_snr() {
        let snr = this._read_reg(REG_PKT_SNR_VALUE);
        if (snr >= 128) snr -= 256;
        return snr / 4.0;
    }

    _digital_write(pin, value) { (void)pin; (void)value; }
    _delay_us(us) { const end = Date.now() + Math.ceil(us / 1000); while (Date.now() < end) {} }
}

module.exports = {
    RFM95Minimal, RFM96Minimal, RFM97Minimal, RFM98Minimal,
    RFM95Full, RFM96Full, RFM97Full, RFM98Full
};