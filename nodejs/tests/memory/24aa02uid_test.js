'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { EEPROM24AA02UIDFull } = require('../../packages/periph/src/chips/memory/_24aa02uid');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x50', 16);

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
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const eeprom = new EEPROM24AA02UIDFull(connection);

    const uid = await eeprom.readUid();
    checkTrue('readUid length 4', uid.length === 4);
    checkEq('readManufacturerCode', await eeprom.readManufacturerCode(), 0x29);
    checkEq('readDeviceCode',       await eeprom.readDeviceCode(),       0x41);

    const TEST_ADDR  = 0x10;
    const TEST_VALUE = 0x5A;
    await eeprom.writeByte(TEST_ADDR, TEST_VALUE);
    checkEq('writeByte/readByte round-trip', await eeprom.readByte(TEST_ADDR), TEST_VALUE);

    const PAGE_ADDR = 0x40;
    const PAGE_DATA = Buffer.from([0x11, 0x22, 0x33, 0x44]);
    await eeprom.writePage(PAGE_ADDR, PAGE_DATA);
    checkEqBuf('writePage read-back', await eeprom.read(PAGE_ADDR, PAGE_DATA.length), PAGE_DATA);

    const CROSS_ADDR = 0x06;
    const CROSS_DATA = Buffer.from([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]);
    await eeprom.write(CROSS_ADDR, CROSS_DATA);
    checkEqBuf('cross-page write read-back', await eeprom.read(CROSS_ADDR, CROSS_DATA.length), CROSS_DATA);

    const RANGE_ADDR = 0x50;
    const RANGE_LEN  = 16;
    const range = await eeprom.read(RANGE_ADDR, RANGE_LEN);
    checkTrue('read length', range.length === RANGE_LEN);

    const uid2 = await eeprom.readUid();
    checkEqBuf('uid unchanged after writes', uid2, uid);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
