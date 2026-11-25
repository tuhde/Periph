'use strict';

const _REG_COMMAND = 0xFD;
const _PAGE_FUNCTION = 0x0B;
const _REG_CTRL = 0x00;
const _REG_BLINK = 0x12;
const _REG_PWM = 0x24;

const _FN_CONFIG = 0x00;
const _FN_AUTOPLAY1 = 0x02;
const _FN_DISPLAY = 0x05;
const _FN_AUDIO_SYNC = 0x06;
const _FN_FRAME_STATE = 0x07;
const _FN_BREATH1 = 0x08;
const _FN_SHUTDOWN = 0x0A;
const _FN_AGC = 0x0B;
const _FN_AUDIO_RATE = 0x0C;

function _check(value, name, max) {
    if (!Number.isInteger(value) || value < 0 || value > max) throw new RangeError(`${name} must be in 0..${max}`);
}

function _checkPixel(row, col) {
    _check(row, 'row', 8);
    _check(col, 'col', 15);
}

/**
 * IS31FL3731 144-LED matrix driver (CharliePlex) — minimal interface.
 *
 * Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture mode, drawing into frame 1.
 * The constructor starts the initialization sequence (software shutdown while configuring, Picture
 * mode showing frame 1, frame 1 initialised with all LED-control bits on and PWM/blink cleared, then
 * normal operation); every bus method waits for it and `ready` exposes it. LED current is set by
 * R_EXT (3.2 mA at 20 kOhm). The driver keeps a shadow copy of every frame because the chip only
 * allows frame register reads in software shutdown. Bus calls are serialised internally.
 */
