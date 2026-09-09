'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SK6812RGBWFull } = require('../../packages/periph/src/chips/led/sk6812rgbw');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// SK6812RGBW is write-only, same reasoning as WS2812B (see
// ws2812b_test_unit.js) - the generic I2CConnectionMock works unchanged;
// only its .writes list is used. This chip additionally appends 24
// zero-bytes (~80us reset) after the pixel buffer on every transmit.

const N = 3;

function allZero(buf, from, to) {
    for (let i = from; i < to; i++) if (buf[i] !== 0) return false;
    return true;
}

async function main() {
    const connection = new I2CConnectionMock();
    const sensor = new SK6812RGBWFull(connection, N);
    checkTrue('init', true);

    // fill(): GRBW wire order, all N pixels, transmits immediately, with
    // the 24-byte extended reset tail appended.
    await sensor.fill(0x11, 0x22, 0x33, 0x44);
    checkTrue('fill_transmits', connection.writes.length === 1);
    let w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_length', w.length === N * 4 + 24);
    checkTrue('fill_grbw_order', w[0] === 0x22 && w[1] === 0x11 && w[2] === 0x33 && w[3] === 0x44);
    let allPixels = true;
    for (let i = 0; i < N; i++) {
        if (w[i * 4] !== 0x22 || w[i * 4 + 1] !== 0x11 || w[i * 4 + 2] !== 0x33 || w[i * 4 + 3] !== 0x44) allPixels = false;
    }
    checkTrue('fill_all_pixels', allPixels);
    checkTrue('fill_reset_tail', allZero(w, N * 4, N * 4 + 24));

    // fill() white channel defaults to 0.
    await sensor.fill(0x10, 0x20, 0x30);
    w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_white_defaults_zero', w[0] === 0x20 && w[1] === 0x10 && w[2] === 0x30 && w[3] === 0x00);

    // fill() clamps out-of-range channels.
    await sensor.fill(-10, 300, 128, 999);
    w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_clamps', w[0] === 255 && w[1] === 0 && w[2] === 128 && w[3] === 255);

    // off(): equivalent to fill(0, 0, 0, 0).
    await sensor.off();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('off', allZero(w, 0, N * 4));
    checkTrue('off_reset_tail', allZero(w, N * 4, N * 4 + 24));

    // set_pixel(): buffer-only, no transmit; white channel defaults to 0.
    let writesBefore = connection.writes.length;
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC, 0xDD);
    checkTrue('set_pixel_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_then_show', w[4] === 0xBB && w[5] === 0xAA && w[6] === 0xCC && w[7] === 0xDD);
    checkTrue('set_pixel_other_pixels_unchanged', w[0] === 0 && w[1] === 0 && w[2] === 0 && w[3] === 0);

    sensor.set_pixel(0, 0x01, 0x02, 0x03);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_white_defaults_zero', w[0] === 0x02 && w[1] === 0x01 && w[2] === 0x03 && w[3] === 0x00);

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x05, 0x06, 0x07, 0x08);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_clamps_index',
        w[(N - 1) * 4] === 0x06 && w[(N - 1) * 4 + 1] === 0x05 && w[(N - 1) * 4 + 2] === 0x07 && w[(N - 1) * 4 + 3] === 0x08);

    // set_pixels(): [r,g,b] or [r,g,b,w] arrays, extras beyond n ignored, white defaults to 0.
    sensor.set_pixels([[0x10, 0x20, 0x30], [0x40, 0x50, 0x60, 0x70], [0x80, 0x90, 0xA0, 0xB0], [0xFF, 0xFF, 0xFF, 0xFF]]);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    const expected = [0x20, 0x10, 0x30, 0x00, 0x50, 0x40, 0x60, 0x70, 0x90, 0x80, 0xA0, 0xB0];
    checkTrue('set_pixels', expected.every((v, i) => w[i] === v));

    // brightness scaling at show() time: sent = stored * brightness / 255.
    sensor.brightness = 128;
    sensor.set_pixel(0, 200, 100, 50, 40);
    sensor.set_pixel(1, 0, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0, 0);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    const sg = (100 * 128 / 255) | 0;
    const sr = (200 * 128 / 255) | 0;
    const sb = (50 * 128 / 255) | 0;
    const sw = (40 * 128 / 255) | 0;
    checkTrue('brightness_scaling', w[0] === sg && w[1] === sr && w[2] === sb && w[3] === sw);
    sensor.brightness = 255;

    // rotate(): shifts pixel buffer left by `steps` whole (4-byte) pixels, no transmit.
    sensor.set_pixels([[1, 0, 0, 0], [2, 0, 0, 0], [3, 0, 0, 0]]);
    await sensor.show();
    writesBefore = connection.writes.length;
    sensor.rotate(1);
    checkTrue('rotate_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('rotate_shifts_left', w[1] === 2 && w[1 + 2 * 4] === 1);

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0), white=0.
    await sensor.fill_hsv(0.0, 1.0, 1.0);
    w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_hsv_red', w[0] === 0 && w[1] === 255 && w[2] === 0 && w[3] === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
