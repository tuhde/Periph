'use strict';

const _REG_STATUS        = 0x00;
const _REG_MODE          = 0x01;
const _REG_RTP_INPUT     = 0x02;
const _REG_LIBRARY       = 0x03;
const _REG_SEQ1          = 0x04;
const _REG_GO            = 0x0C;
const _REG_ODT           = 0x0D;
const _REG_ATH_CTRL      = 0x11;
const _REG_RATED_VOLTAGE = 0x16;
const _REG_OD_CLAMP      = 0x17;
const _REG_A_CAL_COMP    = 0x18;
const _REG_FEEDBACK      = 0x1A;
const _REG_CONTROL1      = 0x1B;
const _REG_CONTROL2      = 0x1C;
const _REG_CONTROL3      = 0x1D;
const _REG_CONTROL4      = 0x1E;
const _REG_VBAT          = 0x21;
const _REG_LRA_PERIOD    = 0x22;

const _MODE_DEV_RESET = 0x80;
const _MODE_STANDBY   = 0x40;

const MODE_INTERNAL_TRIGGER = 0;
const MODE_EXTERNAL_EDGE    = 1;
const MODE_EXTERNAL_LEVEL   = 2;
const MODE_PWM_ANALOG       = 3;
const MODE_AUDIO_TO_VIBE    = 4;
const MODE_RTP              = 5;
const MODE_DIAGNOSTICS      = 6;
const MODE_AUTO_CALIBRATION = 7;

const EFFECTS = {
    EFFECT_STRONG_CLICK: 1,
    EFFECT_SHARP_CLICK: 4,
    EFFECT_SOFT_BUMP: 7,
    EFFECT_DOUBLE_CLICK: 10,
    EFFECT_TRIPLE_CLICK: 12,
    EFFECT_STRONG_BUZZ: 14,
    EFFECT_ALERT_750MS: 15,
    EFFECT_ALERT_1000MS: 16,
    EFFECT_BUZZ: 47,
    EFFECT_PULSING_STRONG: 52,
    EFFECT_LONG_BUZZ: 118,
};

const _SAMPLE_TIME_S = [150e-6, 200e-6, 250e-6, 300e-6];
const _PEAK_TIMES_MS = [10, 20, 30, 40];
const _FILTERS_HZ = [100, 125, 150, 200];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Round to the nearest integer and clamp to a register code 0–255. */
function _clampCode(value) {
    const code = Math.floor(value + 0.5);
    return Math.max(0, Math.min(255, code));
}

/** Drive-time register code for a time in ms (LRA: 0.5 ms + 0.1 ms × code; ERM: 1 ms + 0.2 ms × code). */
function _driveTimeCode(ms, lra) {
    const code = lra ? Math.floor((ms - 0.5) / 0.1 + 0.5) : Math.floor((ms - 1.0) / 0.2 + 0.5);
    return Math.max(0, Math.min(31, code));
}

/**
 * DRV2605 haptic driver for ERM and LRA actuators (Texas Instruments) — minimal interface.
 *
 * Plays the built-in 123-effect TS2200 ROM library. `init()` runs the
 * initialization sequence: DEV_RESET, DEVICE_ID check, leave standby, and
 * actuator selection (ERM: library A, open loop; LRA: LRA library, closed loop
 * with auto-resonance). The I²C address is fixed at 0x5A; the EN pin must be high.
 */
