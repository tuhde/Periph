'use strict';

/**
 * CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic).
 *
 * Configured entirely through the I²C control port (7-bit address 0x4A with AD0 = DGND,
 * 0x4B with AD0 = VL; 100 kHz maximum). Audio data travels over a separate I²S / PCM port
 * that this driver does not touch: the application supplies MCLK and the stream, calls
 * `start()` once the clocks run and `stop()` before they go away. Registers are accessed
 * through `readReg` / `writeReg` with a one-byte address and written one at a time.
 *
 * Order matters: configuration is written while POWER_CTL_1 is 0x01 (powered down), then the
 * required initialization block (registers 0x00, 0x47, 0x32) runs, and only `start()` writes 0x9E.
 */

const I2C_ADDRESS = 0x4A;

const REG_ID = 0x01;
const REG_POWER_CTL_1 = 0x02;
const REG_POWER_CTL_2 = 0x04;
const REG_CLOCKING_CTL = 0x05;
const REG_INTERFACE_CTL_1 = 0x06;
const REG_INTERFACE_CTL_2 = 0x07;
const REG_PASSTHRU_A_SEL = 0x08;
const REG_PASSTHRU_B_SEL = 0x09;
const REG_ANALOG_ZC_SR = 0x0A;
const REG_PLAYBACK_CTL_1 = 0x0D;
const REG_MISC_CTL = 0x0E;
const REG_PLAYBACK_CTL_2 = 0x0F;
const REG_PASSTHRU_A_VOL = 0x14;
const REG_PASSTHRU_B_VOL = 0x15;
const REG_PCM_A_VOL = 0x1A;
const REG_PCM_B_VOL = 0x1B;
const REG_BEEP_FREQ_ON = 0x1C;
const REG_BEEP_VOL_OFF = 0x1D;
const REG_BEEP_TONE_CFG = 0x1E;
const REG_TONE_CTL = 0x1F;
const REG_MASTER_A_VOL = 0x20;
const REG_MASTER_B_VOL = 0x21;
const REG_HP_A_VOL = 0x22;
const REG_HP_B_VOL = 0x23;
const REG_SPK_A_VOL = 0x24;
const REG_SPK_B_VOL = 0x25;
const REG_PCM_MIXER_SWAP = 0x26;
const REG_LIMIT_CTL_1 = 0x27;
const REG_LIMIT_CTL_2 = 0x28;
const REG_LIMIT_ATTACK = 0x29;
const REG_STATUS = 0x2E;
const REG_BATTERY_COMP = 0x2F;
const REG_VP_LEVEL = 0x30;
const REG_SPEAKER_STATUS = 0x31;
const REG_CHARGE_PUMP_FREQ = 0x34;
const REG_INIT_00 = 0x00;
const REG_INIT_32 = 0x32;
const REG_INIT_47 = 0x47;

const READABLE = new Set([
    0x00, 0x01, 0x02, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F,
    0x14, 0x15, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
    0x26, 0x27, 0x28, 0x29, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x34, 0x47]);
const READ_ONLY = new Set([0x01, 0x2E, 0x30, 0x31]);

const HP_GAINS = [0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430];
const LIMIT_DB = [0, -3, -6, -9, -12, -18, -24, -30];

const FS_ROUND = [8000, 12000, 16000, 24000, 32000, 48000, 96000];
const FS_44K = [11025, 22050, 44100, 88200];
const FS_FRAC = [12e6 / 1088, 12e6 / 544, 12e6 / 272, 12e6 / 136];
const FS_27M = [8000, 12000, 16000, 24000, 32000, 48000];

