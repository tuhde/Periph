'use strict';

const CMD_MULTI_WRITE_BASE = 0x40;   // [0 1 0 0 0 DAC1 DAC0 UDAC]
const CMD_SINGLE_WRITE     = 0x58;   // [0 1 0 1 1 DAC1 DAC0 UDAC]
const CMD_SEQUENTIAL_BASE  = 0x50;   // [0 1 0 1 0 DAC1 DAC0 UDAC]
const CMD_WRITE_VREF       = 0x80;   // [1 0 0 X Vref_A Vref_B Vref_C Vref_D]
const CMD_WRITE_GAIN       = 0xC0;   // [1 1 0 X Gx_A Gx_B Gx_C Gx_D]
const CMD_WRITE_POWERDOWN  = 0xA0;   // [1 0 1 X ...]
const ADDR_GENERAL_CALL    = 0x00;
const GC_RESET             = 0x06;
const GC_SOFTWARE_UPD      = 0x08;
const GC_WAKE              = 0x09;

/**
 * MCP4728 quad-channel 12-bit voltage-output DAC — minimal interface.
 *
 * Provides simple voltage output as a fraction of V_DD for any of the four
 * channels (A–D) plus a convenience method to update all four channels
 * simultaneously. No configuration required beyond the transport. V_REF is
 * fixed at external (V_DD), gain is fixed at ×1, and power-down is off.
 * EEPROM is never written by this class.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x60–0x67).
 */
class MCP4728Minimal {
    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        this._transport = transport;
    }

    /**
     * Set one channel's DAC output as a fraction of V_DD.
     * @param {number} channel - Channel index 0 (A) – 3 (D).
     * @param {number} fraction - Output voltage as a fraction of V_DD (0.0–1.0).
     */
    set_voltage(channel, fraction) {
        let f = Math.max(0.0, Math.min(1.0, fraction));
        const code = Math.round(f * 4095);
        this.set_raw(channel, code);
    }

    /**
     * Set one channel's raw 12-bit DAC code.
     * @param {number} channel - Channel index 0 (A) – 3 (D).
     * @param {number} code - Raw 12-bit DAC code (0–4095).
     */
    set_raw(channel, code) {
        const ch = Math.max(0, Math.min(3, channel | 0));
        const c = Math.max(0, Math.min(4095, code | 0));
        this._multi_write(ch, c, 0, 0, 0, 0);
    }

    /**
     * Update all four channels simultaneously using Fast Write.
     * @param {number[]} fractions - Array of 4 fractions (0.0–1.0), index 0 = A.
     */
    set_all(fractions) {
        if (!Array.isArray(fractions) || fractions.length !== 4) {
            throw new Error('fractions must have exactly 4 elements');
        }
        const buf = Buffer.alloc(8);
        for (let i = 0; i < 4; i++) {
            let f = Math.max(0.0, Math.min(1.0, fractions[i]));
            const code = Math.max(0, Math.min(4095, Math.round(f * 4095)));
            // Fast Write: byte1=[0 0 PD1 PD0 D11-D8] (PD=00), byte2=[D7-D0]
            buf[i * 2]     = (code >> 8) & 0x0F;
            buf[i * 2 + 1] = code & 0xFF;
        }
        this._transport.write(buf);
    }

    _multi_write(channel, code, vref, pd, gain, udac) {
        const buf = Buffer.alloc(3);
        buf[0] = CMD_MULTI_WRITE_BASE | ((channel & 0x03) << 1) | (udac & 0x01);
        buf[1] = ((vref & 0x01) << 7) | ((pd & 0x03) << 5) |
                 ((gain & 0x01) << 4) | ((code >> 8) & 0x0F);
        buf[2] = code & 0xFF;
        this._transport.write(buf);
    }
}

/**
 * MCP4728 full interface — extends MCP4728Minimal with EEPROM, V_REF, gain, power-down, and read-back.
 *
 * Adds per-channel V_REF and gain configuration, all-channel V_REF/gain/
 * power-down commands, write-with-EEPROM persistence (Single and Sequential
 * Write), General Call reset/wake-up/software-update, and full 24-byte
 * read-back of all channel DAC input registers and EEPROM contents.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x60–0x67).
 */
