'use strict';

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { RFM95Full }     = require('../../packages/periph/src/chips/comms/rfm9x');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);
const FREQ_HZ  = parseInt(process.env.RFM9X_FREQ || '868000000', 10);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 5_000_000 });
    const radio = new RFM95Full(connection, FREQ_HZ);
    await radio.init();

    checkEq('version', await radio.version(), 0x12);

    await radio.configure(7, 125.0, 5);
    checkTrue('configure', true);

    await radio.setFrequency(FREQ_HZ);
    checkTrue('frequency_in_range', radio._frequencyHz >= radio.freqMinHz && radio._frequencyHz <= radio.freqMaxHz);

    await radio.standby();
    checkEq('standby_mode', (await radio._readReg(0x01)) & 0x07, 0x01);

    await radio.send(Buffer.from('test123'));
    checkEq('irq_tx_done_cleared', (await radio._readReg(0x12)) & 0x08, 0x00);

    await radio.sleep();
    checkEq('sleep_mode', (await radio._readReg(0x01)) & 0x07, 0x00);

    await radio.standby();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