/** Output power modes for `setOutputs`. */
const OUT = { FOLLOW_LOW: 0, FOLLOW_HIGH: 1, ON: 2, OFF: 3 };
/** Serial port formats for `setInterface`. */
const FMT = { LJ: 0, I2S: 1, RJ: 2 };
/** Beep modes for `setBeep`. */
const BEEP = { OFF: 0, SINGLE: 1, MULTIPLE: 2, CONTINUOUS: 3 };
/** PCM channel sources for `setPcmSwap`. */
const SRC = { NORMAL: 0, MIX: 1, SWAPPED: 3 };
/** Headphone gain indices for `setHeadphoneGain`. */
const GAIN = { G_0_3959: 0, G_0_4571: 1, G_0_5111: 2, G_0_6047: 3, G_0_7099: 4, G_0_8399: 5, G_1_000: 6, G_1_143: 7 };

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const _roundHalfUp = (x) => Math.floor(x + 0.5);
const _clamp = (x, lo, hi) => (x < lo ? lo : x > hi ? hi : x);
const _halfDbCode = (db, lo, hi) => _roundHalfUp(_clamp(db, lo, hi) * 2) & 0xFF;
const _signed = (code) => (code > 127 ? code - 256 : code);
const _limitedVolumeCode = (db) => (db < -96 ? 0x01 : _roundHalfUp(_clamp(db, -96, 0) * 2) & 0xFF);
const _near = (v, c) => Math.abs(v - c) <= c * 1e-4;
const _nearAny = (v, list) => list.some((c) => _near(v, c));

function _nearestLimit(db) {
    let best = 0;
    let bestErr = Math.abs(db - LIMIT_DB[0]);
    for (let i = 1; i < 8; i++) {
        const err = Math.abs(db - LIMIT_DB[i]);
        if (err < bestErr) { best = i; bestErr = err; }
    }
    return best;
}

/**
 * Datasheet Table 1 clocking settings for an MCLK / sample-rate pair.
 *
 * @param {number} mclkHz - 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz, in Hz.
 * @param {number} fsHz - Sample rate in Hz (a rate listed for that MCLK in Table 1).
 * @returns {{auto: boolean, speed: number, group32k: boolean, videoClk: boolean, ratio: number, mclkDiv2: boolean}}
 *     The arguments of `setClocking` with `auto` false.
 * @throws {RangeError} If the combination is not in the datasheet table.
 */
function clockingFor(mclkHz, fsHz) {
    let video = false;
    let div2 = false;
    let ratio = -1;
    if (_nearAny(mclkHz, [12288000, 18432000])) {
        if (_nearAny(fsHz, FS_ROUND)) ratio = 0;
    } else if (_near(mclkHz, 11289600)) {
        if (_nearAny(fsHz, FS_44K)) ratio = 0;
    } else if (_near(mclkHz, 16934400)) {
        if (_nearAny(fsHz, FS_44K)) ratio = 0;
        else if (_near(fsHz, 16934400 / 2112)) ratio = 2;
    } else if (_nearAny(mclkHz, [12000000, 24000000])) {
        div2 = mclkHz > 18000000;
        if (_nearAny(fsHz, FS_ROUND)) ratio = 1;
        else if (_nearAny(fsHz, FS_FRAC)) ratio = 3;
    } else if (_near(mclkHz, 27000000)) {
        video = true;
        if (_nearAny(fsHz, FS_27M)) ratio = 1;
        else if (_nearAny(fsHz, FS_FRAC.slice(0, 3))) ratio = 3;
    }
    if (ratio < 0) throw new RangeError('MCLK / sample-rate combination is not in the datasheet table');
    const group32k = _nearAny(fsHz, [8000, 16000, 32000]);
    const speed = fsHz <= 12100 ? 3 : fsHz <= 24100 ? 2 : fsHz <= 48100 ? 1 : 0;
    return { auto: false, speed, group32k, videoClk: video, ratio, mclkDiv2: div2 };
}

/**
 * CS43L22 stereo DAC — minimal interface.
 *
 * Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
 * amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB,
 * digital soft ramp on. `init()` resets the chip (when a RESET pin is given), checks the ID,
 * loads these defaults and runs the required initialization; the chip stays powered down until
 * `start()` is called with MCLK running. The constructor makes no bus access.
 */
class CS43L22Minimal {
    /**
     * @param {object} connection - I2C connection with `readReg` / `writeReg` (address 0x4A / 0x4B).
     * @param {object} [resPin] - Optional OutputPin (async `set(high)`) wired to the active-low RESET pin.
     */
    constructor(connection, resPin = null) {
        this._conn = connection;
        this._resPin = resPin;
    }

