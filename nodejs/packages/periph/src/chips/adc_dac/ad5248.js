'use strict';

const _SD = 0x40;

/**
 * AD5248 dual 256-position I²C digital rheostat — minimal interface.
 *
 * Sets and reads back the wiper position (0–255) or the wiper-to-B resistance of
 * either channel. The chip has no register map: the instruction byte (channel select
 * bit A0 in bit 7, shutdown bit SD in bit 6) plays the role of the register address,
 * so the driver uses readReg/writeReg with the instruction byte.
 *
 * Default behaviour: RAB = 10 kΩ, RW = 160 Ω, both wipers at the power-up midscale
 * (0x80), both channels assumed active; the constructor performs no I²C traffic.
 */
class AD5248Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection pointing at the device (0x2C–0x2F).
     * @param {number} [rAb=10000] - End-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000).
     * @param {number} [rWiper=160] - Wiper resistance RW in ohms (typically 160 Ω at 5 V).
     */
    constructor(connection, rAb = 10000.0, rWiper = 160.0) {
        this._conn = connection;
        this._rAb = rAb;
        this._rWiper = rWiper;
        this._shutdown = [false, false];
    }

    _checkChannel(channel) {
        if (channel !== 0 && channel !== 1) {
            throw new RangeError('AD5248: channel must be 0 or 1');
        }
    }

    _instruction(channel) {
        return (channel << 7) | (this._shutdown[channel] ? _SD : 0);
    }

    /**
     * Set the wiper position of a channel.
     *
     * Clamps the code to [0, 255]. On a shut-down channel the RDAC is updated and the
     * channel stays in shutdown.
     * @param {number} channel - Channel index, 0 or 1.
     * @param {number} code - Wiper position 0–255 (0 = at B, 255 = at A).
     * @returns {Promise<void>}
     */
    async setWiper(channel, code) {
        this._checkChannel(channel);
        const c = Math.max(0, Math.min(255, Math.trunc(code)));
        await this._conn.writeReg(this._instruction(channel), [c]);
    }

    /**
     * Read the wiper position of a channel.
     *
     * Sends the channel's instruction byte alone, then reads one byte.
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {Promise<number>} Wiper position 0–255.
     */
    async getWiper(channel) {
        this._checkChannel(channel);
        return (await this._conn.readReg(this._instruction(channel), 1))[0];
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     * @param {number} channel - Channel index, 0 or 1.
     * @param {number} ohms - Target wiper-to-B resistance in ohms (clamped to the reachable range).
     * @returns {Promise<void>}
     */
    async setResistance(channel, ohms) {
        this._checkChannel(channel);
        const code = Math.round((ohms - 2 * this._rWiper) / this._rAb * 256);
        await this.setWiper(channel, code);
    }

    /**
     * Read the W–B resistance for the current wiper position.
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {Promise<number>} Resistance in ohms, D / 256 * RAB + 2 * RW.
     */
    async getResistance(channel) {
        const code = await this.getWiper(channel);
        return code / 256 * this._rAb + 2 * this._rWiper;
    }
}

/**
 * AD5248 full interface — extends AD5248Minimal with ratio, increment, shutdown and reset.
 */
class AD5248Full extends AD5248Minimal {
    /**
     * Write both wipers (two transactions).
     * @param {number} code1 - Channel 0 wiper position 0–255.
     * @param {number} code2 - Channel 1 wiper position 0–255.
     * @returns {Promise<void>}
     */
    async setBoth(code1, code2) {
        await this.setWiper(0, code1);
        await this.setWiper(1, code2);
    }

    /**
     * Set the wiper to a divider ratio of the full scale.
     *
     * Wiper code = round(ratio × 256) clamped to 255. Only meaningful if terminal A is
     * wired externally; the AD5248 does not pin out A, so prefer setResistance.
     * @param {number} channel - Channel index, 0 or 1.
     * @param {number} ratio - Ratio 0.0–1.0.
     * @returns {Promise<void>}
     */
    async setRatio(channel, ratio) {
        const r = Math.max(0, Math.min(1, ratio));
        await this.setWiper(channel, Math.min(255, Math.round(r * 256)));
    }

    /**
     * Read the wiper position as a ratio.
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {Promise<number>} code / 256.
     */
    async getRatio(channel) {
        return (await this.getWiper(channel)) / 256;
    }

    /**
     * Move the wiper by a number of steps.
     * @param {number} channel - Channel index, 0 or 1.
     * @param {number} [steps=1] - Steps to add (negative to decrement).
     * @returns {Promise<number>} New wiper code, clamped to 0–255.
     */
    async increment(channel, steps = 1) {
        const code = Math.max(0, Math.min(255, (await this.getWiper(channel)) + steps));
        await this.setWiper(channel, code);
        return code;
    }

    /**
     * Put a channel in shutdown (terminal A open, wiper shorted to B); RDAC is kept.
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {Promise<void>}
     */
    async shutdown(channel) {
        this._checkChannel(channel);
        const code = await this.getWiper(channel);
        this._shutdown[channel] = true;
        await this._conn.writeReg(this._instruction(channel), [code]);
    }

    /**
     * Leave shutdown; the RDAC contents are restored to the wiper.
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {Promise<void>}
     */
    async resume(channel) {
        this._checkChannel(channel);
        const code = await this.getWiper(channel);
        this._shutdown[channel] = false;
        await this._conn.writeReg(this._instruction(channel), [code]);
    }

    /**
     * Return the driver-tracked shutdown state (the chip cannot be read back).
     * @param {number} channel - Channel index, 0 or 1.
     * @returns {boolean} True if the channel was shut down by this driver.
     */
    isShutdown(channel) {
        this._checkChannel(channel);
        return this._shutdown[channel];
    }

    /**
     * Write midscale (0x80) to both channels with SD = 0 (power-up preset).
     * @returns {Promise<void>}
     */
    async reset() {
        this._shutdown = [false, false];
        await this.setWiper(0, 0x80);
        await this.setWiper(1, 0x80);
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     * @param {number} rAb - End-to-end resistance RAB in ohms.
     * @param {number} [rWiper] - Wiper resistance RW in ohms; unchanged if omitted.
     */
    setRab(rAb, rWiper) {
        this._rAb = rAb;
        if (rWiper !== undefined && rWiper !== null) this._rWiper = rWiper;
    }
}

module.exports = { AD5248Minimal, AD5248Full };
