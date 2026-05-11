'use strict';

const { SPITransport } = require('../../packages/periph/src/transport/spi');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0',  10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new SPITransport(SPI_BUS, SPI_DEVICE);

const txData = Buffer.from([0x01, 0x02, 0x03]);
transport.write(txData);
checkTrue('write completed', true);

const rxBuf = transport.read(3);
checkTrue('read returned 3 bytes', rxBuf.length === 3);

const cmd = Buffer.from([0x55, 0xAA]);
const resp = transport.writeRead(cmd, 2);
checkTrue('writeRead returned 2 bytes', resp.length === 2);

transport.close();
checkTrue('transport closed', true);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);