    async _read(reg) { return (await this._conn.readReg(reg, 1))[0]; }

    async _write(reg, value) { await this._conn.writeReg(reg, value & 0xFF); }

    async _update(reg, mask, value) {
        await this._write(reg, ((await this._read(reg)) & ~mask & 0xFF) | (value & mask));
    }

    async _setBit(reg, bit, on) { await this._update(reg, 1 << bit, on ? 1 << bit : 0); }

    async _initChip() {
        if (this._resPin) {
            await this._resPin.set(false);
            await _sleep(2);
            await this._resPin.set(true);
            await _sleep(2);
        }
        if (((await this._read(REG_ID)) & 0xF8) !== 0xE0) throw new Error('CS43L22 not found: chip ID mismatch');
        await this._write(REG_POWER_CTL_1, 0x01);
        await this._write(REG_POWER_CTL_2, 0xAF);
        await this._write(REG_CLOCKING_CTL, 0xA0);
        await this._write(REG_INTERFACE_CTL_1, 0x04);
        await this._write(REG_PLAYBACK_CTL_1, 0x60);
        await this._write(REG_MASTER_A_VOL, 0x00);
        await this._write(REG_MASTER_B_VOL, 0x00);
        // Required initialization settings (datasheet §4.11).
        await this._write(REG_INIT_00, 0x99);
        await this._write(REG_INIT_47, 0x80);
        const v = await this._read(REG_INIT_32);
        await this._write(REG_INIT_32, v | 0x80);
        await this._write(REG_INIT_32, v & 0x7F);
        await this._write(REG_INIT_00, 0x00);
    }

    /**
     * Reset (when a RESET pin is given), verify the chip ID, load the defaults and run the
     * required initialization. The chip stays powered down.
     * @returns {Promise<void>}
     * @throws {Error} If the chip ID register does not read 0xE0 in bits 7:3.
     */
    async init() { await this._initChip(); }

    /**
     * Power the chip up (POWER_CTL_1 = 0x9E). Call after MCLK, SCLK and LRCK are running.
     * @returns {Promise<void>}
     */
    async start() { await this._write(REG_POWER_CTL_1, 0x9E); }

    /**
     * Power-down sequence: mute, disable soft ramp / zero-cross, POWER_CTL_1 = 0x9F, wait 100 µs.
     * MCLK may be removed after the promise resolves.
     * @returns {Promise<void>}
     */
    async stop() {
        await this._update(REG_PLAYBACK_CTL_2, 0xF0, 0xF0);
        await this._update(REG_MISC_CTL, 0x03, 0x00);
        await this._update(REG_ANALOG_ZC_SR, 0x0F, 0x00);
        await this._write(REG_POWER_CTL_1, 0x9F);
        await _sleep(1);
    }

    /**
     * Set the master volume of both channels.
     * @param {number} db - −102 … +12 dB, nearest 0.5 dB step (clamped).
     * @returns {Promise<void>}
     */
    async setVolume(db) {
        const code = _halfDbCode(db, -102, 12);
        await this._write(REG_MASTER_A_VOL, code);
        await this._write(REG_MASTER_B_VOL, code);
    }

    /**
     * Master volume of channel A.
     * @returns {Promise<number>} dB.
     */
    async getVolume() { return _signed(await this._read(REG_MASTER_A_VOL)) / 2; }

    /**
     * Mute or unmute both headphone channels.
     * @param {boolean} [on=true] - true to mute.
     * @returns {Promise<void>}
     */
    async mute(on = true) { await this._update(REG_PLAYBACK_CTL_2, 0xC0, on ? 0xC0 : 0); }
}

/**
 * CS43L22 stereo DAC — full interface.
 *
 * Adds per-channel volumes, output power selection, clocking and serial interface configuration,
 * tone control, beep generator, peak limiter, soft ramp / zero-cross, analog passthrough, battery
 * compensation, status reads and raw register access.
 */
class CS43L22Full extends CS43L22Minimal {
    /**
     * Read the chip ID and revision.
     * @returns {Promise<{chipId: number, revision: number}>} chipId is 0x1C for the CS43L22.
     */
    async readId() {
        const v = await this._read(REG_ID);
        return { chipId: v >> 3, revision: v & 7 };
    }

