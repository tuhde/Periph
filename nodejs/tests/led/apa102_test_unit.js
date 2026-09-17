'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { APA102Full } = require('../../packages/periph/src/chips/led/apa102');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// APA102 is write-only (no registers, no reads) and uses raw synchronous SPI.
// The SPIConnectionMock records every write() call; only its .writes list is used.

const N = 4;

async function main() {
    const connection = new SPIConnectionMock();
    const sensor = new APA102Full(connection, N);
    checkTrue('init', true);

    // Expected frame structure:
    // start_frame = bytes(4)                    # 0x00 × 4
    // pixel data: N × 4 bytes [0xE0|brightness, B, G, R]
    // end_frame = bytes([0xFF] * max(4, (N + 15) // 16))
    const endBytes = Math.max(4, Math.floor((N + 15) / 16));
    const FRAME_LEN = 4 + N * 4 + endBytes;

    // fill(): BGR wire order with brightness byte, all N pixels, transmits immediately.
    await sensor.fill(0x11, 0x22, 0x33);
    checkTrue('fill_transmits', connection.writes.length === 1);
    checkTrue('fill_length', connection.writes[connection.writes.length - 1].length === FRAME_LEN);

    // Check start frame (4 zero bytes)
    let w = connection.writes[connection.writes.length - 1];
    checkTrue('fill_start_frame', w[0] === 0 && w[1] === 0 && w[2] === 0 && w[3] === 0);

    // Check pixel data: [0xE0|31, B, G, R] = [0xFF, 0x33, 0x22, 0x11] per pixel
    let pixelOk = true;
    for (let i = 0; i < N; i++) {
        const base = 4 + i * 4;
        if (w[base] !== 0xFF || w[base + 1] !== 0x33 || w[base + 2] !== 0x22 || w[base + 3] !== 0x11) {
            pixelOk = false;
        }
    }
    checkTrue('fill_pixel_order', pixelOk);

    // Check end frame (all 0xFF)
    let endOk = true;
    for (let i = 0; i < endBytes; i++) {
        if (w[4 + N * 4 + i] !== 0xFF) endOk = false;
    }
    checkTrue('fill_end_frame', endOk);

    // fill() clamps out-of-range channels.
    await sensor.fill(-10, 300, 128);
    w = connection.writes[connection.writes.length - 1];
    // Expected: [0xFF, 255, 0, 128] per pixel (B=255 clamped, G=0 clamped, R=128)
    let clampOk = true;
    for (let i = 0; i < N; i++) {
        const base = 4 + i * 4;
        if (w[base] !== 0xFF || w[base + 1] !== 255 || w[base + 2] !== 0 || w[base + 3] !== 128) {
            clampOk = false;
        }
    }
    checkTrue('fill_clamps', clampOk);

    // off(): equivalent to fill(0, 0, 0).
    await sensor.off();
    w = connection.writes[connection.writes.length - 1];
    // Expected: [0xFF, 0, 0, 0] per pixel (brightness=31, B=0, G=0, R=0)
    let offOk = true;
    for (let i = 0; i < N; i++) {
        const base = 4 + i * 4;
        if (w[base] !== 0xFF || w[base + 1] !== 0 || w[base + 2] !== 0 || w[base + 3] !== 0) {
            offOk = false;
        }
    }
    checkTrue('off', offOk);

    // set_pixel(): buffer-only, no transmit.
    let writesBefore = connection.writes.length;
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC);
    checkTrue('set_pixel_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    // Pixel 1 should be [0xFF, 0xCC, 0xBB, 0xAA]
    checkTrue('set_pixel_then_show', w[4 + 4] === 0xFF && w[4 + 5] === 0xCC && w[4 + 6] === 0xBB && w[4 + 7] === 0xAA);
    // Pixel 0 should be [0xFF, 0, 0, 0]
    checkTrue('set_pixel_other_pixels_zero', w[4] === 0xFF && w[5] === 0 && w[6] === 0 && w[7] === 0);

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x01, 0x02, 0x03);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_clamps_index',
        w[4 + (N - 1) * 4] === 0xFF && w[4 + (N - 1) * 4 + 1] === 0x03 && w[4 + (N - 1) * 4 + 2] === 0x02 && w[4 + (N - 1) * 4 + 3] === 0x01);

    // set_pixel() with custom pixel_brightness.
    sensor.set_pixel(0, 0x10, 0x20, 0x30, 16);  // brightness=16
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    checkTrue('set_pixel_hardware_brightness', w[4] === (0xE0 | 16) && w[5] === 0x30 && w[6] === 0x20 && w[7] === 0x10);

    // set_pixels(): sequence of [r, g, b] or [r, g, b, brightness], extras beyond n ignored.
    sensor.set_pixels([[0x10, 0x20, 0x30], [0x40, 0x50, 0x60], [0x70, 0x80, 0x90], [0xA0, 0xB0, 0xC0], [0xFF, 0xFF, 0xFF]]);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    let setPixelsOk = true;
    // pixel 0: [0xFF, 0x30, 0x20, 0x10]
    if (w[4] !== 0xFF || w[5] !== 0x30 || w[6] !== 0x20 || w[7] !== 0x10) setPixelsOk = false;
    // pixel 1: [0xFF, 0x60, 0x50, 0x40]
    if (w[8] !== 0xFF || w[9] !== 0x60 || w[10] !== 0x50 || w[11] !== 0x40) setPixelsOk = false;
    // pixel 2: [0xFF, 0x90, 0x80, 0x70]
    if (w[12] !== 0xFF || w[13] !== 0x90 || w[14] !== 0x80 || w[15] !== 0x70) setPixelsOk = false;
    // pixel 3: [0xFF, 0xC0, 0xB0, 0xA0]
    if (w[16] !== 0xFF || w[17] !== 0xC0 || w[18] !== 0xB0 || w[19] !== 0xA0) setPixelsOk = false;
    checkTrue('set_pixels', setPixelsOk);

    // set_pixels() with per-pixel brightness.
    sensor.set_pixels([[0x10, 0x20, 0x30, 31], [0x40, 0x50, 0x60, 16], [0x70, 0x80, 0x90, 8], [0xA0, 0xB0, 0xC0, 4]]);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    let setPixelsBrightOk = true;
    // pixel 0: [0xFF, 0x30, 0x20, 0x10]
    if (w[4] !== 0xFF || w[5] !== 0x30 || w[6] !== 0x20 || w[7] !== 0x10) setPixelsBrightOk = false;
    // pixel 1: [0xF0, 0x60, 0x50, 0x40]
    if (w[8] !== 0xF0 || w[9] !== 0x60 || w[10] !== 0x50 || w[11] !== 0x40) setPixelsBrightOk = false;
    // pixel 2: [0xE8, 0x90, 0x80, 0x70]
    if (w[12] !== 0xE8 || w[13] !== 0x90 || w[14] !== 0x80 || w[15] !== 0x70) setPixelsBrightOk = false;
    // pixel 3: [0xE4, 0xC0, 0xB0, 0xA0]
    if (w[16] !== 0xE4 || w[17] !== 0xC0 || w[18] !== 0xB0 || w[19] !== 0xA0) setPixelsBrightOk = false;
    checkTrue('set_pixels_hardware_brightness', setPixelsBrightOk);

    // brightness scaling at show() time: sent = stored * brightness / 255.
    // Hardware brightness byte is NOT scaled.
    sensor.brightness = 128;
    sensor.set_pixel(0, 200, 100, 50);  // stored: [0xFF, 50, 100, 200]
    sensor.set_pixel(1, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0);
    sensor.set_pixel(3, 0, 0, 0);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    const scaledR = (200 * 128 / 255) | 0;
    const scaledG = (100 * 128 / 255) | 0;
    const scaledB = (50 * 128 / 255) | 0;
    checkTrue('brightness_scaling', w[4] === 0xFF && w[5] === scaledB && w[6] === scaledG && w[7] === scaledR);
    // Other pixels unchanged (still zero)
    checkTrue('brightness_other_pixels_zero', w[8] === 0xFF && w[9] === 0 && w[10] === 0 && w[11] === 0);
    checkTrue('get_brightness', sensor.brightness === 128);
    sensor.brightness = 255;

    // rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
    sensor.set_pixel(0, 1, 0, 0);
    sensor.set_pixel(1, 2, 0, 0);
    sensor.set_pixel(2, 3, 0, 0);
    sensor.set_pixel(3, 4, 0, 0);
    await sensor.show();
    writesBefore = connection.writes.length;
    sensor.rotate(1);
    checkTrue('rotate_no_transmit', connection.writes.length === writesBefore);
    await sensor.show();
    w = connection.writes[connection.writes.length - 1];
    // After rotate(1): pixel 0 gets old pixel 1 (R=2), pixel 3 gets old pixel 0 (R=1)
    checkTrue('rotate_shifts_left', w[4 + 0 + 3] === 2 && w[4 + (N - 1) * 4 + 3] === 1);

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
    await sensor.fill_hsv(0.0, 1.0, 1.0);
    w = connection.writes[connection.writes.length - 1];
    // Red=255, Green=0, Blue=0 -> wire: [0xFF, 0, 0, 255]
    let hsvOk = true;
    for (let i = 0; i < N; i++) {
        const base = 4 + i * 4;
        if (w[base] !== 0xFF || w[base + 1] !== 0 || w[base + 2] !== 0 || w[base + 3] !== 255) {
            hsvOk = false;
        }
    }
    checkTrue('fill_hsv_red', hsvOk);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();