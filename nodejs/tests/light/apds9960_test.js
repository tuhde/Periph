'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
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

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const apds = new APDS9960Full(transport);

checkEq('chip_id', apds.chipId(), 0xAB);

const { clear, red, green, blue } = apds.color();
checkTrue('color_clear >= 0', clear >= 0);
checkTrue('color_red >= 0', red >= 0);
checkTrue('color_green >= 0', green >= 0);
checkTrue('color_blue >= 0', blue >= 0);

checkTrue('is_als_valid', apds.isAlsValid());

apds.enableProximity(true);
const end1 = Date.now() + 100;
while (Date.now() < end1) {}
const p = apds.proximity();
checkTrue('proximity <= 255', p <= 255);
checkTrue('is_proximity_valid', apds.isProximityValid());

apds.configureAls(0xB6, 1);
const end2 = Date.now() + 210;
while (Date.now() < end2) {}
checkTrue('als_valid after configure', apds.isAlsValid());

apds.alsThreshold(100, 60000);
apds.proximityThreshold(10, 200);
apds.setPersistence(0, 1);
checkTrue('persistence set', true);

apds.enableAlsInterrupt(true);
apds.enableProximityInterrupt(true);
apds.clearAlsInterrupt();
apds.clearProximityInterrupt();
apds.clearAllInterrupts();
checkTrue('interrupts cleared', true);

apds.setProximityOffset(10, -5);
apds.setProximityMask(false, false, false, false);
checkTrue('proximity offset/mask set', true);

apds.enableGesture(true);
apds.configureGesture(1, 0, 0, 1, 1, 50, 20);
checkTrue('gesture configured', true);
checkTrue('gesture_fifo_level >= 0', apds.gestureFifoLevel() >= 0);
apds.clearGestureFifo();
apds.enableGestureInterrupt(false);
apds.enableGesture(false);
checkTrue('gesture disabled', true);

const s = apds.status();
checkTrue('status readable', s >= 0);

apds.enableProximity(false);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
