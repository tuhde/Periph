'use strict';

const spi = require('spi-device');

/**
 * SiPo (serial-in/parallel-out shift register) connection for Node.js.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Shifts data over
 * either a hardware spi-device or a bit-banged pair of onoff Gpio objects.
 * RCK — and, if configured, SRCLR/G — are always plain onoff Gpio objects,
 * independent of which SPI mode is used.
 *
 * Write-only: there is no read() or writeRead().
 *
 * Exactly one of {busNumber, deviceNumber} (hardware) or {serIn, srck}
 * (software) must be given in options.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag and
 * enPin instead.
 */
class SiPoConnection {
    /**
     * @param {object} rck - onoff Gpio instance configured as 'out' (register clock).
     * @param {object} [options]
     * @param {object} [options.srclr] - onoff Gpio instance configured as 'out' for SRCLR; omit to disable.
     * @param {object} [options.g] - onoff Gpio instance configured as 'out' for G (output enable); omit to disable.
     * @param {number} [options.busNumber] - Hardware mode: SPI bus number (opens spi-device).
     * @param {number} [options.deviceNumber] - Hardware mode: chip-select line on the bus.
     * @param {number} [options.maxSpeedHz=1000000] - Hardware mode: SPI clock in Hz.
     * @param {object} [options.serIn] - Software mode: onoff Gpio instance configured as 'out' for SER IN.
     * @param {object} [options.srck] - Software mode: onoff Gpio instance configured as 'out' for SRCK.
     * @param {import('./output_pin').OutputPin|null} [options.enPin=null] - Optional EN-pin OutputPin.
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
        this.enPin = options.enPin ?? null;
        this._enabled = true;

        if (hardware) {
            this._device = spi.openSync(options.busNumber, options.deviceNumber, {
                mode: spi.MODE0,
                maxSpeedHz: options.maxSpeedHz ?? 1_000_000,
            });
        } else {
            this._device = null;
        }

        this._rck.writeSync(0);
        if (this._srclr !== null) this._srclr.writeSync(1);
        if (this._g !== null) this._g.writeSync(0);
    }

    /** Resume writes; drives the hardware EN pin high if wired.
     * @returns {Promise<void>}
     */
    async enable() {
        this._enabled = true;
        if (this.enPin) await this.enPin.set(true);
    }

    /** Gate write()/clear()/setOutputEnable(); drives the hardware EN pin low if wired.
     * @returns {Promise<void>}
     */
    async disable() {
        this._enabled = false;
        if (this.enPin) await this.enPin.set(false);
    }

    /** @returns {boolean} The current software-gate state. */
    isEnabled() { return this._enabled; }

    /**
     * Shift data out MSB-first, then latch it into the output register.
     *
     * In hardware mode this transfers data over spi-device; in software mode
     * it bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW
     * to latch the shifted data into the storage register that drives the
     * outputs.
     *
     * No-op if this connection is disabled.
     *
     * @param {Buffer|Uint8Array} data - Bytes to shift out, one byte per cascaded device.
     */
    write(data) {
        if (!this._enabled) return;
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (this._device !== null) {
            this._device.transferSync([{ sendBuffer: buf, byteLength: buf.length }]);
        } else {
            for (const byte of buf) {
                for (let bit = 7; bit >= 0; bit--) {
                    this._serIn.writeSync((byte >> bit) & 1);
                    this._srck.writeSync(1);
                    this._srck.writeSync(0);
                }
            }
        }
        this._rck.writeSync(1);
        this._rck.writeSync(0);
    }

    /**
     * Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     * The storage register (and therefore the outputs) is unaffected until
     * the next write(). No-op if this connection is disabled.
     *
     * @throws {Error} If srclr was not configured.
     */
    clear() {
        if (!this._enabled) return;
        if (this._srclr === null) throw new Error('SRCLR not configured');
        this._srclr.writeSync(0);
        this._srclr.writeSync(1);
    }

    /**
     * Drive G LOW (enabled) or HIGH (disabled).
     *
     * No-op if this connection is disabled.
     *
     * @param {boolean} enabled - true drives G LOW, letting the storage
     *   register drive the outputs. false drives G HIGH, forcing every
     *   output off without disturbing the storage register's contents.
     * @throws {Error} If g was not configured.
     */
    setOutputEnable(enabled) {
        if (!this._enabled) return;
        if (this._g === null) throw new Error('G not configured');
        this._g.writeSync(enabled ? 0 : 1);
    }

    /**
     * Close the SPI device (if opened) and unexport all configured GPIO pins.
     */
    close() {
        if (this._device !== null) this._device.closeSync();
        this._rck.unexport();
        if (this._srclr !== null) this._srclr.unexport();
        if (this._g !== null) this._g.unexport();
        if (this._serIn !== null) this._serIn.unexport();
        if (this._srck !== null) this._srck.unexport();
    }
}

module.exports = { SiPoConnection };
