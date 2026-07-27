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
 * OutputPin wrapping a pre-constructed `onoff` Gpio instance (sysfs GPIO write).
 *
 * The caller constructs and configures the Gpio ('out' direction) themselves,
 * matching the existing HX711/DHTxx/SiPo constructor convention of taking
 * already-opened onoff objects rather than raw pin numbers.
 */
class SysfsOutputPin extends OutputPin {
    /**
     * @param {object} gpio - onoff Gpio instance configured as 'out'.
     */
    constructor(gpio) {
        super();
        this._gpio = gpio;
    }

    async set(high) {
        this._gpio.writeSync(high ? 1 : 0);
    }
}

/**
 * OutputPin wrapping a pre-opened `node-libgpiod` output line.
 *
 * `node-libgpiod` is an optional peer dependency: nothing here calls
 * require('node-libgpiod') — the caller opens the line and passes it in, so
 * consumers who use SysfsOutputPin instead never need it installed.
 */
class GpiodOutputPin extends OutputPin {
    /**
     * @param {object} line - node-libgpiod output Line instance, already requested as output.
     */
    constructor(line) {
        super();
        this._line = line;
    }

    async set(high) {
        this._line.setValue(high ? 1 : 0);
    }
}

module.exports = { OutputPin, SysfsOutputPin, GpiodOutputPin };
