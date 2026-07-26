'use strict';

const spi = require('spi-device');

/**
 * SiPo (serial-in/parallel-out shift register) transport for Node.js.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Shifts data over
 * either a hardware spi-device or a bit-banged pair of opengpio Output objects.
 * RCK — and, if configured, SRCLR/G — are always plain opengpio Output objects,
 * independent of which SPI mode is used.
 *
 * Write-only: there is no read() or writeRead().
 *
 * Exactly one of {busNumber, deviceNumber} (hardware) or {serIn, srck}
 * (software) must be given in options.
 */
class SiPoTransport {
    /**
     * @param {object} rck - opengpio Output instance (register clock).
     * @param {object} [options]
     * @param {object} [options.srclr] - opengpio Output instance for SRCLR; omit to disable.
     * @param {object} [options.g] - opengpio Output instance for G (output enable); omit to disable.
     * @param {number} [options.busNumber] - Hardware mode: SPI bus number (opens spi-device).
     * @param {number} [options.deviceNumber] - Hardware mode: chip-select line on the bus.
     * @param {number} [options.maxSpeedHz=1000000] - Hardware mode: SPI clock in Hz.
     * @param {object} [options.serIn] - Software mode: opengpio Output instance for SER IN.
     * @param {object} [options.srck] - Software mode: opengpio Output instance for SRCK.
     */
    constructor(rck, options = {}) {
        const hardware = options.busNumber !== undefined;
        const software = options.serIn !== undefined;
        if (hardware === software) {
            throw new Error(
                'specify exactly one of {busNumber, deviceNumber} or {serIn, srck}');
        }

        this._rck = rck;
        this._srclr = options.srclr ?? null;
        this._g = options.g ?? null;
        this._serIn = options.serIn ?? null;
        this._srck = options.srck ?? null;

        if (hardware) {
            this._device = spi.openSync(options.busNumber, options.deviceNumber, {
                mode: spi.MODE0,
                maxSpeedHz: options.maxSpeedHz ?? 1_000_000,
            });
        } else {
            this._device = null;
        }

        this._rck.value = false;
        if (this._srclr !== null) this._srclr.value = true;
        if (this._g !== null) this._g.value = false;
    }

    /**
     * Shift data out MSB-first, then latch it into the output register.
     *
     * In hardware mode this transfers data over spi-device; in software mode
     * it bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW
     * to latch the shifted data into the storage register that drives the
     * outputs.
     *
     * @param {Buffer|Uint8Array} data - Bytes to shift out, one byte per cascaded device.
     */
    write(data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (this._device !== null) {
            this._device.transferSync([{ sendBuffer: buf, byteLength: buf.length }]);
        } else {
            for (const byte of buf) {
                for (let bit = 7; bit >= 0; bit--) {
                    this._serIn.value = !!((byte >> bit) & 1);
                    this._srck.value = true;
                    this._srck.value = false;
                }
            }
        }
        this._rck.value = true;
        this._rck.value = false;
    }

    /**
     * Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     * The storage register (and therefore the outputs) is unaffected until
     * the next write().
     *
     * @throws {Error} If srclr was not configured.
     */
    clear() {
        if (this._srclr === null) throw new Error('SRCLR not configured');
        this._srclr.value = false;
        this._srclr.value = true;
    }

    /**
     * Drive G LOW (enabled) or HIGH (disabled).
     *
     * @param {boolean} enabled - true drives G LOW, letting the storage
     *   register drive the outputs. false drives G HIGH, forcing every
     *   output off without disturbing the storage register's contents.
     * @throws {Error} If g was not configured.
     */
    setOutputEnable(enabled) {
        if (this._g === null) throw new Error('G not configured');
        this._g.value = !enabled;
    }

    /**
     * Close the SPI device (if opened) and stop all configured GPIO pins.
     */
    close() {
        if (this._device !== null) this._device.closeSync();
        this._rck.stop();
        if (this._srclr !== null) this._srclr.stop();
        if (this._g !== null) this._g.stop();
        if (this._serIn !== null) this._serIn.stop();
        if (this._srck !== null) this._srck.stop();
    }
}

module.exports = { SiPoTransport };
