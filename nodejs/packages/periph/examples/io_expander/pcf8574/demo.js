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
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pcf8574Full }   = require('periph/src/chips/io_expander/pcf8574');

async function main() {
    // --- Initialise bus and driver ---
    // All pins default to inputs (shadow = 0xFF). We immediately write 0xF0
    // to drive P0–P3 low (LEDs on) and keep P4–P7 as inputs (buttons).
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pcf8574Full(connection);                 // Create PCF8574 full driver, (connection, addr=0x20)

    await chip.writePort(0, 0xF0);                                  // Write all 8 pins, (port, mask) → Promise<void>

    // --- Wire interrupt for responsive button detection ---
    // No options.intPin wired in this example, so onInterrupt() falls back
    // to a 5 ms polling loop automatically. Pass { intPin } with a real
    // InputPin (e.g. EpollInputPin) for edge-based delivery instead.
    await chip.onInterrupt((changed) => {                           // Subscribe to INT line, (callback, options) → Promise<void>
        // flag is handled in the polling interval below
    });

    // --- Main loop: read buttons → mirror to LEDs ---
    console.log('Running — press buttons on P4–P7 to mirror to LEDs on P0–P3');
    setInterval(async () => {
        const port    = await chip.readPort();                      // Read all 8 pins, (port=0) → Promise<int bitmask>

        const buttons = (port >> 4) & 0x0F;   // bits 4–7: pressed = 0
        const leds    = (~buttons) & 0x0F;    // active-low: pressed → LED on (bit = 0)
        await chip.writePort(0, 0xF0 | leds);                       // Write all 8 pins, (port, mask) → Promise<void>

        const btnStr = [...Array(4)].map((_, i) => (buttons >> i) & 1 ? '.' : 'X').join('');
        const ledStr = [...Array(4)].map((_, i) => (leds    >> i) & 1 ? '*' : ' ').join('');
        console.log(`port=0x${port.toString(16).padStart(2, '0')}  buttons=[${btnStr}]  LEDs=[${ledStr}]`);
    }, 200);
}

main();
