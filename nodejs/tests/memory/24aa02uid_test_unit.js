'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { EEPROM24AA02UIDFull } = require('../../packages/periph/src/chips/memory/_24aa02uid');

const ADDR_UID_BASE = 0xFC;
const ADDR_MFR_CODE = 0xFA;
const ADDR_DEV_CODE = 0xFB;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function bufEquals(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
}

async function main() {
    const connection = new I2CConnectionMock();
    // UID (0xFC-0xFF), MSB first.
    connection.setRegister(ADDR_UID_BASE, [0xAA, 0xBB, 0xCC, 0xDD]);

    const eeprom = new EEPROM24AA02UIDFull(connection);
    checkTrue('init', true);

    checkTrue('read_uid', bufEquals(await eeprom.readUid(), Buffer.from([0xAA, 0xBB, 0xCC, 0xDD])));

    connection.setRegister(0x10, [0x42]);
    checkTrue('read_byte', (await eeprom.readByte(0x10)) === 0x42);

    await eeprom.writeByte(0x10, 0x99);
    checkTrue('write_byte', connection.registers.get(0x10) === 0x99);
    // writeByte() ACK-polls after the data write, so the data write is
    // second-to-last (the ack-poll's writeRead is last).
    const writeByteWrite = connection.writes[connection.writes.length - 2];
    checkTrue('write_byte_issues_write',
        writeByteWrite.length === 2 && writeByteWrite[0] === 0x10 && writeByteWrite[1] === 0x99);

    // Sequential read (0x05-0x08).
    connection.setRegister(0x05, [1, 2, 3, 4]);
    checkTrue('read', bufEquals(await eeprom.read(0x05, 4), Buffer.from([1, 2, 3, 4])));

    await eeprom.writePage(0x08, Buffer.from([10, 20, 30]));
    checkTrue('write_page',
        connection.registers.get(0x08) === 10 &&
        connection.registers.get(0x09) === 20 &&
        connection.registers.get(0x0A) === 30);

    // write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is
    // 0x08-0x0F. Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3
    // bytes, page 0) then [0x08..0x0E] (7 bytes, page 1). Each writePage()
    // call issues a data write followed by an ack-poll writeRead, so the
    // two page-chunk writes are at [-4] and [-2].
    const data10 = Buffer.from(Array.from({ length: 10 }, (_, i) => 100 + i));
    await eeprom.write(0x05, data10);
    const page0Chunk = connection.writes[connection.writes.length - 4];
    const page1Chunk = connection.writes[connection.writes.length - 2];
    checkTrue('write_page0_chunk', bufEquals(page0Chunk, Buffer.from([0x05, 100, 101, 102])));
    checkTrue('write_page1_chunk',
        bufEquals(page1Chunk, Buffer.from([0x08, 103, 104, 105, 106, 107, 108, 109])));
    checkTrue('write_updated_registers',
        connection.registers.get(0x05) === 100 && connection.registers.get(0x06) === 101 &&
        connection.registers.get(0x07) === 102 && connection.registers.get(0x08) === 103 &&
        connection.registers.get(0x0E) === 109);

    connection.setRegister(ADDR_MFR_CODE, [0x29]);
    checkTrue('read_manufacturer_code', (await eeprom.readManufacturerCode()) === 0x29);

    connection.setRegister(ADDR_DEV_CODE, [0x41]);
    checkTrue('read_device_code', (await eeprom.readDeviceCode()) === 0x41);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
