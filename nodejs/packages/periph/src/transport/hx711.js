'use strict';

const Gpio = require('onoff').Gpio;

/**
 * HX711 GPIO bit-bang transport for Node.js (wraps onoff Gpio).
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each rising edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * The DOUT poll loop uses a synchronous spin (readSync) which is acceptable
 * for short waits during the clock cycle itself. The blocking wait before the
 * cycle uses setImmediate yielding to avoid monopolising the event loop.
 */
class HX711Transport {
    /**
     * @param {object} dout   - onoff Gpio instance configured as 'in'.
     * @param {object} pd_sck - onoff Gpio instance configured as 'out'.
     */
    constructor(dout, pd_sck) {
        this._dout = dout;
        this._sck  = pd_sck;
        this._sck.writeSync(0);
    }

    /**
     * Return true if a conversion result is available (DOUT is LOW).
     *
     * Non-blocking.
     *
     * @returns {boolean} True when DOUT is LOW (data ready).
     */
    isReady() {
        return this._dout.readSync() === 0;
    }

    /**
     * Block (synchronously spin) until data is ready, then clock out a conversion.
     *
     * Sends exactly numPulses rising edges on PD_SCK and samples DOUT on each
     * one. The pulse count programs the channel and gain for the next conversion:
     * 25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *
     * @param {number} numPulses - Number of PD_SCK pulses (must be 25, 26, or 27).
     * @returns {number} Signed 24-bit ADC value.
     * @throws {Error} If numPulses is not 25, 26, or 27.
     */
    readRaw(numPulses = 25) {
        if (numPulses !== 25 && numPulses !== 26 && numPulses !== 27)
            throw new Error('numPulses must be 25, 26, or 27');
        while (this._dout.readSync() !== 0) {}
        let raw = 0;
        for (let i = 0; i < numPulses; i++) {
            this._sck.writeSync(1);
            raw = (raw << 1) | (this._dout.readSync() === 0 ? 1 : 0);
            this._sck.writeSync(0);
        }
        raw >>>= numPulses - 24;
        if (raw >= 0x800000) raw -= 0x1000000;
        return raw;
    }

    /**
     * Enter power-down mode by holding PD_SCK HIGH for >60 µs.
     *
     * Uses a busy-spin for the delay since Node.js has no µs sleep.
     */
    powerDown() {
        this._sck.writeSync(1);
        const end = Date.now() + 1;  // 1 ms >> 60 µs, safe margin
        while (Date.now() < end) {}
    }

    /**
     * Exit power-down mode and reset the chip.
     *
     * Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
     * conversion after power-up must be discarded.
     */
    powerUp() {
        this._sck.writeSync(0);
    }

    /**
     * Release both GPIO pins. Must be called when the transport is no longer needed.
     */
    close() {
        this._dout.unexport();
        this._sck.unexport();
    }
}

module.exports = { HX711Transport };
