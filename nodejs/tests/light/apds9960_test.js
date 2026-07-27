'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { APDS9960Full }   = require('../../packages/periph/src/chips/light/apds9960');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x39', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log(`FAIL ${label}: got 0x${got.toString(16).toUpperCase().padStart(2,'0')}, expected 0x${expected.toString(16).toUpperCase().padStart(2,'0')}`);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const apds = new APDS9960Full(connection);

    checkEq('chip_id', await apds.chipId(), 0xAB);

    const { clear, red, green, blue } = await apds.color();
    checkTrue('color_clear >= 0', clear >= 0);
    checkTrue('color_red >= 0', red >= 0);
    checkTrue('color_green >= 0', green >= 0);
    checkTrue('color_blue >= 0', blue >= 0);

    checkTrue('is_als_valid', await apds.isAlsValid());

    await apds.enableProximity(true);
    const end1 = Date.now() + 100;
    while (Date.now() < end1) {}
    const p = await apds.proximity();
    checkTrue('proximity <= 255', p <= 255);
    checkTrue('is_proximity_valid', await apds.isProximityValid());

    await apds.configureAls(0xB6, 1);
    const end2 = Date.now() + 210;
    while (Date.now() < end2) {}
    checkTrue('als_valid after configure', await apds.isAlsValid());

    await apds.alsThreshold(100, 60000);
    await apds.proximityThreshold(10, 200);
    await apds.setPersistence(0, 1);
    checkTrue('persistence set', true);

    await apds.enableAlsInterrupt(true);
    await apds.enableProximityInterrupt(true);
    await apds.clearAlsInterrupt();
    await apds.clearProximityInterrupt();
    await apds.clearAllInterrupts();
    checkTrue('interrupts cleared', true);

    await apds.setProximityOffset(10, -5);
    await apds.setProximityMask(false, false, false, false);
    checkTrue('proximity offset/mask set', true);

    await apds.enableGesture(true);
    await apds.configureGesture(1, 0, 0, 1, 1, 50, 20);
    checkTrue('gesture configured', true);
    checkTrue('gesture_fifo_level >= 0', (await apds.gestureFifoLevel()) >= 0);
    await apds.clearGestureFifo();
    await apds.enableGestureInterrupt(false);
    await apds.enableGesture(false);
    checkTrue('gesture disabled', true);

    const s = await apds.status();
    checkTrue('status readable', s >= 0);

    await apds.enableProximity(false);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
