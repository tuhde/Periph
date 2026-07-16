'use strict';

const BAND_BASE_KHZ = [87000, 76000, 76000, 65000];
const SPACE_KHZ = [100, 200, 50, 25];

const STC_TIMEOUT_MS = 500;
const STC_POLL_MS = 1;

const DHIZ = 0x8000;
const DMUTE = 0x4000;
const MONO = 0x2000;
const BASS = 0x1000;
const SEEKUP = 0x0200;
const SEEK = 0x0100;
const SKMODE = 0x0080;
const RDS_EN = 0x0008;
const NEW_METHOD = 0x0004;
const SOFT_RESET = 0x0002;
const ENABLE = 0x0001;

const TUNE = 0x0010;

const DE = 0x0800;
const SOFTMUTE_EN = 0x0200;
const AFCD = 0x0100;

const INT_MODE = 0x8000;

const BAND_65M_50M = 0x0200;

const RDSR = 0x8000;
const STC = 0x4000;
const SF = 0x2000;
const ST = 0x0400;

const FM_TRUE = 0x0100;
const FM_READY = 0x0080;

function sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

function freqToChan(band, space, eastEurope50m, frequencyMhz) {
    const base = (band === 3 && eastEurope50m) ? 50000 : BAND_BASE_KHZ[band];
    const freqKhz = Math.round(frequencyMhz * 1000);
    let chan = Math.round((freqKhz - base) / SPACE_KHZ[space]);
    if (chan < 0) chan = 0;
    if (chan > 1023) chan = 1023;
    return chan;
}

function chanToFreq(band, space, eastEurope50m, chan) {
    const base = (band === 3 && eastEurope50m) ? 50000 : BAND_BASE_KHZ[band];
    return (base + chan * SPACE_KHZ[space]) / 1000.0;
}

/**
 * RDA5807M single-chip FM stereo radio tuner — minimal interface.
 *
 * Tunes to a station, adjusts volume, mutes, and seeks the next station.
 * No configuration required beyond the transport.
 *
 * Unlike most chips in this project, the RDA5807M has no register-pointer
 * byte: writes always start at the fixed register 0x02 and reads always
 * start at the fixed register 0x0A. This driver keeps an in-memory shadow
 * of registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of
 * them on every change, since the chip cannot be told to start a write
 * anywhere else.
 */
class RDA5807MMinimal {
    static BAND_US_EUROPE = 0;
    static BAND_JAPAN = 1;
    static BAND_WORLD = 2;
    static BAND_EAST_EUROPE = 3;

    static SPACE_100K = 0;
    static SPACE_200K = 1;
    static SPACE_50K = 2;
    static SPACE_25K = 3;

    /**
     * @param {object} transport - I²C transport bound to address 0x10.
     * @param {number} [frequencyMhz=100.0] - Initial frequency in MHz.
     * @param {number} [volume=8] - Initial volume, 0 (mute) to 15 (max).
     */
    constructor(transport, frequencyMhz = 100.0, volume = 8) {
        this._transport = transport;
        this._band = RDA5807MMinimal.BAND_WORLD;
        this._space = RDA5807MMinimal.SPACE_100K;
        this._eastEurope50m = false;

        const ctrl = DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE;
        const chan = freqToChan(this._band, this._space, this._eastEurope50m, frequencyMhz);
        const chanReg = (chan << 6) | TUNE | (this._band << 2) | this._space;
        const r4 = SOFTMUTE_EN | DE;
        const r5 = INT_MODE | (8 << 8) | (volume & 0x0F);
        const r6 = 0x0000;
        const r7 = (16 << 10) | BAND_65M_50M | 0x0002;

        this._regs = [ctrl, chanReg, r4, r5, r6, r7];
        this._writeRegs();
        this._waitStc();
        this._regs[1] &= ~TUNE;
    }

    _writeRegs() {
        const buf = Buffer.alloc(12);
        for (let i = 0; i < 6; i++) buf.writeUInt16BE(this._regs[i] & 0xFFFF, i * 2);
        this._transport.write(buf);
    }

