'use strict';
const { Pcf8575Minimal, Pcf8575Full } = require('../../periph/src/chips/io_expander/pcf8575');

const passed = 0;
const failed = 0;

function check_eq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); return; }
    console.log('FAIL', label, ': got', got, 'expected', expected);
}

function check_true(label, condition) {
    if (condition) { console.log('PASS', label); return; }
    console.log('FAIL', label);
}

const transport = {
    write: (buf) => { /* mock */ },
    read: (len) => { return Buffer.from([0xFF, 0xFF]); }
};

const chip = new Pcf8575Minimal(transport);
check_eq('init_shadow_0', chip._shadow[0], 0xFF);
check_eq('init_shadow_1', chip._shadow[1], 0xFF);

const port0 = chip.readPort(0);
const port1 = chip.readPort(1);
check_true('read_port_0_range', port0 >= 0 && port0 <= 0xFF);
check_true('read_port_1_range', port1 >= 0 && port1 <= 0xFF);

chip.writePort(0, 0xAA);
check_eq('write_port_0_shadow', chip._shadow[0], 0xAA);
chip.writePort(1, 0x55);
check_eq('write_port_1_shadow', chip._shadow[1], 0x55);
chip.writePort(0, 0xFF);
chip.writePort(1, 0xFF);

const p0 = chip.pin(0);
p0.writeSync(0);
check_eq('pin_write0_shadow', chip._shadow[0] & 0x01, 0);
p0.writeSync(1);
check_eq('pin_write1_shadow', chip._shadow[0] & 0x01, 1);

chip.writePort(0, 0xFF);
chip.writePort(1, 0xFF);

const full = new Pcf8575Full(transport);
const changed = full.clearInterrupt();
check_true('clear_interrupt_range', changed >= 0 && changed <= 0xFFFF);

console.log('===DONE: passed, failed===');