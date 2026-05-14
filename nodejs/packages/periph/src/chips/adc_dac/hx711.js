'use strict';

const GAIN_TO_PULSES = { 128: 25, 32: 26, 64: 27 };

/**
 * HX711 24-bit ADC — minimal interface.
 *
 * Reads signed 24-bit ADC values using Channel A, Gain 128. No configuration
 * beyond the transport is required. The first post-power-up conversion is
 * discarded during construction.
 */
class HX711Minimal {
    /**
     * @param {object} transport - Configured HX711 transport (HX711Transport).
     */
    constructor(transport) {
        this._transport = transport;
        this._transport.readRaw(25);
    }

    /**
     * Return true if a conversion result is available (DOUT is LOW).
     *
     * Non-blocking.
     *
     * @returns {boolean} True when DOUT is LOW (data ready).
     */
    isReady() {
        return this._transport.isReady();
    }

    /**
     * Block until data is ready and return a signed 24-bit ADC value.
     *
     * Reads Channel A at Gain 128.
     *
     * @returns {number} Signed 24-bit ADC value (-8 388 608 to +8 388 607).
     */
    readRaw() {
        return this._transport.readRaw(25);
    }
}

/**
 * HX711 full interface — extends HX711Minimal with gain, tare, and calibration.
 *
 * Adds gain selection (Channel A Gain 128/64, Channel B Gain 32), multi-sample
 * averaging, tare offset capture, scale factor calibration, and power management.
 */
class HX711Full extends HX711Minimal {
    /**
     * @param {object} transport - Configured HX711 transport (HX711Transport).
     */
    constructor(transport) {
        super(transport);
        this._pulses = 25;
        this._offset = 0;
        this._scale  = 1.0;
    }

    /**
     * Block until data is ready and return a signed 24-bit ADC value.
     *
     * Uses the currently selected channel and gain.
     *
     * @returns {number} Signed 24-bit ADC value.
     */
    readRaw() {
        return this._transport.readRaw(this._pulses);
    }

    /**
     * Select the input channel and gain.
     *
     * Issues one dummy read to apply the new gain before returning.
     *
     * @param {number} gain - 128 (Channel A), 64 (Channel A), or 32 (Channel B).
     * @throws {Error} If gain is not 128, 64, or 32.
     */
    setGain(gain) {
        if (!(gain in GAIN_TO_PULSES))
            throw new Error('gain must be 128, 64, or 32');
        this._pulses = GAIN_TO_PULSES[gain];
        this._transport.readRaw(this._pulses);
    }

    /**
     * Return the average of multiple raw ADC readings.
     * @param {number} times - Number of readings to average (default 10).
     * @returns {number} Average signed 24-bit ADC value.
     */
    readAverage(times = 10) {
        let total = 0;
        for (let i = 0; i < times; i++)
            total += this.readRaw();
        return Math.trunc(total / times);
    }

    /**
     * Capture the current average reading as the zero offset.
     * @param {number} times - Number of readings to average (default 10).
     */
    tare(times = 10) {
        this._offset = this.readAverage(times);
    }

    /**
     * Return the stored tare offset.
     * @returns {number} Offset captured by the last tare() call.
     */
    getOffset() {
        return this._offset;
    }

    /**
     * Set the calibration scale factor.
     *
     * Calibrate: factor = (readAverage() - getOffset()) / known_weight.
     *
     * @param {number} factor - Scale factor (ADC counts per unit weight).
     */
    setScale(factor) {
        this._scale = factor;
    }

    /**
     * Return the current calibration scale factor.
     * @returns {number} Scale factor set by the last setScale() call.
     */
    getScale() {
        return this._scale;
    }

    /**
     * Return the calibrated weight in the units defined by the scale factor.
     *
     * Computes (readAverage(times) - offset) / scale.
     *
     * @param {number} times - Number of readings to average (default 1).
     * @returns {number} Calibrated weight value.
     */
    readWeight(times = 1) {
        return (this.readAverage(times) - this._offset) / this._scale;
    }

    /**
     * Enter power-down mode (PD_SCK held HIGH for >60 µs).
     */
    powerDown() {
        this._transport.powerDown();
    }

    /**
     * Exit power-down, reset chip, discard settling conversion.
     *
     * Resets to Channel A, Gain 128 and discards the first post-reset conversion.
     */
    powerUp() {
        this._transport.powerUp();
        this._pulses = 25;
        this._transport.readRaw(25);
    }
}

module.exports = { HX711Minimal, HX711Full };
