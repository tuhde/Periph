'use strict';

const { NeoPixelConnection } = require('../../packages/periph/src/connection/neopixel');
const { WS2812BMinimal, WS2812BFull } = require('../../packages/periph/src/chips/led/ws2812b');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

async function main() {
    const connection = new NeoPixelConnection(SPI_BUS, SPI_DEVICE);

    // --- WS2812BMinimal ---
    const strip = new WS2812BMinimal(connection, 8);

    await strip.fill(255, 0, 0);
    checkTrue('fill(255,0,0) accepted', true);

    await strip.fill(0, 255, 0);
    checkTrue('fill(0,255,0) accepted', true);

    await strip.fill(0, 0, 255);
    checkTrue('fill(0,0,255) accepted', true);

    await strip.off();
    checkTrue('off() accepted', true);

    // --- WS2812BFull ---
    const full = new WS2812BFull(connection, 8);

    checkEq('default brightness is 255', full.brightness, 255);

    full.set_pixel(0, 255, 0, 0);
    await full.show();
    checkTrue('set_pixel + show accepted', true);

    full.set_pixel(7, 0, 0, 255);
    await full.show();
    checkTrue('set_pixel at last index + show accepted', true);

    full.set_pixels([[255,0,0],[0,255,0],[0,0,255]]);
    await full.show();
    checkTrue('set_pixels + show accepted', true);

    full.brightness = 128;
    checkEq('brightness setter', full.brightness, 128);
    await full.show();
    checkTrue('show() with brightness=128 accepted', true);

    full.brightness = 0;
    await full.show();
    checkTrue('show() with brightness=0 accepted', true);

    full.brightness = 255;

    full.rotate(1);
    await full.show();
    checkTrue('rotate + show accepted', true);

    await full.fill_hsv(0.0, 1.0, 1.0);
    checkTrue('fill_hsv(0.0) accepted', true);

    await full.fill_hsv(0.333, 1.0, 1.0);
    checkTrue('fill_hsv(0.333) accepted', true);

    await full.fill_hsv(0.667, 1.0, 1.0);
    checkTrue('fill_hsv(0.667) accepted', true);

    await full.off();
    checkTrue('off() on Full accepted', true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
