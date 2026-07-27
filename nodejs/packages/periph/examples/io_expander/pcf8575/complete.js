'use strict';
const { Pcf8575Minimal, Pcf8575Full } = require('../../src/chips/io_expander/pcf8575');

const connection = {
    async write(buf) { console.log('write', buf); },
    async read(len) { return Buffer.from([0xFF, 0xFF]); }
};

async function main() {
    const chip = new Pcf8575Full(connection, 0x20);             // Create PCF8575 full driver, (connection, addr=0x20)

    const p0 = chip.pin(0);                                      // Get pin proxy, (n=0) → Pin
    await p0.write(0);                                           // Write pin, (value=0|1) → Promise<void>
    await p0.write(1);                                           // Write pin, (value=0|1) → Promise<void>
    const v = await p0.read();                                   // Read pin, () → Promise<0|1>

    const port0 = await chip.readPort(0);                        // Read Port 0, (port=0) → Promise<int bitmask>
    const port1 = await chip.readPort(1);                        // Read Port 1, (port=1) → Promise<int bitmask>

    await chip.writePort(0, 0b00001111);                         // Write Port 0, (port=0, mask=int) → Promise<void>
    await chip.writePort(1, 0b00001111);                         // Write Port 1, (port=1, mask=int) → Promise<void>

    const p8 = chip.pin(8);                                      // Get pin proxy, (n=8) → Pin
    await p8.setDirection('in');                                 // Set direction, (direction='in'|'out') → Promise<void>
    const state = await p8.read();                                // Read pin, () → Promise<0|1>

    await chip.onInterrupt((changed) => {                        // Subscribe to INT line, (callback, options) → Promise<void>
        console.log('changed:', changed);
    });

    const changed = await chip.pollInterrupt();                  // Read port and return 16-bit changed bitmask, () → Promise<int>
    console.log('v:', v, 'port0:', port0, 'port1:', port1, 'state:', state, 'changed:', changed);
}

main();