    /**
     * Pulse RESET and re-run the Minimal initialization.
     * @returns {Promise<void>}
     * @throws {Error} If no RESET pin was given.
     */
    async reset() {
        if (!this._resPin) throw new Error('reset requires a resPin');
        await this._initChip();
    }

    /**
     * Select the power mode of the headphone and speaker amplifiers.
     * @param {number} headphone - `OUT.FOLLOW_LOW` / `FOLLOW_HIGH` / `ON` / `OFF` (0–3).
     * @param {number} speaker - Same coding.
     * @returns {Promise<void>}
     */
    async setOutputs(headphone, speaker) {
        if (!(headphone >= 0 && headphone <= 3 && speaker >= 0 && speaker <= 3)) throw new RangeError('output mode must be 0-3');
        await this._write(REG_POWER_CTL_2, (headphone << 6) | (headphone << 4) | (speaker << 2) | speaker);
    }

    /**
     * Independent master volume of channel A (left) and B (right).
     * @param {number} leftDb - −102 … +12 dB.
     * @param {number} rightDb - −102 … +12 dB.
     * @returns {Promise<void>}
     */
    async setVolumeChannels(leftDb, rightDb) {
        await this._write(REG_MASTER_A_VOL, _halfDbCode(leftDb, -102, 12));
        await this._write(REG_MASTER_B_VOL, _halfDbCode(rightDb, -102, 12));
    }

    /**
     * Headphone volume.
     * @param {number} leftDb - 0 … −96 dB; below −96 dB the channel is muted (code 0x01).
     * @param {number} [rightDb] - Right channel; defaults to `leftDb`.
     * @returns {Promise<void>}
     */
    async setHeadphoneVolume(leftDb, rightDb = leftDb) {
        await this._write(REG_HP_A_VOL, _limitedVolumeCode(leftDb));
        await this._write(REG_HP_B_VOL, _limitedVolumeCode(rightDb));
    }

    /**
     * Speaker volume.
     * @param {number} leftDb - 0 … −96 dB; below −96 dB the channel is muted.
     * @param {number} [rightDb] - Right channel; defaults to `leftDb`.
     * @returns {Promise<void>}
     */
    async setSpeakerVolume(leftDb, rightDb = leftDb) {
        await this._write(REG_SPK_A_VOL, _limitedVolumeCode(leftDb));
        await this._write(REG_SPK_B_VOL, _limitedVolumeCode(rightDb));
    }

    /**
     * PCM mixer volume (the mute bits are kept).
     * @param {number} leftDb - −51.5 … +12 dB.
     * @param {number} [rightDb] - Right channel; defaults to `leftDb`.
     * @returns {Promise<void>}
     */
    async setPcmVolume(leftDb, rightDb = leftDb) {
        await this._update(REG_PCM_A_VOL, 0x7F, _halfDbCode(leftDb, -51.5, 12));
        await this._update(REG_PCM_B_VOL, 0x7F, _halfDbCode(rightDb, -51.5, 12));
    }

    /**
     * Mute or unmute the PCM channels.
     * @param {boolean} left - true to mute channel A.
     * @param {boolean} [right] - Channel B; defaults to `left`.
     * @returns {Promise<void>}
     */
    async mutePcm(left, right = left) {
        await this._setBit(REG_PCM_A_VOL, 7, left);
        await this._setBit(REG_PCM_B_VOL, 7, right);
    }

    /**
     * Select the headphone / line analog gain.
     * @param {number} index - 0–7 (`GAIN.G_0_3959` … `GAIN.G_1_143`).
     * @returns {Promise<void>}
     */
    async setHeadphoneGain(index) {
        if (!(index >= 0 && index <= 7)) throw new RangeError('gain index must be 0-7');
        await this._update(REG_PLAYBACK_CTL_1, 0xE0, index << 5);
    }

    /**
     * Headphone analog gain multiplier.
     * @returns {Promise<number>} 0.3959 … 1.143.
     */
    async headphoneGain() { return HP_GAINS[(await this._read(REG_PLAYBACK_CTL_1)) >> 5]; }

