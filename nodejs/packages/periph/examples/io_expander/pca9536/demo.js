'use strict';
/**
 * PCA9536 demo — blinking LEDs with polled button input.
 *
 * Hardware:
 *   P0–P1: two active-low LEDs (anode → VCC via 220 Ω, cathode → P; 0 lights the LED, sinks up to 25 mA)
 *   P2–P3: two push buttons (pin → GND when pressed); the chip's built-in weak
 *          pull-up holds an open input high, so no external resistor is needed
 *
 * The LEDs blink alternately. The chip has no INT pin, so a 5 ms polling
 * timer reports button changes; the callback prints the changed-pin mask and
 * the port state and holds the LED matching the pressed button (P2→P0, P3→P1)
 * for one second.
 */
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca9536Full } = require('periph/src/chips/io_expander/pca9536');

async function main() {
    // --- Initialise bus and driver ---
    const connection = new I2CConnection(1, 0x41);                 // Create I2C connection, (bus, addr=0x41)
    const chip       = new Pca9536Full(connection);                 // Create PCA9536 full driver, (connection, addr=0x41)
    await chip.ready;

    // --- Configure LEDs and buttons ---
    // P0–P1 are outputs (LED off = 1); P2–P3 stay inputs, a pressed button reads 0.
    await chip.writePort(0, 0x0F);                                  // Turn all LEDs off, (port=0, mask) → Promise<void>
    await chip.setDirection(0x0C);                                  // P0–P1 outputs, P2–P3 inputs, (mask) → Promise<void>

    // --- Watch input changes ---
    // The polling timer compares INPUT with the previous value; the callback
    // reports the changed mask, then holds the matching LED.
    let holdUntil = 0;
    let holdMask = 0x03;
    chip.watchChanges(async (changed) => {                          // Start polling for changes, (callback, intervalS=0.005) → void
        const port = await chip.readPort();                         // Read all 4 pins, (port=0) → Promise<int bitmask>
        console.log('button changed=0x' + changed.toString(16) + ' port=0x' + port.toString(16));
        holdMask = ~((changed >> 2) & 0x03) & 0x03;
        holdUntil = Date.now() + 1000;
    });

    // --- Blink loop ---
    let step = 0;
    console.log('Running — press buttons P2–P3 to hold the matching LED');
    const timer = setInterval(async () => {
        const leds = Date.now() < holdUntil ? holdMask : (step % 2 === 0 ? 0x02 : 0x01);
        await chip.writePort(0, 0x0C | leds);                       // Write all 4 pins, (port=0, mask) → Promise<void>
        step++;
        if (step >= 40) {
            clearInterval(timer);
            chip.unwatchChanges();                                  // Stop polling, () → void
            await chip.writePort(0, 0x0F);                          // Turn all LEDs off, (port=0, mask) → Promise<void>
            await connection.close();
        }
    }, 200);
}

main();