class DRV2605Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x5A).
     * @param {boolean} [lra=false] - false for an ERM, true for an LRA.
     */
    constructor(connection, lra = false) {
        this._conn = connection;
        this._lra = lra;
        this._mode = MODE_INTERNAL_TRIGGER;
        this._standby = false;
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg & 0xFF, value & 0xFF);
    }

    async _readReg(reg) {
        const buf = await this._conn.readReg(reg & 0xFF, 1);
        return buf[0];
    }

    async _updateReg(reg, mask, value) {
        const old = await this._readReg(reg);
        await this._writeReg(reg, (old & ~mask) | (value & mask));
    }

    async _writeMode(mode, standby = false) {
        await this._writeReg(_REG_MODE, (standby ? _MODE_STANDBY : 0) | mode);
        this._mode = mode;
        this._standby = standby;
    }

    async _resetChip() {
        await this._writeReg(_REG_MODE, _MODE_DEV_RESET);
        this._mode = MODE_INTERNAL_TRIGGER;
        this._standby = true;
        await sleep(2);
        for (let i = 0; i < 100; i++) {
            if (!((await this._readReg(_REG_MODE)) & _MODE_DEV_RESET)) return;
            await sleep(1);
        }
        throw new Error('DRV2605 reset did not complete');
    }

    async _ensureInternal() {
        if (this._mode !== MODE_INTERNAL_TRIGGER || this._standby) await this._writeMode(MODE_INTERNAL_TRIGGER);
    }

    /**
     * Run the initialization sequence (reset, DEVICE_ID check, leave standby, select actuator).
     * @returns {Promise<void>}
     * @throws {Error} If the reset does not complete or DEVICE_ID is not 3.
     */
    async init() {
        await this._resetChip();
        if (((await this._readReg(_REG_STATUS)) >> 5) !== 3) throw new Error('DRV2605 not found (DEVICE_ID != 3)');
        await this._writeMode(MODE_INTERNAL_TRIGGER);
        if (this._lra) {
            await this._writeReg(_REG_FEEDBACK, 0xB6);
            await this._writeReg(_REG_LIBRARY, 0x06);
        } else {
            await this._writeReg(_REG_FEEDBACK, 0x36);
            await this._writeReg(_REG_LIBRARY, 0x01);
        }
    }

    /**
     * Play one ROM effect and return immediately.
     * Writes the effect to sequencer slot 1 and 0 (end) to slot 2, then sets GO.
     * @param {number} effectId - Effect identifier 1–123 (see the `EFFECT_*` constants).
     * @returns {Promise<void>}
     * @throws {RangeError} If `effectId` is outside 1–123.
     */
    async playEffect(effectId) {
        if (!Number.isInteger(effectId) || effectId < 1 || effectId > 123) throw new RangeError('effectId must be 1-123');
        await this._ensureInternal();
        await this._conn.writeReg(_REG_SEQ1, Buffer.from([effectId, 0]));
        await this._writeReg(_REG_GO, 1);
    }

    /**
     * Cancel playback by clearing the GO bit.
     * @returns {Promise<void>}
     */
    async stop() {
        await this._writeReg(_REG_GO, 0);
    }

    /**
     * True while a sequence is playing (GO bit set).
     * @returns {Promise<boolean>}
     */
    async isPlaying() {
        return ((await this._readReg(_REG_GO)) & 0x01) !== 0;
    }
}

/**
 * DRV2605 full interface — extends Minimal with the complete chip functionality:
 * waveform sequencer, real-time playback, mode and library control, drive
 * configuration, auto-calibration, diagnostics, audio-to-vibe, status readout and
 * one-time OTP programming.
 */
class DRV2605Full extends DRV2605Minimal {
    /**
     * Play a sequence of up to 8 effects and delays.
     * @param {Array<number|Array>} items - Up to 8 steps: a number 1–123 is a ROM effect; `['wait', ms]` is a delay of 0–1270 ms (10 ms steps).
     * @returns {Promise<void>}
     * @throws {RangeError} On an empty or oversized list or an out-of-range step.
     */
    async playSequence(items) {
        if (!Array.isArray(items) || items.length < 1 || items.length > 8) throw new RangeError('items must hold 1-8 steps');
        const data = [];
        for (const item of items) {
            if (typeof item === 'number') {
                if (!Number.isInteger(item) || item < 1 || item > 123) throw new RangeError('effect must be 1-123');
                data.push(item);
            } else {
                if (!Array.isArray(item) || item[0] !== 'wait' || !(item[1] >= 0 && item[1] <= 1270)) throw new RangeError("wait step must be ['wait', 0-1270 ms]");
                data.push(0x80 | Math.floor(item[1] / 10 + 0.5));
            }
        }
        if (data.length < 8) data.push(0);
        await this._ensureInternal();
        await this._conn.writeReg(_REG_SEQ1, Buffer.from(data));
        await this._writeReg(_REG_GO, 1);
    }

