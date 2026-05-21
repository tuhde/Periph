'use strict';
const { I2CTransport } = require('periph/src/transport/i2c');
const { Mcp23017Minimal } = require('periph/src/chips/io_expander/mcp23017');

const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Mcp23017Minimal(transport);                // Create MCP23017 driver, (transport, addr=0x20)

const p0 = chip.pin(0, 'out');                                  // Get pin proxy, (n, direction='out') → Pin
const p8 = chip.pin(8, 'in');                                   // Get pin proxy, (n, direction='in') → Pin

setInterval(() => {
    const porta = chip.readPort(0);                              // Read all 8 pins, (port=0) → int bitmask
    const portb = chip.readPort(1);                              // Read all 8 pins, (port=1) → int bitmask
    p0.writeSync((porta >> 1) & 1);                             // Write pin, (value=0|1) → void
    console.log('PORTA=0x' + porta.toString(16).padStart(2,'0') + '  PORTB=0x' + portb.toString(16).padStart(2,'0'));
}, 200);