'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { Pcf8575Minimal, Pcf8575Full } = require('../../packages/periph/src/chips/io_expander/pcf8575');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',    10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x20', 16);

let passed = 0;
let failed = 0;

function check_eq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; return; }
    console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++;
}

function check_true(label, condition) {
    if (condition) { console.log('PASS', label); passed++; return; }
    console.log('FAIL', label); failed++;
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const chip      = new Pcf8575Minimal(transport);

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

// Loopback: port 0 (outputs) → port 1 (inputs); P0x ↔ P1(7-x)
chip.writePort(1, 0xFF);

chip.writePort(0, 0xAA);
check_eq('loopback_0xAA', chip.readPort(1), 0x55);

chip.writePort(0, 0xF0);
check_eq('loopback_0xF0', chip.readPort(1), 0x0F);

chip.writePort(0, 0x00);
check_eq('loopback_0x00', chip.readPort(1), 0x00);

chip.writePort(0, 0xFF);
chip.writePort(1, 0xFF);

const full = new Pcf8575Full(transport);
const changed = full.clearInterrupt();
check_true('clear_interrupt_range', changed >= 0 && changed <= 0xFFFF);

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed ? 1 : 0);