    /**
     * Poll GO until playback finishes.
     * @param {number} [timeoutMs=5000] - Give-up time in ms.
     * @returns {Promise<boolean>} true if playback finished, false on timeout.
     */
    async waitDone(timeoutMs = 5000) {
        const start = Date.now();
        while (await this.isPlaying()) {
            if (Date.now() - start >= timeoutMs) return false;
            await sleep(2);
        }
        return true;
    }

    /**
     * Cancel playback; also leaves real-time-playback mode (output to zero).
     * @returns {Promise<void>}
     */
    async stop() {
        await super.stop();
        if (this._mode === MODE_RTP) {
            await this._writeReg(_REG_RTP_INPUT, 0);
            await this._writeMode(MODE_INTERNAL_TRIGGER, this._standby);
        }
    }

    /**
     * Select the ROM library: 0 empty, 1–5 ERM libraries A–E, 6 LRA library.
     * @param {number} library - 0–6.
     * @returns {Promise<void>}
     */
    async setLibrary(library) {
        if (!Number.isInteger(library) || library < 0 || library > 6) throw new RangeError('library must be 0-6');
        await this._updateReg(_REG_LIBRARY, 0x07, library);
    }

    /**
     * Set MODE<2:0> and leave standby.
     * @param {number} mode - 0–7.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        if (!Number.isInteger(mode) || mode < 0 || mode > 7) throw new RangeError('mode must be 0-7');
        await this._writeMode(mode);
    }

    /**
     * Enter software standby (also stops playback).
     * @returns {Promise<void>}
     */
    async standby() { await this._writeMode(this._mode, true); }

    /**
     * Leave software standby.
     * @returns {Promise<void>}
     */
    async wake() { await this._writeMode(this._mode, false); }

    /**
     * DEV_RESET and wait for completion; re-run `init()` afterwards.
     * @returns {Promise<void>}
     */
    async reset() { await this._resetChip(); }

    /**
     * Put the outputs in true high-impedance state.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setHiZ(enabled) { await this._updateReg(_REG_LIBRARY, 0x10, enabled ? 0x10 : 0); }

    async _isLra() { return ((await this._readReg(_REG_FEEDBACK)) & 0x80) !== 0; }

    async _isOpenLoop(lra) { return ((await this._readReg(_REG_CONTROL3)) & (lra ? 0x01 : 0x20)) !== 0; }

    /**
     * Select ERM (false) or LRA (true) in N_ERM_LRA; run `calibrate()` afterwards.
     * @param {boolean} lra
     * @returns {Promise<void>}
     */
    async setActuator(lra) { await this._updateReg(_REG_FEEDBACK, 0x80, lra ? 0x80 : 0); }

    /**
     * Enable open-loop drive (ERM_OPEN_LOOP or LRA_OPEN_LOOP according to the actuator type).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setOpenLoop(enabled) {
        const mask = (await this._isLra()) ? 0x01 : 0x20;
        await this._updateReg(_REG_CONTROL3, mask, enabled ? mask : 0);
    }

    /**
     * Select bidirectional (true) or unidirectional (false) input.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     * @throws {Error} If unidirectional is requested while the loop is open.
     */
    async setBidirectional(enabled) {
        if (!enabled && (await this._isOpenLoop(await this._isLra()))) throw new Error('unidirectional input requires closed-loop operation');
        await this._updateReg(_REG_CONTROL2, 0x80, enabled ? 0x80 : 0);
    }

    async _enterRtp() {
        if (this._mode !== MODE_RTP || this._standby) await this._writeMode(MODE_RTP);
    }

    /**
     * Stream a signed real-time-playback amplitude. Enters RTP mode if not already
     * there; leave with `stop()`, `setMode()` or `standby()`.
     * @param {number} level - Amplitude 0.0–1.0 (mapped to 0–127).
     * @returns {Promise<void>}
     */
    async rtpLevel(level) {
        if (!(level >= 0 && level <= 1)) throw new RangeError('level must be 0.0-1.0');
        if (this._mode !== MODE_RTP) await this._updateReg(_REG_CONTROL3, 0x08, 0);
        await this._enterRtp();
        await this._writeReg(_REG_RTP_INPUT, Math.floor(level * 127 + 0.5));
    }