    /**
     * Mute or unmute the headphone and speaker outputs (both channels).
     * @param {boolean|null} [headphone] - true mute, false unmute, null/undefined keep.
     * @param {boolean|null} [speaker] - Same for the speaker.
     * @returns {Promise<void>}
     */
    async muteOutputs(headphone = null, speaker = null) {
        if (headphone !== null && headphone !== undefined) await this._update(REG_PLAYBACK_CTL_2, 0xC0, headphone ? 0xC0 : 0);
        if (speaker !== null && speaker !== undefined) await this._update(REG_PLAYBACK_CTL_2, 0x30, speaker ? 0x30 : 0);
    }

    /**
     * Configure the speaker channels.
     * @param {boolean} mono - Mix both channels to mono.
     * @param {boolean} swap - Swap the speaker channels.
     * @param {boolean} [mute5050=false] - Mute as an exact 50 % duty cycle.
     * @returns {Promise<void>}
     */
    async setSpeakerMode(mono, swap, mute5050 = false) {
        await this._update(REG_PLAYBACK_CTL_2, 0x07, (mono ? 2 : 0) | (swap ? 4 : 0) | (mute5050 ? 1 : 0));
    }

    /**
     * Write CLOCKING_CTL.
     * @param {object} [o]
     * @param {boolean} [o.auto=true] - Auto-detect the speed mode (slave).
     * @param {number} [o.speed=1] - Speed mode 0–3.
     * @param {boolean} [o.group32k=false] - 8, 16 or 32 kHz sample rates.
     * @param {boolean} [o.videoClk=false] - MCLK is 27 MHz.
     * @param {number} [o.ratio=0] - Internal ratio code 0–3 (128, 125, 132, 136).
     * @param {boolean} [o.mclkDiv2=false] - Divide MCLK by 2.
     * @returns {Promise<void>}
     */
    async setClocking({ auto = true, speed = 1, group32k = false, videoClk = false, ratio = 0, mclkDiv2 = false } = {}) {
        if (!(speed >= 0 && speed <= 3 && ratio >= 0 && ratio <= 3)) throw new RangeError('speed and ratio must be 0-3');
        await this._write(REG_CLOCKING_CTL,
            (auto ? 0x80 : 0) | (speed << 5) | (group32k ? 0x10 : 0) | (videoClk ? 0x08 : 0) | (ratio << 1) | (mclkDiv2 ? 1 : 0));
    }

    /**
     * Configure the serial audio port (INTERFACE_CTL_1 / INTERFACE_CTL_2).
     * @param {object} [o]
     * @param {boolean} [o.master=false] - Master mode.
     * @param {number} [o.fmt=FMT.I2S] - `FMT.LJ`, `FMT.I2S` or `FMT.RJ`.
     * @param {number} [o.wordLength=0] - AWL code 0–3.
     * @param {boolean} [o.dsp=false] - DSP mode (requires `FMT.LJ`).
     * @param {boolean} [o.invertSclk=false] - Invert SCLK polarity.
     * @param {boolean} [o.sclkEqMclk=false] - SCLK = MCLK.
     * @param {boolean} [o.invertSwitch=false] - Invert the SPKR/HP_SW sense.
     * @returns {Promise<void>}
     */
    async setInterface({ master = false, fmt = FMT.I2S, wordLength = 0, dsp = false, invertSclk = false,
        sclkEqMclk = false, invertSwitch = false } = {}) {
        if (!(fmt >= 0 && fmt <= 2 && wordLength >= 0 && wordLength <= 3)) throw new RangeError('fmt must be 0-2 and wordLength 0-3');
        if (dsp && fmt !== FMT.LJ) throw new RangeError('DSP mode requires FMT.LJ');
        await this._write(REG_INTERFACE_CTL_1,
            (master ? 0x80 : 0) | (invertSclk ? 0x40 : 0) | (dsp ? 0x10 : 0) | (fmt << 2) | wordLength);
        await this._write(REG_INTERFACE_CTL_2, (sclkEqMclk ? 0x40 : 0) | (invertSwitch ? 0x08 : 0));
    }

