'use strict';

const RATE_TO_PULSES = { 10: 25, 40: 27 };

/**
 * HX710A 24-bit ADC — minimal interface.
 *
 * Reads signed 24-bit ADC values from the single differential input
 * (INN/INP) at the chip's fixed Gain 128, 10 SPS default. No configuration
 * beyond the connection is required. The first post-power-up conversion is
 * discarded during construction.
 */
class HX710AMinimal {
    /**
     * @param {import('../../connection/hx711').HX711Connection} connection - Configured HX711 connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._conn.readRaw(25);
    }

    /**
     * Return true if a conversion result is available (DOUT is LOW).
     *
     * Non-blocking.
     *
     * @returns {boolean} True when DOUT is LOW (data ready).
     */
    isReady() {
        return this._conn.isReady();
    }

    /**
     * Block until data is ready and return a signed 24-bit ADC value.
     *
     * Reads the differential input at Gain 128, 10 SPS.
     *
     * @returns {number} Signed 24-bit ADC value (-8 388 608 to +8 388 607).
     */
    readRaw() {
        return this._conn.readRaw(25);
    }
}

/**
 * HX710A full interface — extends HX710AMinimal with rate selection, tare,
 * calibration, temperature, and power management.
 *
 * The HX710A has no software-selectable gain and no second input channel —
 * unlike the HX711, only the output rate (10 or 40 SPS) is selectable for
 * the differential-input reading.
 */
class HX710AFull extends HX710AMinimal {
    /**
     * @param {import('../../connection/hx711').HX711Connection} connection - Configured HX711 connection.
     */
    constructor(connection) {
        super(connection);
        this._pulses = 25;
        this._offset = 0;
        this._scale  = 1.0;
    }

    /**
     * Block until data is ready and return a signed 24-bit ADC value.
     *
     * Uses the currently selected output rate.
     *
     * @returns {number} Signed 24-bit ADC value.
     */
    readRaw() {
        return this._conn.readRaw(this._pulses);
    }

    /**
     * Select the differential-input output rate.
     *
     * Issues one dummy read to apply the new rate before returning.
     *
     * @param {number} rate - 10 or 40 (samples per second).
     * @throws {Error} If rate is not 10 or 40.
     */
    setRate(rate) {
        if (!(rate in RATE_TO_PULSES))
            throw new Error('rate must be 10 or 40');
        this._pulses = RATE_TO_PULSES[rate];
        this._conn.readRaw(this._pulses);
    }

    /**
     * Return the average of multiple raw differential-input readings.
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
     * Block until data is ready and return the raw on-chip temperature code.
     *
     * Clocks 26 pulses (temperature channel, 40 Hz). This is **not** a
     * calibrated degrees-Celsius value — the datasheet gives only a typical
     * resolution of ~20.4 LSB/°C and states offset and gain vary
     * significantly chip-to-chip. Use for relative drift tracking, or
     * calibrate against a reference thermometer for absolute readings.
     *
     * @returns {number} Signed 24-bit raw ADC code from the temperature sensor.
     */
    readTemperatureRaw() {
        return this._conn.readRaw(26);
    }

    /**
     * Enter power-down mode (PD_SCK held HIGH for >60 µs).
     */
    powerDown() {
        this._conn.powerDown();
    }

    /**
     * Exit power-down, reset chip, discard settling conversion.
     *
     * Resets to the differential input at Gain 128, 10 SPS and discards
     * the first post-reset conversion.
     */
    powerUp() {
        this._conn.powerUp();
        this._pulses = 25;
        this._conn.readRaw(25);
    }
}

module.exports = { HX710AMinimal, HX710AFull };