    _readStatus(n) {
        const buf = this._transport.read(n);
        const words = [];
        for (let i = 0; i < n / 2; i++) words.push(buf.readUInt16BE(i * 2));
        return words;
    }

    _waitStc() {
        let elapsed = 0;
        while (elapsed < STC_TIMEOUT_MS) {
            const [statusA] = this._readStatus(2);
            if (statusA & STC) return statusA;
            sleep(STC_POLL_MS);
            elapsed += STC_POLL_MS;
        }
        return 0;
    }

    /**
     * Tune to a frequency, blocking until the tune completes.
     * @param {number} frequencyMhz - Target frequency in MHz.
     */
    setFrequency(frequencyMhz) {
        const chan = freqToChan(this._band, this._space, this._eastEurope50m, frequencyMhz);
        this._regs[1] = (chan << 6) | TUNE | (this._band << 2) | this._space;
        this._writeRegs();
        this._waitStc();
        this._regs[1] &= ~TUNE;
    }

    /** @returns {number} Currently tuned frequency in MHz, derived from READCHAN. */
    frequency() {
        const [statusA] = this._readStatus(2);
        const readchan = statusA & 0x03FF;
        return chanToFreq(this._band, this._space, this._eastEurope50m, readchan);
    }

    /**
     * Set the output volume.
     * @param {number} level - Volume 0 (mute) to 15 (max), logarithmic scale.
     */
    setVolume(level) {
        this._regs[3] = (this._regs[3] & ~0x000F) | (level & 0x0F);
        this._writeRegs();
    }

    /**
     * Mute or unmute the audio output.
     * @param {boolean} enable - True to mute, false for normal operation.
     */
    mute(enable) {
        if (enable) this._regs[0] &= ~DMUTE;
        else this._regs[0] |= DMUTE;
        this._writeRegs();
    }

    /**
     * Seek to the next station, blocking until the seek completes.
     * @param {boolean} [up=true] - True to seek upward, false to seek downward.
     * @returns {number|null} New frequency in MHz, or null if the seek failed.
     */
    seek(up = true) {
        if (up) this._regs[0] |= SEEKUP;
        else this._regs[0] &= ~SEEKUP;
        this._regs[0] |= SEEK;
        this._writeRegs();
        const statusA = this._waitStc();
        this._regs[0] &= ~SEEK;
        this._writeRegs();

        if (statusA & SF) return null;
        const readchan = statusA & 0x03FF;
        return chanToFreq(this._band, this._space, this._eastEurope50m, readchan);
    }
}

/**
 * RDA5807M full interface — extends RDA5807MMinimal with band/spacing
 * configuration, RDS, status, and power management.
 */
class RDA5807MFull extends RDA5807MMinimal {
    /**
     * Reconfigure tuner-level settings. Only options that are provided are changed.
     * Changing band or space re-tunes to the current frequency.
     * @param {object} [options]
     * @param {number} [options.band] - BAND_US_EUROPE, BAND_JAPAN, BAND_WORLD, or BAND_EAST_EUROPE.
     * @param {number} [options.space] - SPACE_100K, SPACE_200K, SPACE_50K, or SPACE_25K.
     * @param {boolean} [options.deEmphasis] - True for 50 µs, false for 75 µs.
     * @param {number} [options.seekThreshold] - Seek SNR threshold, 0-15.
     * @param {boolean} [options.seekMode] - True to stop seeking at the band limit.
     * @param {number} [options.clkMode] - Reference clock select, 0-7.
     * @param {boolean} [options.afcDisable] - True to disable AFC.
     * @param {boolean} [options.eastEurope50m] - Sub-band select when band is BAND_EAST_EUROPE.
     */
    configure(options = {}) {
        const { band, space, deEmphasis, seekThreshold, seekMode, clkMode, afcDisable, eastEurope50m } = options;
        let retune = false;
        const currentFreq = this.frequency();

        if (band !== undefined && band !== this._band) {
            this._band = band;
            retune = true;
        }
        if (space !== undefined && space !== this._space) {
            this._space = space;
            retune = true;
        }
        if (eastEurope50m !== undefined && eastEurope50m !== this._eastEurope50m) {
            this._eastEurope50m = eastEurope50m;
            retune = true;
        }

        this._regs[1] = (this._regs[1] & ~0x000F) | (this._band << 2) | this._space;

        if (deEmphasis !== undefined) {
            if (deEmphasis) this._regs[2] |= DE;
            else this._regs[2] &= ~DE;
        }
        if (afcDisable !== undefined) {
            if (afcDisable) this._regs[2] |= AFCD;
            else this._regs[2] &= ~AFCD;
        }
        if (seekThreshold !== undefined) {
            this._regs[3] = (this._regs[3] & ~0x0F00) | ((seekThreshold & 0x0F) << 8);
        }
        if (seekMode !== undefined) {
            if (seekMode) this._regs[0] |= SKMODE;
            else this._regs[0] &= ~SKMODE;
        }
        if (clkMode !== undefined) {
            this._regs[0] = (this._regs[0] & ~0x0070) | ((clkMode & 0x07) << 4);
        }
        if (eastEurope50m !== undefined) {
            if (eastEurope50m) this._regs[5] &= ~BAND_65M_50M;
            else this._regs[5] |= BAND_65M_50M;
        }

        if (retune) this.setFrequency(currentFreq);
        else this._writeRegs();
    }

