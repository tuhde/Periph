'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Pcf8575Full } = require('../../packages/periph/src/chips/io_expander/pcf8575');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    // PCF8575 has no sub-registers: every transaction is a plain 2-byte
    // read()/write() (Port 0 first, Port 1 second; no register pointer), so
    // I2CConnectionMock's register map is never consulted — reads must be
    // preloaded via queueRead() in the exact order the driver will issue
    // them.
    const connection = new I2CConnectionMock();
    const chip = new Pcf8575Full(connection);
    await flushMicrotasks(); // let the fire-and-forget constructor write (and prev-seed read) finish
    checkTrue('init', true);

    checkTrue('init_writes_ff_ff',
        connection.writes[0].length === 2 && connection.writes[0][0] === 0xFF && connection.writes[0][1] === 0xFF);
    checkTrue('init_shadow', chip._shadow[0] === 0xFF && chip._shadow[1] === 0xFF);

    // readPort(0)/(1): both derived from one 2-byte read.
    connection.queueRead([0x5A, 0xA5]);
    checkTrue('read_port_0', (await chip.readPort(0)) === 0x5A);
    connection.queueRead([0x5A, 0xA5]);
    checkTrue('read_port_1', (await chip.readPort(1)) === 0xA5);

    // writePort(): writes both shadow bytes, preserving the untouched port.
    await chip.writePort(0, 0x3C);
    let last = connection.writes[connection.writes.length - 1];
    checkTrue('write_port_0', last[0] === 0x3C && last[1] === 0xFF);
    await chip.writePort(1, 0x0F);
    last = connection.writes[connection.writes.length - 1];
    checkTrue('write_port_1_preserves_port0', last[0] === 0x3C && last[1] === 0x0F);

    // pin() read on Port 0 and Port 1.
    const pin3 = chip.pin(3);   // Port 0, bit 3
    connection.queueRead([0x08, 0x00]);
    checkTrue('pin_read_port0', (await pin3.read()) === 1);

    const pin11 = chip.pin(11); // Port 1, bit 3
    connection.queueRead([0x00, 0x08]);
    checkTrue('pin_read_port1', (await pin11.read()) === 1);

    // Pin set high/low preserves other shadow bits within the same port.
    await chip.writePort(0, 0xFF);
    await chip.writePort(1, 0xFF);
    await pin3.write(0);
    last = connection.writes[connection.writes.length - 1];
    checkTrue('pin3_off', last[0] === (0xFF & ~0x08 & 0xFF) && last[1] === 0xFF);
    const pin5 = chip.pin(5);
    await pin5.write(0);
    last = connection.writes[connection.writes.length - 1];
    checkTrue('pin5_off_preserves_pin3', last[0] === (0xFF & ~0x08 & ~0x20 & 0xFF) && last[1] === 0xFF);
    await pin11.write(0);
    last = connection.writes[connection.writes.length - 1];
    checkTrue('pin11_off_only_touches_port1',
        last[0] === (0xFF & ~0x08 & ~0x20 & 0xFF) && last[1] === (0xFF & ~0x08 & 0xFF));

    // Direction: setDirection('out') drives low, 'in' releases high.
    const pin0 = chip.pin(0);
    await pin0.setDirection('out');
    last = connection.writes[connection.writes.length - 1];
    checkTrue('set_direction_out_drives_low', (last[0] & 0x01) === 0);
    await pin0.setDirection('in');
    last = connection.writes[connection.writes.length - 1];
    checkTrue('set_direction_in_releases_high', (last[0] & 0x01) === 1);

    // Full: pollInterrupt() compares to the previous 2-byte read and returns
    // the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits 8-15 = Port 1).
    connection.queueRead([0xFF, 0xFF]);
    await chip.pollInterrupt(); // resync _prev to a known value
    connection.queueRead([0xF7, 0xFE]); // Port0 bit3 low, Port1 bit0 low
    checkTrue('poll_interrupt_detects_change', (await chip.pollInterrupt()) === (0x08 | (0x01 << 8)));
    connection.queueRead([0xF7, 0xFE]); // no further change
    checkTrue('poll_interrupt_no_change', (await chip.pollInterrupt()) === 0x00);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
