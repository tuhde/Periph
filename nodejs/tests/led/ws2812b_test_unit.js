'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { WS2812BFull } = require('../../packages/periph/src/chips/led/ws2812b');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// WS2812B is write-only (no registers, no reads) - the connection mock only
// ever needs to record what bytes were sent, so the same generic
// I2CConnectionMock used by every register-addressed chip works unchanged
// here too; only its .writes list is used.

const N = 4;

async function main() {
    const connection = new I2CConnectionMock();
    const sensor = new WS2812BFull(connection, N);
    checkTrue('init', true);

    // fill(): GRB wire order, all N pixels, transmits immediately.
    await sensor.fill(0x11, 0x22, 0x33);
    checkTrue('fill_transmits', connection.writes.length === 1);
    checkTrue('fill_length', connection.writes[connection.writes.length - 1].length === N * 3);
    let w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_grb_order', w[0] === 0x22 && w[1] === 0x11 && w[2] === 0x33);
    let allPixels = true;
    for (let i = 0; i < N; i++) {
        if (w[i * 3] !== 0x22 || w[i * 3 + 1] !== 0x11 || w[i * 3 + 2] !== 0x33) allPixels = false;
    }
    checkTrue('fill_all_pixels', allPixels);

    // fill() clamps out-of-range channels.
    await sensor.fill(-10, 300, 128);
    w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_clamps', w[0] === 255 && w[1] === 0 && w[2] === 128);

    // off(): equivalent to fill(0, 0, 0).
    await sensor.off();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('off', w.every((b) => b === 0));

    // set_pixel(): buffer-only, no transmit.
    let writesBefore = connection.writes.length;
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC);
    checkTrue('set_pixel_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_then_show', w[3] === 0xBB && w[4] === 0xAA && w[5] === 0xCC);
    checkTrue('set_pixel_other_pixels_unchanged', w[0] === 0 && w[1] === 0 && w[2] === 0);

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x01, 0x02, 0x03);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_clamps_index',
        w[(N - 1) * 3] === 0x02 && w[(N - 1) * 3 + 1] === 0x01 && w[(N - 1) * 3 + 2] === 0x03);

    // set_pixels(): sequence of [r, g, b], extras beyond n ignored.
    sensor.set_pixels([[0x10, 0x20, 0x30], [0x40, 0x50, 0x60], [0x70, 0x80, 0x90], [0xA0, 0xB0, 0xC0], [0xFF, 0xFF, 0xFF]]);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    const expected = [0x20, 0x10, 0x30, 0x50, 0x40, 0x60, 0x80, 0x70, 0x90, 0xB0, 0xA0, 0xC0];
    checkTrue('set_pixels', expected.every((v, i) => w[i] === v));

    // brightness scaling at show() time: sent = stored * brightness / 255.
    sensor.brightness = 128;
    sensor.set_pixel(0, 200, 100, 50);
    sensor.set_pixel(1, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0);
    sensor.set_pixel(3, 0, 0, 0);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    const scaledR = (200 * 128 / 255) | 0;
    const scaledG = (100 * 128 / 255) | 0;
    const scaledB = (50 * 128 / 255) | 0;
    checkTrue('brightness_scaling', w[0] === scaledG && w[1] === scaledR && w[2] === scaledB);
    sensor.brightness = 255;

    // rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
    sensor.set_pixels([[1, 0, 0], [2, 0, 0], [3, 0, 0], [4, 0, 0]]);
    await sensor.show();
    writesBefore = connection.writes.length;
    sensor.rotate(1);
    checkTrue('rotate_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('rotate_shifts_left', w[1] === 2 && w[1 + 3 * 3] === 1);

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
    await sensor.fill_hsv(0.0, 1.0, 1.0);
    w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_hsv_red', w[0] === 0 && w[1] === 255 && w[2] === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
