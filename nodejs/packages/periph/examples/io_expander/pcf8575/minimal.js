'use strict';
const { Pcf8575Minimal } = require('../../src/chips/io_expander/pcf8575');

const connection = {                                             // Mock connection for example
    async write(buf) { console.log('write', buf); },
    async read(len) { return Buffer.from([0xFF, 0xFF]); }
};

async function main() {
    const chip = new Pcf8575Minimal(connection, 0x20);          // Create PCF8575 driver, (connection, addr=0x20)

    const p0 = chip.pin(0);                                      // Get pin proxy, (n=0) → Pin
    const p8 = chip.pin(8);                                      // Get pin proxy, (n=8) → Pin

    await p0.write(0);                                           // Write pin, (value=0|1) → Promise<void>
    const v = await p0.read();                                   // Read pin, () → Promise<0|1>
    console.log('pin0 value:', v);

    const port0 = await chip.readPort(0);                        // Read Port 0, (port=0) → Promise<int bitmask>
    const port1 = await chip.readPort(1);                        // Read Port 1, (port=1) → Promise<int bitmask>
    console.log('port0:', port0, 'port1:', port1);
}

main();
