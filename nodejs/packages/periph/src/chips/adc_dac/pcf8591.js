'use strict';

const NUM_CHANNELS = 4;
const CONTROL_DEFAULT = 0x00;  // AIP=00, AOE=0, AI=0, CHN=0

/**
 * PCF8591 8-bit quad ADC + DAC — minimal interface.
 *
 * Provides single-ended reads of the four analog inputs in 4 single-ended
 * mode (AIP=00). No configuration beyond the transport is required. Each
 * read transaction returns 5 bytes: the first is the previous conversion
 * result and must be discarded; the next four are fresh channel samples.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x48–0x4F).
 */
class PCF8591Minimal {
    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        this._transport = transport;
    }

    /**
     * Read a single channel as an unsigned 8-bit value.
     *
     * Uses single-shot conversion: writes the control byte selecting the
     * channel, then reads 2 bytes (discarding the stale first byte).
     *
     * @param {number} channel - Channel number 0–3. Clamped to the valid range.
     * @returns {number} Raw 8-bit value (0–255).
     */
    read_channel(channel) {
        let ch = (channel >= 0 && channel < NUM_CHANNELS) ? channel : 0;
        const ctrl = CONTROL_DEFAULT | (ch & 0x03);
        this._transport.write(Buffer.from([ctrl]));
        const buf = this._transport.read(2);
        return buf[1];
    }

    /**
     * Read all four channels as unsigned 8-bit values.
     *
     * Uses auto-increment (AI=1) to read all four channels in one
     * transaction. Reads 5 bytes and discards the stale first byte.
     *
     * @returns {number[]} Four raw 8-bit values [ch0, ch1, ch2, ch3].
     */
    read_all() {
        const ctrl = CONTROL_DEFAULT | 0x04;  // AI=1
        this._transport.write(Buffer.from([ctrl]));
        const buf = this._transport.read(NUM_CHANNELS + 1);
        return [buf[1], buf[2], buf[3], buf[4]];
    }
}

/**
 * PCF8591 full interface — extends PCF8591Minimal with differential, voltage, and DAC output.
 *
 * Adds analog input mode selection (single-ended, differential, mixed),
 * auto-increment, DAC enable/disable, raw and voltage-calibrated ADC reads,
 * and signed differential reads.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x48–0x4F).
 */
class PCF8591Full extends PCF8591Minimal {
    /** @type {number} 4 single-ended inputs (AIN0–AIN3). */
    static MODE_4_SINGLE_ENDED  = 0;
    /** @type {number} 3 differential inputs (vs AIN3). */
    static MODE_3_DIFFERENTIAL = 1;
    /** @type {number} AIN0/1 single-ended, AIN2-AIN3 differential. */
    static MODE_MIXED          = 2;
    /** @type {number} 2 differential inputs. */
    static MODE_2_DIFFERENTIAL = 3;

    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        super(transport);
        this._control = CONTROL_DEFAULT;
        this._input_mode = PCF8591Full.MODE_4_SINGLE_ENDED;
        this._dac_enabled = false;
        this._auto_increment = false;
        this._last_channel = 0;
    }

    /**
     * Set the analog input mode, auto-increment, and DAC enable.
     * @param {number}  input_mode - Analog input programming 0–3 (see MODE_* constants).
     * @param {boolean} auto_increment - If true, AI=1 — channel increments after each conversion.
     * @param {boolean} dac_enabled - If true, AOE=1 — AOUT is active; AOUT returns to
     *                                 high-impedance when false.
     */
    configure(input_mode, auto_increment, dac_enabled) {
        const aip = input_mode & 0x03;
        const ai  = auto_increment ? 0x04 : 0x00;
        const aoe = dac_enabled     ? 0x40 : 0x00;
        this._control = (aip << 4) | aoe | ai | (this._last_channel & 0x03);
        this._input_mode     = aip;
        this._auto_increment = !!auto_increment;
        this._dac_enabled    = !!dac_enabled;
        this._transport.write(Buffer.from([this._control]));
    }

    /**
     * Read a single channel and convert to voltage.
     * @param {number} channel - Channel number 0–3.
     * @param {number} vref - Reference voltage in volts.
     * @param {number} vagnd - Analog ground voltage in volts.
     * @returns {number} Channel voltage in volts.
     */
    read_channel_voltage(channel, vref, vagnd) {
        const raw = this.read_channel(channel);
        return vagnd + raw * (vref - vagnd) / 256.0;
    }

    /**
     * Read all four channels and convert each to voltage.
     * @param {number} vref - Reference voltage in volts.
     * @param {number} vagnd - Analog ground voltage in volts.
     * @returns {number[]} Four channel voltages in volts [ch0, ch1, ch2, ch3].
     */
    read_all_voltage(vref, vagnd) {
        const raws = this.read_all();
        const vfs = vref - vagnd;
        return raws.map(r => vagnd + r * vfs / 256.0);
    }

    /**
     * Read a differential channel as a signed value.
     *
     * The chip must be configured in a differential mode (input_mode 1, 2,
     * or 3). The result is interpreted as a signed 8-bit two's complement
     * number.
     *
     * @param {number} channel - Differential channel index (0–2 for 3-diff mode, 0–1
     *                           for 2-diff and mixed modes).
     * @returns {number} Signed 8-bit value (-128 to 127).
     */
    read_differential(channel) {
        const ch = channel & 0x03;
        this._last_channel = ch;
        const ctrl = this._control | (ch & 0x03);
        this._transport.write(Buffer.from([ctrl]));
        const buf = this._transport.read(2);
        const raw = buf[1];
        return raw >= 128 ? raw - 256 : raw;
    }

    /**
     * Enable the DAC and write a raw 8-bit value.
     *
     * Sets the AOE bit so AOUT becomes active, then writes the DAC value
     * in the byte following the control byte.
     *
     * @param {number} value - Raw 8-bit DAC value (0–255). Output voltage is
     *                         V_AGND + value × (V_REF − V_AGND) / 256.
     */
    set_dac(value) {
        let v = Math.max(0, Math.min(255, value | 0));
        const ctrl = (this._control | 0x40) & ~0x04;  // AOE=1, AI=0
        this._control = ctrl;
        this._dac_enabled = true;
        this._transport.write(Buffer.from([ctrl, v]));
    }

    /**
     * Enable the DAC and set the output as a fraction of (VREF−VAGND).
     * @param {number} voltage_fraction - Output level as a fraction of (VREF−VAGND)
     *                                   (0.0 = V_AGND, 1.0 = V_REF). Clamped to [0.0, 1.0].
     */
    set_dac_voltage(voltage_fraction) {
        let f = voltage_fraction;
        if (f < 0.0) f = 0.0;
        if (f > 1.0) f = 1.0;
        const value = Math.round(f * 255);
        this.set_dac(value);
    }

    /**
     * Disable the DAC output; AOUT returns to high-impedance.
     */
    disable_dac() {
        const ctrl = this._control & ~0x40;  // AOE=0
        this._control = ctrl;
        this._dac_enabled = false;
        this._transport.write(Buffer.from([ctrl]));
    }
}

module.exports = { PCF8591Minimal, PCF8591Full };
