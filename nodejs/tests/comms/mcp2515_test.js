'use strict';

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { MCP2515Full }    = require('../../packages/periph/src/chips/comms/mcp2515');

const SPI_BUS = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV  || '0', 10);
const BITRATE = parseInt(process.env.MCP2515_BITRATE || '125', 10);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}`); failed++; }
}

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 10_000_000 });
    const chip = new MCP2515Full(connection, BITRATE);

    checkEq('init_mode', await chip.getMode(), 'normal');

    await chip.setMode('loopback');
    checkEq('loopback_mode', await chip.getMode(), 'loopback');

    await chip.setMode('normal');
    checkEq('normal_mode_after_loopback', await chip.getMode(), 'normal');

    await chip.send(0x7FF, Buffer.from([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08]), false);
    const frame1 = await chip.recv(100);
    checkTrue('recv_standard', frame1 !== null && frame1.id === 0x7FF);

    await chip.send(0x1FFFFFFF, Buffer.from([0xAA, 0xBB, 0xCC, 0xDD]), true);
    const frame2 = await chip.recv(100);
    checkTrue('recv_extended', frame2 !== null && frame2.id === 0x1FFFFFFF && frame2.extended === true);

    const errors = await chip.readErrors();
    checkTrue('read_errors', typeof errors.tec === 'number' && typeof errors.rec === 'number' && typeof errors.eflg === 'number');

    await chip.setOneShot(true);
    await chip.setOneShot(false);

    await chip.setFilter(0, 0x100, false);
    await chip.setMask(0, 0x7FF, false);
    await chip.setRxMode(0, 0);

    await chip.reset();
    await chip.init(BITRATE, 8);
    checkEq('init_after_reset', await chip.getMode(), 'normal');

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
