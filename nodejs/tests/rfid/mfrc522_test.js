'use strict';

const { SPITransport } = require('../../packages/periph/src/transport/spi');
const { MFRC522Full }   = require('../../packages/periph/src/chips/rfid/mfrc522');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0',  10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0',  10);
const SPI_SPEED  = parseInt(process.env.SPI_SPEED  || '1000000', 10);

let passed = 0, failed = 0;

function checkTrue(label, cond) {
    if (cond) { console.log('PASS', label); passed++; }
    else      { console.log('FAIL', label); failed++; }
}

const transport = new SPITransport(SPI_BUS, SPI_DEVICE, { maxSpeedHz: SPI_SPEED, mode: 0 });
const mfrc = new MFRC522Full(transport);

const v = mfrc.version();
checkTrue('chipType == 0x09 (MFRC522)', v.chipType === 0x09);
checkTrue('version in {1, 2}', v.version === 1 || v.version === 2);

mfrc.antennaOn();
checkTrue('antennaOn sets TxControlReg bits 0|1', (mfrc._readReg(0x14) & 0x03) === 0x03);
mfrc.antennaOff();
checkTrue('antennaOff clears TxControlReg bits 0|1', (mfrc._readReg(0x14) & 0x03) === 0x00);
mfrc.antennaOn();

for (const dB of [18, 23, 33, 38, 43, 48]) {
    mfrc.setAntennaGain(dB);
    checkTrue(`setAntennaGain(${dB}) read back == ${dB}`, mfrc.antennaGain() === dB);
}

const present = mfrc.isCardPresent();
checkTrue('isCardPresent returns bool', typeof present === 'boolean');

const raw = mfrc._readReg(0x37);
checkTrue('raw VersionReg in 0x90/0x91/0x92', raw === 0x90 || raw === 0x91 || raw === 0x92);

transport.close();
console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
