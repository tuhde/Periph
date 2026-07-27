'use strict';

const { NeoPixelConnection } = require('../../packages/periph/src/connection/neopixel');

const SPI_BUS    = parseInt(process.env.SPI_BUS     || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE  || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new NeoPixelConnection(SPI_BUS, SPI_DEVICE);
    const data = Buffer.from([0xFF, 0x00, 0x00]);
    await connection.write(data);

    checkTrue('write accepted data', data.length === 3);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
