'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADE7953Full } = require('../../packages/periph/src/chips/power/ade7953');

(async () => {
    let passed = 0;
    let failed = 0;
    function checkEq(label, got, expected, tolerance = 1e-3) {
        if (Math.abs(got - expected) < tolerance) {
            console.log('PASS', label); passed++;
        } else {
            console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++;
        }
    }

    const connection = new I2CConnectionMock();
    const ade = new ADE7953Full(connection, 100.0, 10.0);

    // --- voltage ---
    connection.setRegister(0x21C, [0x89, 0xD1, 0x47]);
    checkEq('voltage full-scale', await ade.voltage(), 35.35533905932738);

    // --- current Channel A ---
    connection.setRegister(0x21A, [0x89, 0xD1, 0x47]);
    checkEq('current_a full-scale', await ade.current(), 3.53553390593);

    // --- activePower ---
    connection.setRegister(0x212, [0x4A, 0x31, 0xC1]);
    checkEq('activePower full-scale', await ade.activePower(), 125.0);

    // --- reset() writes SWRST in CONFIG ---
    const writesBefore = connection.writes.length;
    await ade.reset();
    const writesAfter = connection.writes.length;
    let foundSwrst = false;
    for (let i = writesBefore + 1; i < writesAfter; i++) {
        const w = connection.writes[i];
        if (w.length >= 4 && w[2] === 0x01 && (w[3] & 0x80) !== 0) {
            foundSwrst = true;
            break;
        }
    }
    if (foundSwrst) { console.log('PASS reset writes SWRST'); passed++; }
    else            { console.log('FAIL reset writes SWRST'); failed++; }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();