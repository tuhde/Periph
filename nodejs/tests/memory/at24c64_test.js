'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AT24C64Full } = require('../../packages/periph/src/chips/memory/at24c64');

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
    const ee = new AT24C64Full(connection);

    checkEq('size', ee.size(), 8192);
    checkEq('pageSize', ee.pageSize(), 32);

    await ee.writeByte(0x0010, 0x5A);
    checkEq('writeByte/readByte round-trip', await ee.readByte(0x0010), 0x5A);
    checkTrue('waitReady after write', await ee.waitReady(10));

    const DATA = Buffer.from(Array.from({ length: 200 }, (_, i) => i));
    await ee.write(0x0070, DATA);
    checkEqBuf('page-split write/read round-trip', await ee.read(0x0070, DATA.length), DATA);

    const PAGE = Buffer.from(Array.from({ length: 32 }, (_, i) => i));
    await ee.writePage(0x0300, PAGE);
    checkEqBuf('writePage round-trip', await ee.read(0x0300, 32), PAGE);
    let crossRejected = false;
    try { await ee.writePage(0x037C, Buffer.alloc(8)); } catch (e) { crossRejected = e instanceof RangeError; }
    checkTrue('writePage crossing rejected', crossRejected);

    await ee.fill(0x0100, 100, 0xC3);
    checkEqBuf('fill', await ee.read(0x0100, 100), Buffer.alloc(100, 0xC3));
    await ee.fill(0x0100, 100);
    checkEqBuf('fill default zero', await ee.read(0x0100, 100), Buffer.alloc(100));

    await ee.write(0x1FFE, Buffer.from([0x12, 0x34]));
    checkEqBuf('last bytes round-trip', await ee.read(0x1FFE, 2), Buffer.from([0x12, 0x34]));

    let rejected = false;
    try { await ee.read(0x1FFF, 2); } catch (e) { rejected = e instanceof RangeError; }
    checkTrue('wrap rejected', rejected);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
