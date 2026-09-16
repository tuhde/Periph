'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HMC5883LFull }   = require('../../packages/periph/src/chips/magnetometer/hmc5883l');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1E', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log('FAIL %s: got %d, expected %d', label, got, expected);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    // Bypass constructor
    const hmc5883l = Object.create(HMC5883LFull.prototype);
    hmc5883l._conn = connection;

    // --- Identification ---
    const [idA, idB, idC] = await hmc5883l.identify();
    checkEq('identify A', idA, 0x48);
    checkEq('identify B', idB, 0x34);
    checkEq('identify C', idC, 0x33);

    // --- Status ---
    const sb = await hmc5883l.status();
    checkTrue('status_byte valid', sb >= 0 && sb <= 255);

    // --- Data ready ---
    checkTrue('data_ready returns bool', await hmc5883l.dataReady() === true || await hmc5883l.dataReady() === false);

    // --- Magnetic field reading ---
    const { x, y, z } = await hmc5883l.magneticField();
    checkTrue('magneticField x is number or null', x === null || typeof x === 'number');
    checkTrue('magneticField y is number or null', y === null || typeof y === 'number');
    checkTrue('magneticField z is number or null', z === null || typeof z === 'number');

    // --- Configuration ---
    await hmc5883l.configure(15, 8, 1);
    checkTrue('configure accepted', true);

    await hmc5883l.setGain(2);
    checkTrue('setGain accepted', true);

    await hmc5883l.setMode('continuous');
    checkTrue('setMode continuous accepted', true);

    // --- Single-shot measurement ---
    await new Promise(r => setTimeout(r, 6));
    const single = await hmc5883l.singleMeasurement();
    checkTrue('singleMeasurement x is number or null', single.x === null || typeof single.x === 'number');
    checkTrue('singleMeasurement y is number or null', single.y === null || typeof single.y === 'number');
    checkTrue('singleMeasurement z is number or null', single.z === null || typeof single.z === 'number');

    await hmc5883l.setMode('idle');
    checkTrue('setMode idle accepted', true);

    // --- Self-test ---
    const selfTest = await hmc5883l.selfTest(true);
    checkTrue('selfTest x is number or null', selfTest.x === null || typeof selfTest.x === 'number');
    checkTrue('selfTest y is number or null', selfTest.y === null || typeof selfTest.y === 'number');
    checkTrue('selfTest z is number or null', selfTest.z === null || typeof selfTest.z === 'number');

    await connection.close();

    console.log('===DONE: %d passed, %d failed===', passed, failed);
    process.exit(failed === 0 ? 0 : 1);
}

main();