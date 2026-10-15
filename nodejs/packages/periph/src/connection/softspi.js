'use strict';

/**
 * Bit-banged SPI master (mode 0, MSB first) over GPIO pins.
 *
 * The software-SPI primitive for the display connection on hosts where
 * spi-device (a hardware controller) is not the right choice. Chip select is
 * not part of the bus object: the connection drives CS itself.
 *
 * Reading needs the MISO input. For a display controller whose DIN and DOUT
 * share one pin, MISO is wired to that pin and MOSI is joined to it through a
 * series resistor, so the host keeps driving MOSI (dummy bytes) while the chip
 * drives the line and wins — no direction switch is needed.
 *
 * sck and mosi are opengpio-style outputs (assign a boolean to `.value`,
 * synchronous); miso is an InputPin read with isHigh().
 */
class SoftSPI {
    /**
     * @param {object} sck - Output for the clock (idles low).
     * @param {object} mosi - Output for the data line to the device.
     * @param {import('./input_pin').InputPin|null} [miso=null] - Input sampling the
     *   data line from the device; null for a write-only bus.
     */
    constructor(sck, mosi, miso = null) {
        this._sck = sck;
        this._mosi = mosi;
        this._miso = miso;
        this._sck.value = false;
    }

    /** @returns {boolean} true when a MISO pin was given. */
    canRead() { return this._miso !== null; }

    /**
     * Shift data out MSB-first, SPI mode 0.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     */
    write(data) {
        for (const byte of data) {
            for (let bit = 7; bit >= 0; bit--) {
                this._mosi.value = !!((byte >> bit) & 1);
                this._sck.value = true;
                this._sck.value = false;
            }
        }
    }

    /**
     * Clock in n bytes from MISO while sending `fill` on MOSI.
     * @param {number} n - Number of bytes to read.
     * @param {number} [fill=0] - Byte driven on MOSI during every clocked byte.
     * @returns {Buffer} The sampled bytes, MSB-first.
     * @throws {Error} If no MISO pin was given.
     */
    read(n, fill = 0x00) {
        if (this._miso === null) throw new Error('SoftSPI has no MISO pin');
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) {
            let value = 0;
            for (let bit = 7; bit >= 0; bit--) {
                this._mosi.value = !!((fill >> bit) & 1);
                this._sck.value = true;
                value = (value << 1) | (this._miso.isHigh() ? 1 : 0);
                this._sck.value = false;
            }
            out[i] = value;
        }
        return out;
    }
}

module.exports = { SoftSPI };