    /**
     * Write a raw RTP_INPUT byte, interpreted per the RTP data format.
     * @param {number} value - 0–255.
     * @returns {Promise<void>}
     */
    async rtpWrite(value) {
        if (!Number.isInteger(value) || value < 0 || value > 255) throw new RangeError('value must be 0-255');
        await this._enterRtp();
        await this._writeReg(_REG_RTP_INPUT, value);
    }

    /**
     * Select unsigned (true) or signed (false) RTP data (DATA_FORMAT_RTP).
     * @param {boolean} unsigned
     * @returns {Promise<void>}
     */
    async setRtpFormat(unsigned) { await this._updateReg(_REG_CONTROL3, 0x08, unsigned ? 0x08 : 0); }

    /**
     * Select analog (true) or PWM (false) input for mode 3 (N_PWM_ANALOG).
     * @param {boolean} analog
     * @returns {Promise<void>}
     */
    async setInput(analog) { await this._updateReg(_REG_CONTROL3, 0x02, analog ? 0x02 : 0); }

    async _voltsToRatedCode(volts, lraFreqHz) {
        if (!(await this._isLra())) return _clampCode(volts / 21.33e-3);
        if (!(lraFreqHz > 0)) throw new RangeError('lraFreqHz is required to convert volts for an LRA');
        const tSample = _SAMPLE_TIME_S[((await this._readReg(_REG_CONTROL2)) >> 4) & 0x03];
        const factor = 1 - (4 * tSample + 300e-6) * lraFreqHz;
        if (factor <= 0) throw new RangeError('lraFreqHz too high for the sample time');
        return _clampCode(volts * Math.sqrt(factor) / 20.71e-3);
    }

    /**
     * Set RATED_VOLTAGE from volts or a raw code.
     * @param {{volts?: number, code?: number, lraFreqHz?: number}} opts - Exactly one of `volts` (V, RMS for an LRA) or `code` (0–255); `lraFreqHz` is required with `volts` for an LRA.
     * @returns {Promise<void>}
     */
    async setRatedVoltage({ volts, code, lraFreqHz } = {}) {
        if ((volts === undefined) === (code === undefined)) throw new RangeError('give exactly one of volts or code');
        if (code === undefined) code = await this._voltsToRatedCode(volts, lraFreqHz);
        else if (!Number.isInteger(code) || code < 0 || code > 255) throw new RangeError('code must be 0-255');
        await this._writeReg(_REG_RATED_VOLTAGE, code);
    }

    /**
     * Set OD_CLAMP from volts or a raw code.
     * @param {{volts?: number, code?: number, lraFreqHz?: number}} opts - Exactly one of `volts` or `code` (0–255); `lraFreqHz` is required with `volts` for LRA open loop.
     * @returns {Promise<void>}
     * @throws {Error} For ERM closed loop with `volts` (pass `code` instead).
     */
    async setOverdriveClamp({ volts, code, lraFreqHz } = {}) {
        if ((volts === undefined) === (code === undefined)) throw new RangeError('give exactly one of volts or code');
        if (code === undefined) {
            const lra = await this._isLra();
            const openLoop = await this._isOpenLoop(lra);
            if (lra && openLoop) {
                if (!(lraFreqHz > 0)) throw new RangeError('lraFreqHz is required for LRA open loop');
                code = _clampCode(volts / (21.33e-3 * Math.sqrt(1 - lraFreqHz * 800e-6)));
            } else if (!lra && !openLoop) {
                throw new Error('ERM closed-loop clamp in volts needs undocumented timing values; pass code');
            } else {
                code = _clampCode(volts / 21.96e-3);
            }
        } else if (!Number.isInteger(code) || code < 0 || code > 255) {
            throw new RangeError('code must be 0-255');
        }
        await this._writeReg(_REG_OD_CLAMP, code);
    }