    /**
     * Treble / bass shelving filters.
     * @param {number} trebleDb - −10.5 … +12 dB (nearest 1.5 dB step).
     * @param {number} bassDb - −10.5 … +12 dB.
     * @param {number} [trebleCorner=0] - 0–3 (5 / 7 / 10 / 15 kHz).
     * @param {number} [bassCorner=0] - 0–3 (50 / 100 / 200 / 250 Hz).
     * @param {boolean} [enabled=true] - Enable the tone control.
     * @returns {Promise<void>}
     */
    async setTone(trebleDb, bassDb, trebleCorner = 0, bassCorner = 0, enabled = true) {
        if (!(trebleCorner >= 0 && trebleCorner <= 3 && bassCorner >= 0 && bassCorner <= 3)) throw new RangeError('corner must be 0-3');
        const treb = _clamp(8 - _roundHalfUp(trebleDb / 1.5), 0, 15);
        const bass = _clamp(8 - _roundHalfUp(bassDb / 1.5), 0, 15);
        await this._write(REG_TONE_CTL, (treb << 4) | bass);
        await this._update(REG_BEEP_TONE_CFG, 0x1F, (trebleCorner << 3) | (bassCorner << 1) | (enabled ? 1 : 0));
    }

    /**
     * Program and start the beep generator (timing first, mode last). `BEEP.OFF` only stops it.
     * @param {number} frequency - Code 0–15 (260.87 … 2181.82 Hz).
     * @param {number} onTime - Code 0–15.
     * @param {number} offTime - Code 0–7.
     * @param {number} volumeDb - +6 … −56 dB in 2 dB steps (nearest).
     * @param {number} mode - `BEEP.OFF` / `SINGLE` / `MULTIPLE` / `CONTINUOUS`.
     * @param {boolean} [mix=true] - Mix the beep with the PCM stream.
     * @returns {Promise<void>}
     * @throws {Error} If a beep is active and `mode` is not `BEEP.OFF`.
     */
    async setBeep(frequency, onTime, offTime, volumeDb, mode, mix = true) {
        if (!(frequency >= 0 && frequency <= 15 && onTime >= 0 && onTime <= 15 && offTime >= 0 && offTime <= 7 && mode >= 0 && mode <= 3)) {
            throw new RangeError('beep argument out of range');
        }
        if (mode === BEEP.OFF) { await this.stopBeep(); return; }
        if (((await this._read(REG_BEEP_TONE_CFG)) & 0xC0) !== 0) throw new Error('beep active: call stopBeep() before changing the timing');
        const n = _clamp(_roundHalfUp((6 - volumeDb) / 2), 0, 31);
        await this._write(REG_BEEP_FREQ_ON, (frequency << 4) | onTime);
        await this._write(REG_BEEP_VOL_OFF, (offTime << 5) | ((6 - n) & 0x1F));
        await this._update(REG_BEEP_TONE_CFG, 0xE0, (mode << 6) | (mix ? 0 : 0x20));
    }

    /**
     * Stop the beep generator (BEEP[1:0] = 0).
     * @returns {Promise<void>}
     */
    async stopBeep() { await this._update(REG_BEEP_TONE_CFG, 0xC0, 0); }

    /**
     * Configure the peak limiter.
     * @param {boolean} enabled - Enable the limiter.
     * @param {object} [o]
     * @param {number} [o.maxDb=0] - Maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB.
     * @param {number} [o.cushionDb=0] - Cushion threshold, same steps.
     * @param {number} [o.release=0x3F] - Release rate 0 (fastest) … 63.
     * @param {number} [o.attack=0] - Attack rate 0 … 63.
     * @param {boolean} [o.limitAll=true] - Limit both channels together.
     * @param {boolean} [o.softRampDisable=false] - Disable soft ramp while limiting.
     * @param {boolean} [o.zeroCrossDisable=false] - Disable zero-cross while limiting.
     * @returns {Promise<void>}
     */
    async setLimiter(enabled, { maxDb = 0, cushionDb = 0, release = 0x3F, attack = 0, limitAll = true,
        softRampDisable = false, zeroCrossDisable = false } = {}) {
        if (!(release >= 0 && release <= 63 && attack >= 0 && attack <= 63)) throw new RangeError('release and attack must be 0-63');
        await this._write(REG_LIMIT_CTL_1,
            (_nearestLimit(maxDb) << 5) | (_nearestLimit(cushionDb) << 2) | (softRampDisable ? 2 : 0) | (zeroCrossDisable ? 1 : 0));
        await this._write(REG_LIMIT_CTL_2, (enabled ? 0x80 : 0) | (limitAll ? 0x40 : 0) | release);
        await this._write(REG_LIMIT_ATTACK, attack);
    }

