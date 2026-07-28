'use strict';

/**
 * Drives a chip's EN (hardware enable/power) pin.
 */
class OutputPin {
    /**
     * Set the pin's output level.
     * @param {boolean} high - true drives the pin high, false drives it low.
     * @returns {Promise<void>}
     * @abstract
     */
    async set(_high) { throw new Error('abstract'); }
}

/**
 * OutputPin wrapping a pre-constructed [`opengpio`](https://www.npmjs.com/package/opengpio)
 * Output instance (libgpiod character-device write).
 *
 * The caller constructs and configures the Output themselves (e.g. via
 * `Default.output({chip, line})`), matching the existing HX711/SiPo/UART
 * constructor convention of taking already-opened opengpio objects rather
 * than raw pin numbers. `opengpio` is an optional dependency — nothing here
 * calls require('opengpio'); consumers who never wire an EN pin never need
 * it installed.
 */
class GpioOutputPin extends OutputPin {
    /**
     * @param {object} output - opengpio Output instance (boolean `.value`).
     */
    constructor(output) {
        super();
        this._output = output;
    }

    async set(high) {
        this._output.value = !!high;
    }
}

module.exports = { OutputPin, GpioOutputPin };