    /**
     * Set DRIVE_TIME (LRA: half the resonance period; ERM: back-EMF sample period).
     * @param {number} ms - Time in ms.
     * @returns {Promise<void>}
     */
    async setDriveTime(ms) { await this._updateReg(_REG_CONTROL1, 0x1F, _driveTimeCode(ms, await this._isLra())); }

    /**
     * Set the FEEDBACK_CONTROL fields (actuator-type bit preserved).
     * @param {number} [brakeFactor=3] - FB_BRAKE_FACTOR 0–7.
     * @param {number} [loopGain=1] - LOOP_GAIN 0–3.
     * @param {number} [bemfGain=2] - BEMF_GAIN 0–3.
     * @returns {Promise<void>}
     */
    async setFeedback(brakeFactor = 3, loopGain = 1, bemfGain = 2) {
        if (!(brakeFactor >= 0 && brakeFactor <= 7 && loopGain >= 0 && loopGain <= 3 && bemfGain >= 0 && bemfGain <= 3)) throw new RangeError('brakeFactor 0-7, loopGain 0-3, bemfGain 0-3');
        await this._updateReg(_REG_FEEDBACK, 0x7F, (brakeFactor << 4) | (loopGain << 2) | bemfGain);
    }

    /**
     * Set the CONTROL1–CONTROL3 fields not covered by other setters.
     * @param {{startupBoost?: boolean, brakeStabilizer?: boolean, sampleTime?: number, blankingTime?: number, idissTime?: number, lraDriveTwice?: boolean, supplyComp?: boolean, noiseGate?: number}} [opts]
     * @returns {Promise<void>}
     */
    async setAdvanced({ startupBoost = true, brakeStabilizer = true, sampleTime = 3, blankingTime = 1, idissTime = 1, lraDriveTwice = false, supplyComp = true, noiseGate = 2 } = {}) {
        for (const v of [sampleTime, blankingTime, idissTime, noiseGate]) {
            if (!Number.isInteger(v) || v < 0 || v > 3) throw new RangeError('sampleTime, blankingTime, idissTime, noiseGate must be 0-3');
        }
        await this._updateReg(_REG_CONTROL1, 0x80, startupBoost ? 0x80 : 0);
        await this._updateReg(_REG_CONTROL2, 0x7F, (brakeStabilizer ? 0x40 : 0) | (sampleTime << 4) | (blankingTime << 2) | idissTime);
        await this._updateReg(_REG_CONTROL3, 0xD4, (noiseGate << 6) | (supplyComp ? 0 : 0x10) | (lraDriveTwice ? 0x04 : 0));
    }

    /**
     * Set the ODT, SPT, SNT, BRT offsets (5 ms steps, signed 8-bit).
     * @param {{overdriveMs?: number, sustainPosMs?: number, sustainNegMs?: number, brakeMs?: number}} [opts]
     * @returns {Promise<void>}
     */
    async setTimeOffsets({ overdriveMs = 0, sustainPosMs = 0, sustainNegMs = 0, brakeMs = 0 } = {}) {
        const data = [];
        for (const ms of [overdriveMs, sustainPosMs, sustainNegMs, brakeMs]) {
            const steps = ms >= 0 ? Math.floor(ms / 5 + 0.5) : -Math.floor(-ms / 5 + 0.5);
            if (steps < -128 || steps > 127) throw new RangeError('time offset out of range (-640..635 ms)');
            data.push(steps & 0xFF);
        }
        await this._conn.writeReg(_REG_ODT, Buffer.from(data));
    }

