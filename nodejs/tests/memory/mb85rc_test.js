'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MB85RCFull } = require('../../packages/periph/src/chips/memory/mb85rc');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x50', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label + ': got', got, 'expected', expected); failed++; }
}

function checkEqBuf(label, got, expected) {
    if (Buffer.compare(got, expected) === 0) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label + ': got', got.toString('hex'), 'expected', expected.toString('hex')); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR, null, null, 2);
    const fram = new MB85RCFull(connection);

    checkEq('size', fram.size(), 32768);

    await fram.writeByte(0x0010, 0x5A);
    checkEq('writeByte/readByte round-trip', await fram.readByte(0x0010), 0x5A);

    const DATA = Buffer.from(Array.from({ length: 100 }, (_, i) => i));
    await fram.write(0x0100, DATA);
    checkEqBuf('chunked write/read round-trip', await fram.read(0x0100, DATA.length), DATA);

    await fram.fill(0x0100, 100, 0xC3);
    checkEqBuf('fill', await fram.read(0x0100, 100), Buffer.alloc(100, 0xC3));
    await fram.fill(0x0100, 100);
    checkEqBuf('fill default zero', await fram.read(0x0100, 100), Buffer.alloc(100));

    await fram.write(0x7FFE, Buffer.from([0x12, 0x34]));
    checkEqBuf('last bytes round-trip', await fram.read(0x7FFE, 2), Buffer.from([0x12, 0x34]));

    let rejected = false;
    try { await fram.read(0x7FFF, 2); } catch (e) { rejected = e instanceof RangeError; }
    checkTrue('wrap rejected', rejected);

    try {
        const idConnection = new I2CConnection(I2C_BUS, 0x7C, null, null, 1);
        const id = await fram.readDeviceId(idConnection, I2C_ADDR);
        checkTrue('readDeviceId', id.manufacturer === 0x00A && id.density === 0x5 && id.product === 0x10);
        idConnection.close();
    } catch (e) {
        console.log('SKIP readDeviceId (0x7C not accessible on this host)');
    }

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
