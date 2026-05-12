'use strict';

const { SPITransport } = require('../../packages/periph/src/transport/spi');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);
const SPI_SPEED  = parseInt(process.env.SPI_SPEED  || '1000000', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new SPITransport(SPI_BUS, SPI_DEVICE, { maxSpeedHz: SPI_SPEED });

transport.write(Buffer.from([0x00]));
checkTrue('write accepted', true);

const readBuf = transport.read(1);
checkTrue('read returns 1 byte', readBuf.length === 1);

const wrBuf = transport.writeRead(Buffer.from([0x00]), 1);
checkTrue('writeRead returns 1 byte', wrBuf.length === 1);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