class IS31FL3731Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x74-0x77).
     */
    constructor(connection) {
        this._conn = connection;
        this._page = null;
        this._frame = 0;
        this._pwm = Array.from({ length: 8 }, () => Buffer.alloc(144));
        this._ctrl = Array.from({ length: 8 }, () => Buffer.alloc(18, 0xFF));
        this._blink = Array.from({ length: 8 }, () => Buffer.alloc(18));
        this._ic = 0;
        this._be = 0;
        this._bpt = 0;
        this._chain = Promise.resolve();
        this._ready = this._exec(async () => {
            await this._funcWrite(_FN_SHUTDOWN, 0x00);
            await this._funcWrite(_FN_CONFIG, Buffer.from([0x00, 0x00]));
            await this._funcWrite(_FN_DISPLAY, 0x00);
            await this._initFrame(0);
            await this._funcWrite(_FN_SHUTDOWN, 0x01);
        });
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    _exec(fn) {
        const p = this._chain.then(fn);
        this._chain = p.catch(() => {});
        return p;
    }

    async _select(page) {
        if (this._page !== page) {
            await this._conn.writeReg(_REG_COMMAND, page);
            this._page = page;
        }
    }

    async _funcWrite(reg, data) {
        await this._select(_PAGE_FUNCTION);
        await this._conn.writeReg(reg, data);
    }

    async _funcRead(reg) {
        await this._select(_PAGE_FUNCTION);
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _initFrame(frame) {
        this._ctrl[frame].fill(0xFF);
        this._blink[frame].fill(0);
        this._pwm[frame].fill(0);
        await this._select(frame);
        await this._conn.writeReg(_REG_CTRL, Buffer.from(this._ctrl[frame]));
        await this._conn.writeReg(_REG_BLINK, Buffer.alloc(18));
        await this._conn.writeReg(_REG_PWM, Buffer.alloc(144));
    }

    /**
     * Set the PWM brightness of one LED in the frame being drawn.
     * @param {number} row - Common line, 0-8.
     * @param {number} col - Sink line, 0-15.
     * @param {number} brightness - PWM value, 0-255.
     * @returns {Promise<void>}
     */
    setPixel(row, col, brightness) {
        _checkPixel(row, col);
        _check(brightness, 'brightness', 255);
        return this._exec(async () => {
            this._pwm[this._frame][16 * row + col] = brightness;
            await this._select(this._frame);
            await this._conn.writeReg(_REG_PWM + 16 * row + col, brightness);
        });
    }

    /** Set all 144 PWM values of the frame being drawn to 0 (one block write). @returns {Promise<void>} */
    clear() { return this.fill(0); }

    /**
     * Set all 144 PWM values of the frame being drawn (one block write).
     * @param {number} brightness - PWM value, 0-255.
     * @returns {Promise<void>}
     */
    fill(brightness) {
        _check(brightness, 'brightness', 255);
        return this._exec(async () => {
            this._pwm[this._frame].fill(brightness);
            await this._select(this._frame);
            await this._conn.writeReg(_REG_PWM, Buffer.alloc(144, brightness));
        });
    }
}

/**
 * IS31FL3731 full interface — frames, auto-play, blink, breath, audio, shutdown, INTB.
 */
class IS31FL3731Full extends IS31FL3731Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x74-0x77).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(true); };
    }

    /**
     * Read the PWM value of one LED from the shadow copy of the frame being drawn.
     * @param {number} row - 0-8.
     * @param {number} col - 0-15.
     * @returns {number} PWM value, 0-255.
     */
    getPixel(row, col) {
        _checkPixel(row, col);
        return this._pwm[this._frame][16 * row + col];
    }

    /**
     * Write a whole frame of PWM values (one block write).
     * @param {Buffer|number[]} data - 144 PWM values, row-major (row * 16 + col).
     * @returns {Promise<void>}
     */
    setPixels(data) {
        const buf = Buffer.from(data);
        if (buf.length !== 144) throw new RangeError('data must be 144 bytes');
        return this._exec(async () => {
            buf.copy(this._pwm[this._frame]);
            await this._select(this._frame);
            await this._conn.writeReg(_REG_PWM, buf);
        });
    }

    _setBit(cache, base, row, col, on) {
        _checkPixel(row, col);
        return this._exec(async () => {
            const idx = 2 * row + (col >> 3);
            const mask = 1 << (col & 7);
            const buf = cache[this._frame];
            if (on) buf[idx] |= mask; else buf[idx] &= ~mask & 0xFF;
            await this._select(this._frame);
            await this._conn.writeReg(base + idx, buf[idx]);
        });
    }

    /**
     * Set the LED-control (on/off) bit of one LED in the frame being drawn.
     * @param {number} row - 0-8.
     * @param {number} col - 0-15.
     * @param {boolean} on - true to enable the LED.
     * @returns {Promise<void>}
     */
    setLedEnabled(row, col, on) { return this._setBit(this._ctrl, _REG_CTRL, row, col, on); }

    /**
     * Set the blink-enable bit of one LED in the frame being drawn.
     * @param {number} row - 0-8.
     * @param {number} col - 0-15.
     * @param {boolean} on - true to let the LED blink (needs enableBlink()).
     * @returns {Promise<void>}
     */
    setBlink(row, col, on) { return this._setBit(this._blink, _REG_BLINK, row, col, on); }

    /**
     * Set all 18 blink-control bytes of the frame being drawn.
     * @param {boolean} on - true to enable blinking of every LED.
     * @returns {Promise<void>}
     */
    setBlinkAll(on) {
        return this._exec(async () => {
            this._blink[this._frame].fill(on ? 0xFF : 0x00);
            await this._select(this._frame);
            await this._conn.writeReg(_REG_BLINK, Buffer.from(this._blink[this._frame]));
        });
    }

    _writeDisplay() {
        return this._funcWrite(_FN_DISPLAY, (this._ic << 5) | (this._be << 3) | this._bpt);
    }

    /**
     * Enable or disable blinking (50 % duty).
     * @param {boolean} enabled - true to enable.
     * @param {number} [period=1] - Blink period in units of 0.27 s, 0-7.
     * @returns {Promise<void>}
     */
    enableBlink(enabled, period = 1) {
        _check(period, 'period', 7);
        return this._exec(async () => {
            this._be = enabled ? 1 : 0;
            this._bpt = period;
            await this._writeDisplay();
        });
    }

    /**
     * Select the frame subsequent drawing calls write to (does not change the display).
     * @param {number} frame - 0-7 (frame 1-8).
     * @returns {Promise<void>}
     */
    selectFrame(frame) {
        _check(frame, 'frame', 7);
        return this._exec(async () => {
            this._frame = frame;
            await this._select(frame);
        });
    }

    /**
     * Show a frame in Picture mode.
     * @param {number} frame - 0-7 (frame 1-8).
     * @returns {Promise<void>}
     */
    showFrame(frame) {
        _check(frame, 'frame', 7);
        return this._exec(() => this._funcWrite(_FN_CONFIG, Buffer.from([0x00, frame])));
    }

    /**
     * Initialise a frame: LED-control 0xFF, PWM and blink 0.
     * @param {number} frame - 0-7.
     * @returns {Promise<void>}
     */
    clearFrame(frame) {
        _check(frame, 'frame', 7);
        return this._exec(() => this._initFrame(frame));
    }

    /** Initialise all eight frames. @returns {Promise<void>} */
    clearAllFrames() {
        return this._exec(async () => {
            for (let f = 0; f < 8; f++) await this._initFrame(f);
        });
    }

    /**
     * Copy the PWM values of one frame to another.
     * @param {number} src - Source frame, 0-7.
     * @param {number} dst - Destination frame, 0-7.
     * @returns {Promise<void>}
     */
    copyFrame(src, dst) {
        _check(src, 'src', 7);
        _check(dst, 'dst', 7);
        return this._exec(async () => {
            this._pwm[src].copy(this._pwm[dst]);
            await this._select(dst);
            await this._conn.writeReg(_REG_PWM, Buffer.from(this._pwm[dst]));
        });
    }

    /** Switch to Picture mode (shows the frame selected by showFrame). @returns {Promise<void>} */
    setPictureMode() { return this._exec(() => this._funcWrite(_FN_CONFIG, 0x00)); }

    /**
     * Start Auto Frame Play.
     * @param {number} [frames=0] - Number of frames played, 1-7, or 0 for all eight.
     * @param {number} [loops=0] - Number of loops, 1-7, or 0 for endless.
     * @param {number} [delayMs=121] - Frame delay in ms (rounded to a multiple of 11 ms, 11-704).
     * @param {number} [startFrame=0] - First frame, 0-7.
     * @returns {Promise<void>}
     */
    startAutoPlay(frames = 0, loops = 0, delayMs = 121, startFrame = 0) {
        _check(frames, 'frames', 7);
        _check(loops, 'loops', 7);
        _check(startFrame, 'startFrame', 7);
        const a = Math.floor(delayMs / 11 + 0.5);
        if (!(a >= 1 && a <= 64)) throw new RangeError('delayMs must be in 11..704');
        return this._exec(async () => {
            await this._funcWrite(_FN_AUTOPLAY1, Buffer.from([(loops << 4) | frames, a & 0x3F]));
            await this._funcWrite(_FN_CONFIG, 0x08 | startFrame);
        });
    }

    /**
     * Stop Auto Frame Play and keep showing the current frame (Picture mode).
     * Reading the current frame clears the movie-finished flag.
     * @returns {Promise<void>}
     */
    stopAutoPlay() {
        return this._exec(async () => {
            const frame = (await this._funcRead(_FN_FRAME_STATE)) & 0x07;
            await this._funcWrite(_FN_CONFIG, Buffer.from([0x00, frame]));
        });
    }

    /**
     * Switch Audio Frame Play on, or back to Picture mode.
     * @param {boolean} enabled - true for Audio Frame Play.
     * @returns {Promise<void>}
     */
    setAudioMode(enabled) { return this._exec(() => this._funcWrite(_FN_CONFIG, enabled ? 0x10 : 0x00)); }

    /**
     * Let the audio input modulate the matrix intensity.
     * @param {boolean} enabled - true to enable.
     * @returns {Promise<void>}
     */
    setAudioSync(enabled) { return this._exec(() => this._funcWrite(_FN_AUDIO_SYNC, enabled ? 1 : 0)); }

    /**
     * Configure the audio gain.
     * @param {number} gainDb - 0, 3, ... 21 dB.
     * @param {boolean} [agc=false] - true to enable automatic gain control.
     * @param {boolean} [fast=false] - true for fast AGC, false for slow.
     * @returns {Promise<void>}
     */
    setAudioGain(gainDb, agc = false, fast = false) {
        if (!Number.isInteger(gainDb) || gainDb < 0 || gainDb > 21 || gainDb % 3) {
            throw new RangeError('gainDb must be a multiple of 3 in 0..21');
        }
        return this._exec(() => this._funcWrite(_FN_AGC, ((fast ? 1 : 0) << 4) | ((agc ? 1 : 0) << 3) | (gainDb / 3)));
    }

    /**
     * Set the audio ADC sample interval (46 us x a, 0 = 46 us x 256).
     * @param {number} a - 0-255.
     * @returns {Promise<void>}
     */
    setAudioRate(a) {
        _check(a, 'a', 255);
        return this._exec(() => this._funcWrite(_FN_AUDIO_RATE, a));
    }

    /**
     * Configure the breath (fade) function.
     * @param {boolean} enabled - true to enable.
     * @param {number} [fadeIn=0] - Fade-in exponent, 0-7 (26 ms x 2^n).
     * @param {number} [fadeOut=0] - Fade-out exponent, 0-7 (26 ms x 2^n).
     * @param {number} [extinguish=0] - Extinguish exponent, 0-7 (3.5 ms x 2^n).
     * @returns {Promise<void>}
     */
    setBreath(enabled, fadeIn = 0, fadeOut = 0, extinguish = 0) {
        _check(fadeIn, 'fadeIn', 7);
        _check(fadeOut, 'fadeOut', 7);
        _check(extinguish, 'extinguish', 7);
        return this._exec(() => this._funcWrite(_FN_BREATH1,
            Buffer.from([(fadeOut << 4) | fadeIn, ((enabled ? 1 : 0) << 4) | extinguish])));
    }

    /**
     * Choose whether frame 1's PWM values apply to all frames.
     * @param {boolean} useFrame1 - true to use frame 1's intensity for every frame.
     * @returns {Promise<void>}
     */
    setIntensityControl(useFrame1) {
        return this._exec(async () => {
            this._ic = useFrame1 ? 1 : 0;
            await this._writeDisplay();
        });
    }

    /**
     * Enter or leave software shutdown (frame registers are kept).
     * @param {boolean} [on=true] - true for shutdown, false for normal operation.
     * @returns {Promise<void>}
     */
    shutdown(on = true) { return this._exec(() => this._funcWrite(_FN_SHUTDOWN, on ? 0 : 1)); }

    /** Read FRAME_STATE (reading clears the movie-finished flag). @returns {Promise<number>} Bit 4 = movie finished, bits 2:0 = current frame. */
    frameState() { return this._exec(() => this._funcRead(_FN_FRAME_STATE)); }

    /** Return the frame being displayed, 0-7. @returns {Promise<number>} */
    async currentFrame() { return (await this.frameState()) & 0x07; }

    /** Return true if an Auto Frame Play movie has finished (clears the flag). @returns {Promise<boolean>} */
    async movieFinished() { return ((await this.frameState()) & 0x10) !== 0; }

    async _withRestore(fn) {
        const prev = this._page;
        const result = await fn();
        if (prev !== null && prev !== this._page) await this._select(prev);
        return result;
    }

    /**
     * Read a function-page register and restore the selected page.
     * @param {number} reg - Register address, 0-0x0C.
     * @returns {Promise<number>}
     */
    readFunctionRegister(reg) {
        _check(reg, 'reg', 0x0C);
        return this._exec(() => this._withRestore(() => this._funcRead(reg)));
    }

    /**
     * Write a function-page register and restore the selected page.
     * @param {number} reg - Register address, 0-0x0C.
     * @param {number} value - Byte to write.
     * @returns {Promise<void>}
     */
    writeFunctionRegister(reg, value) {
        _check(reg, 'reg', 0x0C);
        _check(value, 'value', 255);
        return this._exec(() => this._withRestore(() => this._funcWrite(reg, value)));
    }

    /**
     * Read FRAME_STATE (clears INT) and restore the selected page.
     * @returns {Promise<number>} FRAME_STATE (INT = 0x10, current frame in bits 2:0).
     */
    pollInterrupt() {
        return this._exec(() => this._withRestore(() => this._funcRead(_FN_FRAME_STATE)));
    }

    /**
     * Subscribe to the end of an Auto Frame Play movie. Delivery is via the INTB line
     * (options.intPin or connection.intPin, falling edge) or a 50 ms polling timer.
     * @param {function} callback - Called with (status) = FRAME_STATE.
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const intPin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(false); }, 50);
        }
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service(always) {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if ((always || (status & 0x10)) && this._callback) this._callback(status);
        } catch (e) {
            // bus error while polling; the next tick retries
        } finally {
            this._polling = false;
        }
    }
}

IS31FL3731Full.GAMMA_32 = Buffer.from([0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
    78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255]);
IS31FL3731Full.GAMMA_64 = Buffer.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
    24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
    77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
    158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255]);
IS31FL3731Minimal.ROWS = 9;
IS31FL3731Minimal.COLS = 16;
IS31FL3731Minimal.ADDRESS = 0x74;

module.exports = { IS31FL3731Minimal, IS31FL3731Full };
