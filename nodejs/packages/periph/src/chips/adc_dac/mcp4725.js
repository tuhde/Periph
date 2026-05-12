'use strict';

const CMD_FAST_WRITE = 0x00;
const CMD_WRITE_DAC_EEPROM = 0x60;
const ADDR_GENERAL_CALL = 0x00;
const GC_RESET = 0x06;
const GC_WAKE = 0x09;

/**
 * MCP4725 single-channel 12-bit voltage-output DAC — minimal interface.
 *
 * Provides simple voltage output as a fraction of V_DD with no configuration
 * beyond the transport. Uses Fast Write (2-byte) for DAC register updates.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x60–0x61).
 */
class MCP4725Minimal {
    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        this._transport = transport;
    }

    /**
     * Set the DAC output as a fraction of V_DD.
     * @param {number} fraction - Output voltage as a fraction of V_DD (0.0–1.0).
     */
    set_voltage(fraction) {
        if (fraction < 0.0) fraction = 0.0;
        if (fraction > 1.0) fraction = 1.0;
        const code = Math.round(fraction * 4095);
        this._fast_write(code, 0);
    }

    /**
     * Set the raw 12-bit DAC code directly.
     * @param {number} code - Raw 12-bit DAC code (0–4095).
     */
    set_raw(code) {
        if (code > 4095) code = 4095;
        this._fast_write(code, 0);
    }

    _fast_write(code, pd_mode) {
        const buf = Buffer.alloc(2);
        buf[0] = ((pd_mode & 0x03) << 4) | ((code >> 8) & 0x0F);
        buf[1] = code & 0xFF;
        this._transport.write(buf);
    }
}

/**
 * MCP4725 full interface — extends MCP4725Minimal with EEPROM, power-down, and read-back.
 *
 * Adds write-with-EEPROM persistence, power-down modes, General Call reset/wake,
 * and full register read-back of both DAC and EEPROM contents.
 *
 * @param {object} transport - Configured I²C transport pointing at the device (0x60–0x61).
 */
class MCP4725Full extends MCP4725Minimal {
    /**
     * @param {object} transport - Configured I²C transport pointing at the device.
     */
    constructor(transport) {
        super(transport);
    }

    /** @type {number} Normal operation (power-down mode 0). */
    static PD_NORMAL  = 0;
    /** @type {number} Power-down with 1 kΩ to GND (mode 1). */
    static PD_1K_GND  = 1;
    /** @type {number} Power-down with 100 kΩ to GND (mode 2). */
    static PD_100K_GND = 2;
    /** @type {number} Power-down with 500 kΩ to GND (mode 3). */
    static PD_500K_GND = 3;

    /**
     * Set the DAC output and persist to EEPROM.
     * @param {number} fraction - Output voltage as a fraction of V_DD (0.0–1.0).
     */
    set_voltage_eeprom(fraction) {
        if (fraction < 0.0) fraction = 0.0;
        if (fraction > 1.0) fraction = 1.0;
        const code = Math.round(fraction * 4095);
        this._write_dac_eeprom(code, 0);
    }

    /**
     * Set the raw 12-bit DAC code and persist to EEPROM.
     * @param {number} code - Raw 12-bit DAC code (0–4095).
     */
    set_raw_eeprom(code) {
        if (code > 4095) code = 4095;
        this._write_dac_eeprom(code, 0);
    }

    /**
     * Read the current DAC register and EEPROM contents.
     * @returns {{code: number, voltage_fraction: number, power_down: number, eeprom_code: number, eeprom_power_down: number, eeprom_ready: boolean}} Dictionary with code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready.
     */
    read() {
        const buf = this._transport.writeRead(Buffer.from([0x00]), 5);
        return {
            code: ((buf[1] & 0x0F) << 8) | buf[2],
            voltage_fraction: (((buf[1] & 0x0F) << 8) | buf[2]) / 4095.0,
            power_down: (buf[0] >> 2) & 0x03,
            eeprom_code: ((buf[3] & 0x0F) << 8) | buf[4],
            eeprom_power_down: (buf[3] >> 6) & 0x03,
            eeprom_ready: !!(buf[0] & 0x80),
        };
    }

    /**
     * Set the power-down mode and preserve the current DAC code.
     * @param {number} mode - Power-down mode 0–3 (0 = normal, 1 = 1 kΩ to GND, 2 = 100 kΩ to GND, 3 = 500 kΩ to GND).
     */
    set_power_down(mode) {
        if (mode > 3) mode = 3;
        const code = this._read_dac_code();
        this._fast_write(code, mode);
    }

    /**
     * Send General Call Wake-Up (0x00, 0x09) to clear power-down bits.
     */
    wake_up() {
        this._transport.write(Buffer.from([ADDR_GENERAL_CALL, GC_WAKE]));
    }

    /**
     * Send General Call Reset (0x00, 0x06) to trigger internal POR.
     */
    reset() {
        this._transport.write(Buffer.from([ADDR_GENERAL_CALL, GC_RESET]));
    }

    /**
     * Check if the EEPROM write operation is complete.
     * @returns {boolean} True when a pending EEPROM write has finished.
     */
    is_eeprom_ready() {
        const buf = this._transport.writeRead(Buffer.from([0x00]), 1);
        return !!(buf[0] & 0x80);
    }

    _write_dac_eeprom(code, pd_mode) {
        const buf = Buffer.alloc(3);
        buf[0] = CMD_WRITE_DAC_EEPROM | ((pd_mode & 0x03) << 1);
        buf[1] = (code >> 4) & 0xFF;
        buf[2] = (code & 0x0F) << 4;
        this._transport.write(buf);
    }

    _read_dac_code() {
        const buf = this._transport.writeRead(Buffer.from([0x00]), 2);
        return ((buf[0] & 0x0F) << 8) | buf[1];
    }
}

module.exports = { MCP4725Minimal, MCP4725Full };