class MCP4728Full extends MCP4728Minimal {
    /** @type {number} Normal operation (power-down mode 0). */
    static PD_NORMAL   = 0;
    /** @type {number} Power-down with 1 kΩ to GND (mode 1). */
    static PD_1K_GND   = 1;
    /** @type {number} Power-down with 100 kΩ to GND (mode 2). */
    static PD_100K_GND = 2;
    /** @type {number} Power-down with 500 kΩ to GND (mode 3). */
    static PD_500K_GND = 3;

    /** @type {number} External V_DD reference. */
    static VREF_EXTERNAL = 0;
    /** @type {number} Internal 2.048 V reference. */
    static VREF_INTERNAL = 1;

    /** @type {number} Gain ×1. */
    static GAIN_X1 = 0;
    /** @type {number} Gain ×2. */
    static GAIN_X2 = 1;

    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        super(transport);
    }

    /**
     * Set one channel's output and persist to EEPROM (Single Write).
     * @param {number} channel - Channel index 0 (A) – 3 (D).
     * @param {number} fraction - Output as a fraction of the configured full-scale.
     * @param {number} vref - 0 = external (V_DD), 1 = internal (2.048 V).
     * @param {number} gain - 1 = ×1, 2 = ×2 (ignored when vref = external).
     */
    set_voltage_eeprom(channel, fraction, vref, gain) {
        let f = Math.max(0.0, Math.min(1.0, fraction));
        const code = Math.round(f * 4095);
        this._single_write(channel, code, vref, 0, gain, 0);
    }

    /**
     * Set one channel's raw 12-bit code and persist to EEPROM.
     * @param {number} channel - Channel index 0 (A) – 3 (D).
     * @param {number} code - Raw 12-bit DAC code (0–4095).
     * @param {number} vref - 0 = external (V_DD), 1 = internal (2.048 V).
     * @param {number} gain - 1 = ×1, 2 = ×2 (ignored when vref = external).
     */
    set_raw_eeprom(channel, code, vref, gain) {
        const c = Math.max(0, Math.min(4095, code | 0));
        this._single_write(channel, c, vref, 0, gain, 0);
    }

    /**
     * Update all four channels and EEPROM (Sequential Write from A to D).
     * @param {number[]} fractions - Array of 4 fractions (0.0–1.0).
     * @param {number[]} vrefs - Array of 4 V_REF values (0/1).
     * @param {number[]} gains - Array of 4 gain values (1/2).
     */
    set_all_eeprom(fractions, vrefs, gains) {
        if (fractions.length !== 4 || vrefs.length !== 4 || gains.length !== 4) {
            throw new Error('fractions, vrefs, gains must each have 4 elements');
        }
        const buf = Buffer.alloc(9);
        // Sequential Write starting at channel 0 (A): [0 1 0 1 0 0 0 UDAC] = 0x50
        buf[0] = CMD_SEQUENTIAL_BASE | 0x00;
        for (let i = 0; i < 4; i++) {
            let f = Math.max(0.0, Math.min(1.0, fractions[i]));
            const code = Math.max(0, Math.min(4095, Math.round(f * 4095)));
            const v = vrefs[i] ? 1 : 0;
            const g = (gains[i] === 2) ? 1 : 0;
            // Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
            // PD bits are always 0 here (no power-down is set in this command).
            buf[1 + i * 2]     = ((v & 0x01) << 7) | ((g & 0x01) << 4) | ((code >> 8) & 0x0F);
            buf[1 + i * 2 + 1] = code & 0xFF;
        }
        this._transport.write(buf);
    }

    /**
     * Set V_REF for all four channels (volatile register only).
     * @param {number} vref_a - 0 = external V_DD, 1 = internal 2.048 V.
     * @param {number} vref_b
     * @param {number} vref_c
     * @param {number} vref_d
     */
    set_vref(vref_a, vref_b, vref_c, vref_d) {
        const byte1 = CMD_WRITE_VREF |
            ((vref_a ? 1 : 0) << 3) | ((vref_b ? 1 : 0) << 2) |
            ((vref_c ? 1 : 0) << 1) |  (vref_d ? 1 : 0);
        this._transport.write(Buffer.from([byte1]));
    }

    /**
     * Set gain for all four channels (volatile register only).
     * @param {number} gain_a - 1 = ×1, 2 = ×2.
     * @param {number} gain_b
     * @param {number} gain_c
     * @param {number} gain_d
     */
    set_gain(gain_a, gain_b, gain_c, gain_d) {
        const byte1 = CMD_WRITE_GAIN |
            ((gain_a === 2 ? 1 : 0) << 3) | ((gain_b === 2 ? 1 : 0) << 2) |
            ((gain_c === 2 ? 1 : 0) << 1) |  (gain_d === 2 ? 1 : 0);
        this._transport.write(Buffer.from([byte1]));
    }

    /**
     * Set power-down mode for all four channels (volatile register only).
     * @param {number} pd_a - 0–3.
     * @param {number} pd_b
     * @param {number} pd_c
     * @param {number} pd_d
     */
    set_power_down(pd_a, pd_b, pd_c, pd_d) {
        const byte1 = CMD_WRITE_POWERDOWN |
            (((pd_a >> 1) & 0x01) << 4) | ((pd_a & 0x01) << 3) |
            (((pd_b >> 1) & 0x01) << 2) | ((pd_b & 0x01) << 1);
        const byte2 = (((pd_c >> 1) & 0x01) << 6) | ((pd_c & 0x01) << 5) |
                      (((pd_d >> 1) & 0x01) << 4) | ((pd_d & 0x01) << 3);
        this._transport.write(Buffer.from([byte1, byte2]));
    }

    /**
     * Read all four channels' DAC input registers and EEPROM contents.
     * @returns {{channel: object[], eeprom_ready: boolean}} Per-channel code, vref, gain, power_down, eeprom_*.
     */
    read() {
        const buf = this._transport.read(24);
        const result = { channel: [], eeprom_ready: !!(buf[0] & 0x80) };
        for (let i = 0; i < 4; i++) {
            const b = i * 3;
            result.channel.push({
                code: ((buf[b + 1] & 0x0F) << 8) | buf[b + 2],
                vref: (buf[b + 1] >> 7) & 0x01,
                gain: ((buf[b + 1] >> 4) & 0x01) ? 2 : 1,
                power_down: (buf[b + 1] >> 5) & 0x03,
            });
        }
        for (let i = 0; i < 4; i++) {
            const b = 12 + i * 3;
            result.channel[i].eeprom_code = ((buf[b + 1] & 0x0F) << 8) | buf[b + 2];
            result.channel[i].eeprom_vref = (buf[b + 1] >> 7) & 0x01;
            result.channel[i].eeprom_gain = ((buf[b + 1] >> 4) & 0x01) ? 2 : 1;
            result.channel[i].eeprom_power_down = (buf[b + 1] >> 5) & 0x03;
        }
        return result;
    }

    /**
     * Check if the EEPROM write is complete (RDY/BSY = 1).
     * @returns {boolean}
     */
    is_eeprom_ready() {
        const buf = this._transport.read(1);
        return !!(buf[0] & 0x80);
    }

    /**
     * Send General Call Software Update (0x00, 0x08) to latch all V_OUT.
     */
    software_update() {
        this._transport.write(Buffer.from([ADDR_GENERAL_CALL, GC_SOFTWARE_UPD]));
    }

    /**
     * Send General Call Wake-Up (0x00, 0x09) to clear all PD bits.
     */
    wake_up() {
        this._transport.write(Buffer.from([ADDR_GENERAL_CALL, GC_WAKE]));
    }

    /**
     * Send General Call Reset (0x00, 0x06) to reload EEPROM into all DAC registers.
     */
    reset() {
        this._transport.write(Buffer.from([ADDR_GENERAL_CALL, GC_RESET]));
    }

    _single_write(channel, code, vref, pd, gain, udac) {
        const ch = Math.max(0, Math.min(3, channel | 0));
        const c = Math.max(0, Math.min(4095, code | 0));
        const buf = Buffer.alloc(3);
        buf[0] = CMD_SINGLE_WRITE | ((ch & 0x03) << 1) | (udac & 0x01);
        buf[1] = ((vref & 0x01) << 7) | ((pd & 0x03) << 5) |
                 ((gain & 0x01) << 4) | ((c >> 8) & 0x0F);
        buf[2] = c & 0xFF;
        this._transport.write(buf);
    }
}

module.exports = { MCP4728Minimal, MCP4728Full };