    /** @param {boolean} enable - True to enable bass boost. */
    setBassBoost(enable) {
        if (enable) this._regs[0] |= BASS;
        else this._regs[0] &= ~BASS;
        this._writeRegs();
    }

    /** @param {boolean} enable - True to force mono, false to allow stereo. */
    setMono(enable) {
        if (enable) this._regs[0] |= MONO;
        else this._regs[0] &= ~MONO;
        this._writeRegs();
    }

    /** @param {boolean} enable - True to enable soft mute (chip default). */
    setSoftmute(enable) {
        if (enable) this._regs[2] |= SOFTMUTE_EN;
        else this._regs[2] &= ~SOFTMUTE_EN;
        this._writeRegs();
    }

    /** @param {boolean} enable - True to enable RDS/RBDS. */
    enableRds(enable) {
        if (enable) this._regs[0] |= RDS_EN;
        else this._regs[0] &= ~RDS_EN;
        this._writeRegs();
    }

    /** @returns {boolean} True if a new RDS/RBDS group is available. */
    rdsReady() {
        const [statusA] = this._readStatus(2);
        return !!(statusA & RDSR);
    }

    /**
     * Read the four raw RDS/RBDS blocks, if a new group is ready. Does not
     * decode group content — the caller interprets the raw blocks.
     * @returns {number[]|null} [blockA, blockB, blockC, blockD], or null.
     */
    readRdsGroup() {
        const [statusA, , blockA, blockB, blockC, blockD] = this._readStatus(12);
        if (!(statusA & RDSR)) return null;
        return [blockA, blockB, blockC, blockD];
    }

    /** @returns {boolean} True if the current station is being received in stereo. */
    isStereo() {
        const [statusA] = this._readStatus(2);
        return !!(statusA & ST);
    }

    /** @returns {boolean} True if the current channel is a real station. */
    isStation() {
        const [, statusB] = this._readStatus(4);
        return !!(statusB & FM_TRUE);
    }

    /** @returns {boolean} True if the tuner is ready. */
    isReady() {
        const [, statusB] = this._readStatus(4);
        return !!(statusB & FM_READY);
    }

    /** @returns {number} Raw RSSI, 0 (weakest) to 127 (strongest), logarithmic. */
    signalStrength() {
        const [, statusB] = this._readStatus(4);
        return (statusB >> 9) & 0x7F;
    }

    /** @param {boolean} enable - True to power down, false to power up. */
    standby(enable) {
        if (enable) this._regs[0] &= ~ENABLE;
        else this._regs[0] |= ENABLE;
        this._writeRegs();
    }

    /**
     * Pulse the soft-reset bit, then re-apply the current configuration
     * (the chip's power-on defaults would otherwise replace it).
     */
    softReset() {
        this._regs[0] |= SOFT_RESET;
        this._writeRegs();
        this._regs[0] &= ~SOFT_RESET;
        this._writeRegs();
    }
}

module.exports = { RDA5807MMinimal, RDA5807MFull };
