'use strict';
const { Pcf8575Minimal } = require('../../src/chips/io_expander/pcf8575');

const transport = {                                              // Mock transport for example
    write: (buf) => { console.log('write', buf); },
    read: (len) => { return Buffer.from([0xFF, 0xFF]); }
};

const chip = new Pcf8575Minimal(transport, 0x20);               // Create PCF8575 driver, (transport, addr=0x20)

const p0 = chip.pin(0);                                          // Get pin proxy, (n=0) → Pin
const p8 = chip.pin(8);                                          // Get pin proxy, (n=8) → Pin

p0.writeSync(0);                                                // Write pin, (value=0|1) → void
const v = p0.readSync();                                        // Read pin, () → 0|1
console.log('pin0 value:', v);

const port0 = chip.readPort(0);                                  // Read Port 0, (port=0) → int bitmask
const port1 = chip.readPort(1);                                  // Read Port 1, (port=1) → int bitmask
console.log('port0:', port0, 'port1:', port1);