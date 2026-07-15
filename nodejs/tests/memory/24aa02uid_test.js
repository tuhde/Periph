'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
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

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const eeprom = new EEPROM24AA02UIDFull(transport);

const uid = eeprom.readUid();
checkTrue('readUid length 4', uid.length === 4);
checkEq('readManufacturerCode', eeprom.readManufacturerCode(), 0x29);
checkEq('readDeviceCode',       eeprom.readDeviceCode(),       0x41);

const TEST_ADDR  = 0x10;
const TEST_VALUE = 0x5A;
eeprom.writeByte(TEST_ADDR, TEST_VALUE);
checkEq('writeByte/readByte round-trip', eeprom.readByte(TEST_ADDR), TEST_VALUE);

const PAGE_ADDR = 0x40;
const PAGE_DATA = Buffer.from([0x11, 0x22, 0x33, 0x44]);
eeprom.writePage(PAGE_ADDR, PAGE_DATA);
checkEqBuf('writePage read-back', eeprom.read(PAGE_ADDR, PAGE_DATA.length), PAGE_DATA);

const CROSS_ADDR = 0x06;
const CROSS_DATA = Buffer.from([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]);
eeprom.write(CROSS_ADDR, CROSS_DATA);
checkEqBuf('cross-page write read-back', eeprom.read(CROSS_ADDR, CROSS_DATA.length), CROSS_DATA);

const RANGE_ADDR = 0x50;
const RANGE_LEN  = 16;
const range = eeprom.read(RANGE_ADDR, RANGE_LEN);
checkTrue('read length', range.length === RANGE_LEN);

const uid2 = eeprom.readUid();
checkEqBuf('uid unchanged after writes', uid2, uid);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
