'use strict';
const { I2CTransport }              = require('../packages/periph/src/transport/i2c');
const { Pcf8574Minimal, Pcf8574Full } = require('../packages/periph/src/chips/io_expander/pcf8574');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',    10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x20', 16);

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

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const chip      = new Pcf8574Minimal(transport);

checkEq('init_shadow', chip._shadow, 0xFF);

const port = chip.readPort();
checkTrue('read_port_range', port >= 0 && port <= 0xFF);

chip.writePort(0, 0xAA);
checkEq('write_port_shadow', chip._shadow, 0xAA);
chip.writePort(0, 0xFF);

const p0 = chip.pin(0, 'out');
p0.writeSync(0);
checkEq('write_low_shadow', chip._shadow & 0x01, 0);
p0.writeSync(1);
checkEq('write_high_shadow', chip._shadow & 0x01, 1);

const v = p0.readSync();
checkTrue('read_range', v === 0 || v === 1);

p0.read((err, val) => {
    checkTrue('read_async_no_error', !err);
    checkTrue('read_async_range', val === 0 || val === 1);
});

p0.write(0, (err) => checkTrue('write_async_no_error', !err));
checkEq('direction_prop', p0.direction, 'out');

const p4 = chip.pin(4, 'in');
checkEq('input_shadow_bit4', (chip._shadow >> 4) & 1, 1);
checkEq('input_direction', p4.direction, 'in');

// Full
const full = new Pcf8574Full(transport);
checkTrue('full_init_shadow', full._shadow === 0xFF);

const changed = full.clearInterrupt();
checkTrue('clear_interrupt_range', changed >= 0 && changed <= 0xFF);

// polling-mode configureInterrupt
full.configureInterrupt(null, () => {});
checkTrue('poll_timer_set', full._pollTimer !== null);
clearInterval(full._pollTimer);
full._pollTimer = null;

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed ? 1 : 0);