    /**
     * Soft-ramp volume transitions.
     * @param {boolean} digital - Digital volume (DIGSFT).
     * @param {boolean} [analogLeft=false] - Analog channel A (ANLGSFTA).
     * @param {boolean} [analogRight=false] - Analog channel B (ANLGSFTB).
     * @returns {Promise<void>}
     */
    async setSoftRamp(digital, analogLeft = false, analogRight = false) {
        await this._setBit(REG_MISC_CTL, 1, digital);
        await this._update(REG_ANALOG_ZC_SR, 0x0A, (analogLeft ? 0x02 : 0) | (analogRight ? 0x08 : 0));
    }

    /**
     * Zero-cross volume transitions.
     * @param {boolean} digital - Digital volume (DIGZC).
     * @param {boolean} [analogLeft=false] - Analog channel A (ANLGZCA).
     * @param {boolean} [analogRight=false] - Analog channel B (ANLGZCB).
     * @returns {Promise<void>}
     */
    async setZeroCross(digital, analogLeft = false, analogRight = false) {
        await this._setBit(REG_MISC_CTL, 0, digital);
        await this._update(REG_ANALOG_ZC_SR, 0x05, (analogLeft ? 0x01 : 0) | (analogRight ? 0x04 : 0));
    }

    /**
     * Digital de-emphasis filter.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setDeemphasis(enabled) { await this._setBit(REG_MISC_CTL, 2, enabled); }

    /**
     * Source of each PCM channel (PCM_MIXER_SWAP).
     * @param {number} leftSource - `SRC.NORMAL` (0), `SRC.MIX` (1) or `SRC.SWAPPED` (3).
     * @param {number} rightSource - Same coding.
     * @returns {Promise<void>}
     */
    async setPcmSwap(leftSource, rightSource) {
        if (!(leftSource >= 0 && leftSource <= 3 && rightSource >= 0 && rightSource <= 3)) throw new RangeError('source must be 0-3');
        await this._write(REG_PCM_MIXER_SWAP, (leftSource << 6) | (rightSource << 4));
    }

    /**
     * Invert the PCM polarity of each channel.
     * @param {boolean} left
     * @param {boolean} right
     * @returns {Promise<void>}
     */
    async invertPcm(left, right) { await this._update(REG_PLAYBACK_CTL_1, 0x0C, (left ? 0x04 : 0) | (right ? 0x08 : 0)); }

    /**
     * Route the analog line inputs to the headphone outputs.
     * @param {boolean} enabled - Enable passthrough on both channels.
     * @param {number} [leftInput=1] - AIN1A … AIN4A (1–4).
     * @param {number} [rightInput=1] - AIN1B … AIN4B (1–4).
     * @returns {Promise<void>}
     */
    async setPassthrough(enabled, leftInput = 1, rightInput = 1) {
        if (!(leftInput >= 1 && leftInput <= 4 && rightInput >= 1 && rightInput <= 4)) throw new RangeError('input must be 1-4');
        await this._write(REG_PASSTHRU_A_SEL, 0x80 | (1 << (leftInput - 1)));
        await this._write(REG_PASSTHRU_B_SEL, 0x80 | (1 << (rightInput - 1)));
        await this._update(REG_MISC_CTL, 0xC0, enabled ? 0xC0 : 0);
    }

