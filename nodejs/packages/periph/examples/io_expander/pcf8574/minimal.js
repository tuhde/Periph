'use strict';
const { I2CTransport }    = require('periph/src/transport/i2c');
const { Pcf8574Minimal }  = require('periph/src/chips/io_expander/pcf8574');

const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Pcf8574Minimal(transport);                // Create PCF8574 driver, (transport, addr=0x20)

const p0 = chip.pin(0, 'out');                                  // Get pin proxy, (n, direction='out') → Pin
const p4 = chip.pin(4, 'in');                                   // Get pin proxy, (n, direction='in') → Pin

setInterval(() => {
    const port = chip.readPort();                               // Read all 8 pins, (port=0) → int bitmask
    const btn  = (port >> 4) & 1;
    p0.writeSync(btn ? 1 : 0);                                  // Write pin, (value=0|1) → void
    console.log('port=0x' + port.toString(16).padStart(2, '0'));
}, 200);
