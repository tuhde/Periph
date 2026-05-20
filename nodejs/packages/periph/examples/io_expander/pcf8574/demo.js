'use strict';
/**
 * PCF8574 demo — button-controlled LED mirror.
 *
 * Hardware:
 *   P0–P3: LEDs (anode → VCC, cathode → pin; active-low)
 *   P4–P7: push buttons (pin → GND when pressed; internal pull-up = high when idle)
 *
 * Reads button nibble every 200 ms and mirrors it inverted to the LED nibble.
 */
const { I2CTransport } = require('periph/src/transport/i2c');
const { Pcf8574Full }  = require('periph/src/chips/io_expander/pcf8574');

// --- Initialise bus and driver ---
// All pins default to inputs (shadow = 0xFF). We immediately write 0xF0
// to drive P0–P3 low (LEDs on) and keep P4–P7 as inputs (buttons).
const transport = new I2CTransport(1, 0x20);                   // Create I2C transport, (bus, addr=0x20)
const chip      = new Pcf8574Full(transport);                   // Create PCF8574 full driver, (transport, addr=0x20)

chip.writePort(0, 0xF0);                                       // Write all 8 pins, (port, mask) → void

// --- Wire interrupt for responsive button detection ---
// Using polling (null) since no GPIO-mapped INT line in this example.
// Replace null with '/sys/class/gpio/gpio5/value' for edge-based delivery.
chip.configureInterrupt(null, (changed) => {                   // Attach interrupt, (intGpioPath, callback) → void
    // flag is handled in the polling interval below
});

// --- Main loop: read buttons → mirror to LEDs ---
console.log('Running — press buttons on P4–P7 to mirror to LEDs on P0–P3');
setInterval(() => {
    const port    = chip.readPort();                            // Read all 8 pins, (port=0) → int bitmask

    const buttons = (port >> 4) & 0x0F;   // bits 4–7: pressed = 0
    const leds    = (~buttons) & 0x0F;    // active-low: pressed → LED on (bit = 0)
    chip.writePort(0, 0xF0 | leds);                             // Write all 8 pins, (port, mask) → void

    const btnStr = [...Array(4)].map((_, i) => (buttons >> i) & 1 ? '.' : 'X').join('');
    const ledStr = [...Array(4)].map((_, i) => (leds    >> i) & 1 ? '*' : ' ').join('');
    console.log(`port=0x${port.toString(16).padStart(2,'0')}  buttons=[${btnStr}]  LEDs=[${ledStr}]`);
}, 200);
