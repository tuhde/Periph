'use strict';

const { NeoPixelTransport } = require('../../packages/periph/src/transport/neopixel');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log(`FAIL ${label}: got ${got}, expected ${expected}`);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new NeoPixelTransport(SPI_BUS, SPI_DEVICE);

transport.write(Buffer.from([0x00, 0x00, 0x00]));
checkTrue('write_black_no_error', true);

transport.write(Buffer.from([0xFF, 0xFF, 0xFF]));
checkTrue('write_white_no_error', true);

transport.write(Buffer.from([0x00, 0xFF, 0x00]));
checkTrue('write_green_no_error', true);

transport.write(Buffer.from([0x10, 0x20, 0x30, 0x40]));
checkTrue('write_4bytes_no_error', true);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);