    /**
     * Run the auto-calibration procedure.
     * @param {boolean} lra - true for an LRA, false for an ERM.
     * @param {number} ratedVoltage - Rated voltage in V (RMS for an LRA).
     * @param {number} overdriveVoltage - Overdrive clamp in V (ERM: open-loop conversion V / 21.96 mV, since the closed-loop correction needs undocumented timing values).
     * @param {number} [lraFreqHz] - LRA resonance in Hz (required for an LRA); also sets DRIVE_TIME to half the period.
     * @returns {Promise<{ok: boolean, comp: number, bemf: number, bemfGain: number}>}
     */
    async calibrate(lra, ratedVoltage, overdriveVoltage, lraFreqHz) {
        if (lra && !(lraFreqHz > 0)) throw new RangeError('lraFreqHz is required for an LRA');
        const wasStandby = this._standby;
        await this._writeMode(MODE_AUTO_CALIBRATION);
        const bemfGain = (await this._readReg(_REG_FEEDBACK)) & 0x03;
        await this._writeReg(_REG_FEEDBACK, (lra ? 0x80 : 0) | (2 << 4) | (2 << 2) | bemfGain);
        await this._updateReg(_REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1); // SAMPLE_TIME 3, BLANKING 1, IDISS 1
        if (lra) await this._updateReg(_REG_CONTROL1, 0x1F, _driveTimeCode(500 / lraFreqHz, true));
        await this._writeReg(_REG_RATED_VOLTAGE, await this._voltsToRatedCode(ratedVoltage, lraFreqHz));
        await this._writeReg(_REG_OD_CLAMP, _clampCode(overdriveVoltage / 21.96e-3));
        await this._updateReg(_REG_CONTROL4, 0x31, 3 << 4); // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        await this._writeReg(_REG_GO, 1);
        const finished = await this.waitDone(3000);
        const diagFailed = ((await this._readReg(_REG_STATUS)) & 0x08) !== 0;
        const result = await this.getCalibration();
        await this._writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
        return { ok: finished && !diagFailed, ...result };
    }

    /**
     * Read the stored calibration.
     * @returns {Promise<{comp: number, bemf: number, bemfGain: number}>}
     */
    async getCalibration() {
        const data = await this._conn.readReg(_REG_A_CAL_COMP, 2);
        return { comp: data[0], bemf: data[1], bemfGain: (await this._readReg(_REG_FEEDBACK)) & 0x03 };
    }

    /**
     * Restore A_CAL_COMP, A_CAL_BEMF and BEMF_GAIN from an earlier calibration.
     * @param {number} comp - 0–255.
     * @param {number} bemf - 0–255.
     * @param {number} bemfGain - 0–3.
     * @returns {Promise<void>}
     */
    async setCalibration(comp, bemf, bemfGain) {
        if (!(comp >= 0 && comp <= 255 && bemf >= 0 && bemf <= 255 && bemfGain >= 0 && bemfGain <= 3)) throw new RangeError('comp/bemf 0-255, bemfGain 0-3');
        await this._conn.writeReg(_REG_A_CAL_COMP, Buffer.from([comp, bemf]));
        await this._updateReg(_REG_FEEDBACK, 0x03, bemfGain);
    }

    /**
     * Run the actuator diagnostics.
     * @returns {Promise<boolean>} true if the actuator is OK (DIAG_RESULT = 0).
     */
    async diagnose() {
        const wasStandby = this._standby;
        await this._writeMode(MODE_DIAGNOSTICS);
        await this._writeReg(_REG_GO, 1);
        const finished = await this.waitDone(3000);
        const diagFailed = ((await this._readReg(_REG_STATUS)) & 0x08) !== 0;
        await this._writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
        return finished && !diagFailed;
    }

    /**
     * Configure the audio-to-vibe engine and enter audio-to-vibe mode.
     * @param {{acCouple?: boolean, peakTimeMs?: number, filterHz?: number, minInputV?: number, maxInputV?: number, minDrive?: number, maxDrive?: number}} [opts] - peakTimeMs one of 10/20/30/40; filterHz one of 100/125/150/200; input levels 0–1.8 V; drive 0.0–1.0.
     * @returns {Promise<void>}
     */
    async setAudioToVibe({ acCouple = true, peakTimeMs = 20, filterHz = 125, minInputV = 0.1, maxInputV = 1.8, minDrive = 0.1, maxDrive = 1.0 } = {}) {
        const peak = _PEAK_TIMES_MS.indexOf(peakTimeMs);
        const filter = _FILTERS_HZ.indexOf(filterHz);
        if (peak < 0 || filter < 0) throw new RangeError('peakTimeMs in 10/20/30/40, filterHz in 100/125/150/200');
        if (!(minInputV >= 0 && minInputV <= 1.8 && maxInputV >= 0 && maxInputV <= 1.8 && minDrive >= 0 && minDrive <= 1 && maxDrive >= 0 && maxDrive <= 1)) throw new RangeError('input levels 0-1.8 V, drive 0.0-1.0');
        await this._conn.writeReg(_REG_ATH_CTRL, Buffer.from([
            (peak << 2) | filter,
            _clampCode(minInputV * 255 / 1.8), _clampCode(maxInputV * 255 / 1.8),
            _clampCode(minDrive * 255), _clampCode(maxDrive * 255),
        ]));
        await this._updateReg(_REG_CONTROL1, 0x20, acCouple ? 0x20 : 0);
        await this._updateReg(_REG_CONTROL3, 0x02, 0x02);
        await this._writeMode(MODE_AUDIO_TO_VIBE);
    }

