'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { IS31FL3731Minimal, IS31FL3731Full } = require('../../packages/periph/src/chips/led/is31fl3731');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const b = (...bytes) => Buffer.from(bytes);
const fill = (n, v) => Buffer.alloc(n, v);
const cat = (...parts) => Buffer.concat(parts.map((p) => (Buffer.isBuffer(p) ? p : Buffer.from(p))));

async function throwsRange(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

async function main() {
    const c = new I2CConnectionMock();
    const d = new IS31FL3731Full(c);
    await d.ready;
    const w = c.writes;
    const last = () => c.writes[c.writes.length - 1];
    const eq = (i, buf) => w[i] && w[i].equals(buf);

    checkTrue('init_sequence', w.length === 10 && eq(0, b(0xFD, 0x0B)) && eq(1, b(0x0A, 0x00)) && eq(2, b(0x00, 0x00, 0x00))
        && eq(3, b(0x05, 0x00)) && eq(4, b(0xFD, 0x00)) && eq(5, cat([0x00], fill(18, 0xFF)))
        && eq(6, cat([0x12], fill(18, 0))) && eq(7, cat([0x24], fill(144, 0))) && eq(8, b(0xFD, 0x0B)) && eq(9, b(0x0A, 0x01)));

    w.length = 0;
    await d.setPixel(2, 5, 200);
    checkTrue('set_pixel_page_and_pwm', w.length === 2 && eq(0, b(0xFD, 0x00)) && eq(1, b(0x24 + 37, 200)));
    w.length = 0;
    await d.setPixel(8, 15, 1);
    checkTrue('set_pixel_cached_page', w.length === 1 && eq(0, b(0x24 + 143, 1)));
    checkTrue('get_pixel', d.getPixel(2, 5) === 200 && d.getPixel(0, 0) === 0);
    checkTrue('pixel_range', await throwsRange(() => d.setPixel(9, 0, 0)) && await throwsRange(() => d.setPixel(0, 16, 0))
        && await throwsRange(() => d.setPixel(0, 0, 256)) && await throwsRange(() => d.setPixel(-1, 0, 0)));

    w.length = 0;
    await d.fill(0x40);
    checkTrue('fill', w.length === 1 && eq(0, cat([0x24], fill(144, 0x40))));
    await d.clear();
    checkTrue('clear', last().equals(cat([0x24], fill(144, 0))) && d.getPixel(2, 5) === 0);

    const ramp = Buffer.from(Array.from({ length: 144 }, (_, i) => i));
    await d.setPixels(ramp);
    checkTrue('set_pixels', last().equals(cat([0x24], ramp)) && d.getPixel(1, 1) === 17);
    checkTrue('set_pixels_len', await throwsRange(() => d.setPixels(Buffer.alloc(143))));

    w.length = 0;
    await d.setLedEnabled(1, 9, false);
    checkTrue('led_off', last().equals(b(0x03, 0xFD)));
    await d.setLedEnabled(1, 9, true);
    checkTrue('led_on', last().equals(b(0x03, 0xFF)));
    await d.setBlink(1, 9, true);
    checkTrue('blink_bit', last().equals(b(0x12 + 3, 0x02)));
    await d.setBlinkAll(true);
    checkTrue('blink_all', last().equals(cat([0x12], fill(18, 0xFF))));
    await d.setBlinkAll(false);
    checkTrue('blink_all_off', last().equals(cat([0x12], fill(18, 0))));

    w.length = 0;
    await d.selectFrame(3);
    checkTrue('select_frame', w.length === 1 && eq(0, b(0xFD, 0x03)));
    await d.setPixel(0, 0, 9);
    checkTrue('draw_on_frame3', last().equals(b(0x24, 9)));
    await d.copyFrame(3, 5);
    checkTrue('copy_frame', eq(w.length - 2, b(0xFD, 0x05)) && last().equals(cat([0x24, 9], fill(143, 0))));
    await d.clearFrame(5);
    checkTrue('clear_frame', eq(w.length - 3, cat([0x00], fill(18, 0xFF))) && last().equals(cat([0x24], fill(144, 0))));
    w.length = 0;
    await d.clearAllFrames();
    checkTrue('clear_all_frames', w.filter((x) => x.length === 145).length === 8);
    checkTrue('frame_range', await throwsRange(() => d.selectFrame(8)) && await throwsRange(() => d.copyFrame(0, 8)));
    await d.selectFrame(0);

    w.length = 0;
    await d.showFrame(4);
    checkTrue('show_frame', w.length === 2 && eq(0, b(0xFD, 0x0B)) && eq(1, b(0x00, 0x00, 0x04)));
    await d.setPictureMode();
    checkTrue('picture_mode', last().equals(b(0x00, 0x00)));

    await d.startAutoPlay(3, 2, 121, 1);
    checkTrue('auto_play', eq(w.length - 2, b(0x02, 0x23, 11)) && last().equals(b(0x00, 0x09)));
    await d.startAutoPlay();
    checkTrue('auto_play_defaults', eq(w.length - 2, b(0x02, 0x00, 11)) && last().equals(b(0x00, 0x08)));
    await d.startAutoPlay(0, 0, 704);
    checkTrue('auto_play_64', eq(w.length - 2, b(0x02, 0x00, 0x00)));
    checkTrue('auto_play_range', await throwsRange(() => d.startAutoPlay(0, 0, 2000)) && await throwsRange(() => d.startAutoPlay(0, 8)));
    c.setRegister(0x07, [0x15]);
    await d.stopAutoPlay();
    checkTrue('stop_auto_play', last().equals(b(0x00, 0x00, 0x05)));

    await d.setAudioMode(true);
    checkTrue('audio_mode', last().equals(b(0x00, 0x10)));
    await d.setAudioMode(false);
    checkTrue('audio_mode_off', last().equals(b(0x00, 0x00)));
    await d.setAudioSync(true);
    checkTrue('audio_sync', last().equals(b(0x06, 0x01)));
    await d.setAudioGain(9, true, true);
    checkTrue('audio_gain', last().equals(b(0x0B, 0x1B)));
    checkTrue('audio_gain_range', await throwsRange(() => d.setAudioGain(4)) && await throwsRange(() => d.setAudioGain(24)));
    await d.setAudioRate(100);
    checkTrue('audio_rate', last().equals(b(0x0C, 100)));

    await d.enableBlink(true, 3);
    checkTrue('enable_blink', last().equals(b(0x05, 0x0B)));
    await d.setIntensityControl(true);
    checkTrue('intensity_control', last().equals(b(0x05, 0x2B)));
    await d.enableBlink(false);
    checkTrue('disable_blink', last().equals(b(0x05, 0x21)));
    checkTrue('blink_period_range', await throwsRange(() => d.enableBlink(true, 8)));
    await d.setBreath(true, 2, 5, 3);
    checkTrue('breath', last().equals(b(0x08, 0x52, 0x13)));
    await d.setBreath(false);
    checkTrue('breath_off', last().equals(b(0x08, 0x00, 0x00)));

    await d.shutdown();
    checkTrue('shutdown', last().equals(b(0x0A, 0x00)));
    await d.shutdown(false);
    checkTrue('shutdown_off', last().equals(b(0x0A, 0x01)));

    c.setRegister(0x07, [0x13]);
    checkTrue('frame_state', (await d.frameState()) === 0x13);
    checkTrue('current_frame', (await d.currentFrame()) === 3);
    checkTrue('movie_finished', (await d.movieFinished()) === true);
    c.setRegister(0x07, [0x02]);
    checkTrue('movie_not_finished', (await d.movieFinished()) === false);

    await d.selectFrame(2);
    w.length = 0;
    c.setRegister(0x03, [0x2A]);
    checkTrue('read_function_register', (await d.readFunctionRegister(0x03)) === 0x2A);
    checkTrue('read_restores_page', last().equals(b(0xFD, 0x02)));
    w.length = 0;
    await d.writeFunctionRegister(0x03, 5);
    checkTrue('write_function_register', w.length === 3 && eq(0, b(0xFD, 0x0B)) && eq(1, b(0x03, 5)) && eq(2, b(0xFD, 0x02)));
    checkTrue('function_register_range', await throwsRange(() => d.readFunctionRegister(0x0D)));

    w.length = 0;
    c.setRegister(0x07, [0x14]);
    checkTrue('poll_interrupt', (await d.pollInterrupt()) === 0x14 && last().equals(b(0xFD, 0x02)));

    checkTrue('gamma_tables', IS31FL3731Full.GAMMA_32.length === 32 && IS31FL3731Full.GAMMA_64.length === 64
        && IS31FL3731Full.GAMMA_32[31] === 255 && IS31FL3731Full.GAMMA_64[63] === 255);

    const m = new IS31FL3731Minimal(new I2CConnectionMock());
    await m.setPixel(0, 0, 1);
    checkTrue('minimal', IS31FL3731Minimal.ROWS === 9 && IS31FL3731Minimal.COLS === 16);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