    /**
     * Passthrough volume.
     * @param {number} leftDb - −60 … +12 dB.
     * @param {number} [rightDb] - Right channel; defaults to `leftDb`.
     * @returns {Promise<void>}
     */
    async setPassthroughVolume(leftDb, rightDb = leftDb) {
        await this._write(REG_PASSTHRU_A_VOL, _halfDbCode(leftDb, -60, 12));
        await this._write(REG_PASSTHRU_B_VOL, _halfDbCode(rightDb, -60, 12));
    }

    /**
     * Mute the passthrough channels.
     * @param {boolean} left
     * @param {boolean} [right] - Defaults to `left`.
     * @returns {Promise<void>}
     */
    async mutePassthrough(left, right = left) {
        await this._setBit(REG_MISC_CTL, 4, left);
        await this._setBit(REG_MISC_CTL, 5, right);
    }

    /**
     * Hold (true) or apply (false) register changes (FREEZE).
     * @param {boolean} on
     * @returns {Promise<void>}
     */
    async freeze(on) { await this._setBit(REG_MISC_CTL, 3, on); }

    /**
     * Battery compensation for the speaker supply VP.
     * @param {boolean} enabled
     * @param {number} [vpRefIndex=0] - VPREF code 0–15.
     * @param {boolean} [monitor=false] - Enable the VP monitor.
     * @returns {Promise<void>}
     */
    async setBatteryCompensation(enabled, vpRefIndex = 0, monitor = false) {
        if (!(vpRefIndex >= 0 && vpRefIndex <= 15)) throw new RangeError('vpRefIndex must be 0-15');
        await this._write(REG_BATTERY_COMP, (enabled ? 0x80 : 0) | (monitor ? 0x40 : 0) | vpRefIndex);
    }

    /**
     * VP supply voltage.
     * @param {number} vaVolts - The analog supply VA (1.8 or 2.5).
     * @returns {Promise<number>} Volts: VP_LEVEL × VA / 63.3.
     */
    async readVpVoltage(vaVolts) { return ((await this._read(REG_VP_LEVEL)) * vaVolts) / 63.3; }

    /**
     * Read the status register — reading clears the flags.
     * @returns {Promise<{clockError: boolean, dspAOverflow: boolean, dspBOverflow: boolean, pcmAOverflow: boolean, pcmBOverflow: boolean}>}
     */
    async readStatus() {
        const v = await this._read(REG_STATUS);
        return {
            clockError: !!(v & 0x40), dspAOverflow: !!(v & 0x20), dspBOverflow: !!(v & 0x10),
            pcmAOverflow: !!(v & 0x08), pcmBOverflow: !!(v & 0x04),
        };
    }

    /**
     * Read the speaker status register.
     * @returns {Promise<{speakerAShort: boolean, speakerBShort: boolean, spkrHpPin: boolean}>}
     */
    async readSpeakerStatus() {
        const v = await this._read(REG_SPEAKER_STATUS);
        return { speakerAShort: !!(v & 0x20), speakerBShort: !!(v & 0x10), spkrHpPin: !!(v & 0x08) };
    }

    /**
     * Charge-pump frequency divider (frequency = 64·Fs / (N + 2)).
     * @param {number} n - 0–15.
     * @returns {Promise<void>}
     */
    async setChargePumpFrequency(n) {
        if (!(n >= 0 && n <= 15)) throw new RangeError('n must be 0-15');
        await this._update(REG_CHARGE_PUMP_FREQ, 0xF0, n << 4);
    }

    /**
     * Read any documented register (reading STATUS clears it).
     * @param {number} reg
     * @returns {Promise<number>}
     */
    async readRegister(reg) {
        if (!READABLE.has(reg)) throw new RangeError('register is reserved');
        return this._read(reg);
    }

    /**
     * Write any documented writable register.
     * @param {number} reg
     * @param {number} value
     * @returns {Promise<void>}
     */
    async writeRegister(reg, value) {
        if (!READABLE.has(reg) || READ_ONLY.has(reg)) throw new RangeError('register is reserved or read-only');
        await this._write(reg, value);
    }
}

CS43L22Full.clockingFor = clockingFor;

module.exports = { CS43L22Minimal, CS43L22Full, clockingFor, I2C_ADDRESS, OUT, FMT, BEEP, SRC, GAIN };
