'use strict';
const { Pcf8575Minimal, Pcf8575Full } = require('../../src/chips/io_expander/pcf8575');

const transport = {
    write: (buf) => { console.log('write', buf); },
    read: (len) => { return Buffer.from([0xFF, 0xFF]); }
};

const chip = new Pcf8575Full(transport, 0x20);                  // Create PCF8575 full driver, (transport, addr=0x20)

const p0 = chip.pin(0);                                          // Get pin proxy, (n=0) → Pin
p0.writeSync(0);                                                // Write pin, (value=0|1) → void
p0.writeSync(1);                                                // Write pin, (value=0|1) → void
const v = p0.readSync();                                         // Read pin, () → 0|1

const port0 = chip.readPort(0);                                 // Read Port 0, (port=0) → int bitmask
const port1 = chip.readPort(1);                                 // Read Port 1, (port=1) → int bitmask

chip.writePort(0, 0b00001111);                                   // Write Port 0, (port=0, mask=int) → void
chip.writePort(1, 0b00001111);                                   // Write Port 1, (port=1, mask=int) → void

const p8 = chip.pin(8);                                         // Get pin proxy, (n=8) → Pin
p8.setDirection('in');                                          // Set direction, (direction='in'|'out', cb) → void
const state = p8.readSync();                                    // Read pin, () → 0|1

chip.configureInterrupt(null, (changed) => {                     // Attach interrupt, (int_gpio_path|null, callback) → void
    console.log('changed:', changed);
});

const changed = chip.clearInterrupt();                          // Read port and return 16-bit changed bitmask, () → int
console.log('v:', v, 'port0:', port0, 'port1:', port1, 'state:', state, 'changed:', changed);