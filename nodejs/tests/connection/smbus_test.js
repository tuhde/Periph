'use strict';

const { SMBusConnection } = require('../../packages/periph/src/connection/smbus');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    // --- offline: address validation ---

    try {
        new SMBusConnection(I2C_BUS, 0x07);
        checkTrue('addr 0x07 rejected', false);
    } catch (e) {
        checkTrue('addr 0x07 rejected', e instanceof RangeError);
    }

    try {
        new SMBusConnection(I2C_BUS, 0x78);
        checkTrue('addr 0x78 rejected', false);
    } catch (e) {
        checkTrue('addr 0x78 rejected', e instanceof RangeError);
    }

    // --- online: basic I/O without PEC ---

    const connection = new SMBusConnection(I2C_BUS, I2C_ADDR);

    const readBuf = await connection.read(1);
    checkTrue('read returns 1 byte', readBuf.length === 1);

    await connection.write(Buffer.from([0x00]));
    checkTrue('write accepted', true);

    const wrBuf = await connection.writeRead(Buffer.from([0x00]), 1);
    checkTrue('writeRead returns 1 byte', wrBuf.length === 1);

    // --- online: write with PEC enabled ---

    const connectionPec = new SMBusConnection(I2C_BUS, I2C_ADDR, true);
    await connectionPec.write(Buffer.from([0x00]));
    checkTrue('write with PEC accepted', true);
    await connectionPec.close();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