    /**
     * Read STATUS (clears the latched flags).
     * @returns {Promise<{diagFailed: boolean, fbTimeout: boolean, overTemp: boolean, overCurrent: boolean}>}
     */
    async readStatus() {
        const s = await this._readReg(_REG_STATUS);
        return { diagFailed: (s & 0x08) !== 0, fbTimeout: (s & 0x04) !== 0, overTemp: (s & 0x02) !== 0, overCurrent: (s & 0x01) !== 0 };
    }

    /**
     * DEVICE_ID (3 for the DRV2605); clears the latched status flags.
     * @returns {Promise<number>}
     */
    async readDeviceId() { return (await this._readReg(_REG_STATUS)) >> 5; }

    /**
     * VDD in volts from VBAT; valid only during playback.
     * @returns {Promise<number>}
     */
    async readSupplyVoltage() { return (await this._readReg(_REG_VBAT)) * 5.6 / 255; }

    /**
     * LRA resonance frequency in Hz; valid only during LRA playback.
     * @returns {Promise<number|null>} null if LRA_PERIOD is 0.
     */
    async readLraFrequency() {
        const raw = await this._readReg(_REG_LRA_PERIOD);
        return raw === 0 ? null : 1e6 / (raw * 98.46);
    }

    /**
     * Burn registers 0x16–0x1A into OTP. **Irreversible, one time only**; VDD must be 4.0–4.4 V.
     * @param {boolean} [confirm=false] - Must be true to proceed.
     * @returns {Promise<void>}
     * @throws {Error} If not confirmed, already programmed, or programming does not complete.
     */
    async programOtp(confirm = false) {
        if (confirm !== true) throw new Error('programOtp is irreversible; pass confirm=true');
        if (await this.otpProgrammed()) throw new Error('OTP already programmed');
        await this._updateReg(_REG_CONTROL4, 0x01, 0x01);
        for (let i = 0; i < 100; i++) {
            await sleep(10);
            if (await this.otpProgrammed()) return;
        }
        throw new Error('OTP programming did not complete');
    }

    /**
     * OTP_STATUS: true if the OTP has already been programmed.
     * @returns {Promise<boolean>}
     */
    async otpProgrammed() { return ((await this._readReg(_REG_CONTROL4)) & 0x04) !== 0; }
}

for (const cls of [DRV2605Minimal, DRV2605Full]) {
    cls.I2C_ADDRESS = 0x5A;
    cls.DEVICE_ID = 3;
    Object.assign(cls, EFFECTS);
    cls.MODE_INTERNAL_TRIGGER = MODE_INTERNAL_TRIGGER;
    cls.MODE_EXTERNAL_EDGE = MODE_EXTERNAL_EDGE;
    cls.MODE_EXTERNAL_LEVEL = MODE_EXTERNAL_LEVEL;
    cls.MODE_PWM_ANALOG = MODE_PWM_ANALOG;
    cls.MODE_AUDIO_TO_VIBE = MODE_AUDIO_TO_VIBE;
    cls.MODE_RTP = MODE_RTP;
    cls.MODE_DIAGNOSTICS = MODE_DIAGNOSTICS;
    cls.MODE_AUTO_CALIBRATION = MODE_AUTO_CALIBRATION;
}

module.exports = { DRV2605Minimal, DRV2605Full, I2C_ADDRESS: 0x5A, ...EFFECTS };
