'use strict';
const { I2CConnection } = require('periph/src/connection/i2c');
const { Mcp23017Minimal } = require('periph/src/chips/io_expander/mcp23017');

async function main() {
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Mcp23017Minimal(connection);             // Create MCP23017 driver, (connection, addr=0x20)

    const p0 = chip.pin(0, 'out');                                  // Get pin proxy, (n, direction='out') → Pin
    const p8 = chip.pin(8, 'in');                                   // Get pin proxy, (n, direction='in') → Pin

    setInterval(async () => {
        const porta = await chip.readPort(0);                       // Read all 8 pins, (port=0) → Promise<int bitmask>
        const portb = await chip.readPort(1);                       // Read all 8 pins, (port=1) → Promise<int bitmask>
        await p0.write((porta >> 1) & 1);                           // Write pin, (value=0|1) → Promise<void>
        console.log('PORTA=0x' + porta.toString(16).padStart(2, '0') + '  PORTB=0x' + portb.toString(16).padStart(2, '0'));
    }, 200);
}

main();
