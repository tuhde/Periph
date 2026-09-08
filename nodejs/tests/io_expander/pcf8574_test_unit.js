'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Pcf8574Full } = require('../../packages/periph/src/chips/io_expander/pcf8574');

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
    // PCF8574 has no sub-registers: every transaction is a single plain
    // byte read()/write() (no register pointer), so I2CConnectionMock's
    // register map is never consulted — reads must be preloaded via
    // queueRead() in the exact order the driver will issue them.
    const connection = new I2CConnectionMock();
    const chip = new Pcf8574Full(connection);
    await flushMicrotasks(); // let the fire-and-forget constructor write (and prev-seed read) finish
    checkTrue('init', true);

    // Construction writes 0xFF (all pins to quasi-bidirectional input mode).
    checkTrue('init_writes_0xff', connection.writes[0].length === 1 && connection.writes[0][0] === 0xFF);
    checkTrue('init_shadow', chip._shadow === 0xFF);

    // readPort(): plain single-byte read.
    connection.queueRead([0x5A]);
    checkTrue('read_port', (await chip.readPort()) === 0x5A);

    // writePort(): plain single-byte write; updates shadow.
    await chip.writePort(0, 0x3C);
    checkTrue('write_port', connection.writes[connection.writes.length - 1][0] === 0x3C);
    checkTrue('write_port_shadow', chip._shadow === 0x3C);

    // pin().read() reads the live bus level (not the shadow).
    const pin3 = chip.pin(3);
    connection.queueRead([0x08]); // bit 3 high
    checkTrue('pin_read', (await pin3.read()) === 1);

    // Pin set high/low preserves other shadow bits (read-modify-write).
    await chip.writePort(0, 0xFF);
    await pin3.write(0);
    checkTrue('pin3_off', connection.writes[connection.writes.length - 1][0] === (0xFF & ~0x08 & 0xFF));
    const pin5 = chip.pin(5);
    await pin5.write(0);
    checkTrue('pin5_off_preserves_pin3', connection.writes[connection.writes.length - 1][0] === (0xFF & ~0x08 & ~0x20 & 0xFF));
    await pin3.write(1);
    checkTrue('pin3_on_preserves_pin5', connection.writes[connection.writes.length - 1][0] === (0xFF & ~0x20 & 0xFF));

    // Toggle is manual (no toggle() method on Node's Pin) — set/read directly.
    // setDirection('in') releases high; setDirection('out') would drive low
    // without a value, but the driver's write() is what actually drives level.
    await pin3.setDirection('out');
    checkTrue('set_direction_out', pin3.direction === 'out');
    await pin3.setDirection('in');
    checkTrue('set_direction_in_releases_high', connection.writes[connection.writes.length - 1][0] & 0x08);

    // Full: pollInterrupt() compares to the previous read and returns the
    // changed-pin bitmask, also updating the stored previous value.
    connection.queueRead([0xFF]);
    await chip.pollInterrupt(); // resync _prev to a known value (0xFF)
    connection.queueRead([0xF7]); // bit 3 now low
    checkTrue('poll_interrupt_detects_change', (await chip.pollInterrupt()) === 0x08);
    connection.queueRead([0xF7]); // no further change
    checkTrue('poll_interrupt_no_change', (await chip.pollInterrupt()) === 0x00);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
