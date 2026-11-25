'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { IS31FL3731Minimal, IS31FL3731Full } = require('../../packages/periph/src/chips/led/is31fl3731');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x74', 16);

let passed = 0, failed = 0;

function check(label, ok, extra) {
    if (ok) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label + (extra ? ': ' + extra : '')); failed++; }
}

async function attempt(label, fn) {
    try { await fn(); check(label, true); }
    catch (e) { check(label, false, e.message); }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const matrix = new IS31FL3731Minimal(connection);
    await matrix.ready;
    check('constants', IS31FL3731Minimal.ROWS === 9 && IS31FL3731Minimal.COLS === 16);
    await attempt('set_pixel', async () => { await matrix.setPixel(0, 0, 255); await matrix.setPixel(8, 15, 64); });
    await attempt('fill', () => matrix.fill(32));
    await attempt('clear', () => matrix.clear());

    const full = new IS31FL3731Full(connection);
    await full.ready;
    await full.setPixel(3, 4, 99);
    check('get_pixel', full.getPixel(3, 4) === 99 && full.getPixel(3, 5) === 0);
    await attempt('set_pixels', () => full.setPixels(Buffer.from(Array.from({ length: 144 }, (_, i) => i))));
    await attempt('led_enabled', async () => { await full.setLedEnabled(0, 0, false); await full.setLedEnabled(0, 0, true); });
    await attempt('blink', async () => {
        await full.setBlink(1, 1, true); await full.setBlinkAll(true); await full.enableBlink(true, 2);
        await full.enableBlink(false); await full.setBlinkAll(false);
    });
    await attempt('frames', async () => {
        await full.selectFrame(1); await full.fill(10); await full.copyFrame(1, 2); await full.showFrame(2);
        await full.selectFrame(0); await full.showFrame(0);
    });
    await attempt('clear_frames', async () => { await full.clearFrame(1); await full.clearAllFrames(); });
    await attempt('picture_mode', () => full.setPictureMode());
    await attempt('auto_play', async () => { await full.startAutoPlay(2, 1, 110); await full.stopAutoPlay(); });
    await attempt('audio', async () => {
        await full.setAudioGain(6, true, false); await full.setAudioRate(10);
        await full.setAudioSync(false); await full.setAudioMode(false);
    });
    await attempt('breath', async () => { await full.setBreath(true, 2, 2, 1); await full.setBreath(false); });
    await attempt('intensity_control', async () => { await full.setIntensityControl(true); await full.setIntensityControl(false); });
    await attempt('frame_state', async () => { await full.frameState(); await full.currentFrame(); await full.movieFinished(); });
    await attempt('function_register', async () => { await full.writeFunctionRegister(0x03, 0x05); await full.readFunctionRegister(0x03); });
    await attempt('poll_interrupt', () => full.pollInterrupt());
    await attempt('shutdown', async () => { await full.shutdown(); await full.shutdown(false); });
    await full.clear();

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
