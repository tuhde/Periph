'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { NEO6Minimal } = require('../../packages/periph/src/chips/gnss/neo6');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x42', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

// Requires a NEO-6 module wired to I2C (DDC) with a clear sky view. Achieving
// an actual fix needs an outdoor antenna and can take up to ~26 s (cold
// start); this test only requires that well-typed values come back.
async function main() {
    const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
    const gps = new NEO6Minimal(transport, 'i2c');

    checkTrue('fix() starts at 0', gps.fix() === 0);
    checkTrue('latitude() starts at null', gps.latitude() === null);

    for (let i = 0; i < 3000; i++) {
        await gps.update();
    }

    checkTrue('fix() is a valid quality code', [0, 1, 2].includes(gps.fix()));
    checkTrue('satellites() is a non-negative int', gps.satellites() >= 0);
    if (gps.fix() > 0) {
        checkTrue('latitude() in range once fixed', gps.latitude() >= -90 && gps.latitude() <= 90);
        checkTrue('longitude() in range once fixed', gps.longitude() >= -180 && gps.longitude() <= 180);
        checkTrue('altitude() is populated once fixed', gps.altitude() !== null);
    } else {
        console.log('note: no fix acquired during the test window (needs sky view)');
    }

    transport.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
