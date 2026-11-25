'use strict';
/**
 * PCA6408A demo — LED scanner with interrupt-driven button input.
 *
 * Hardware:
 *   P0–P3: four active-low LEDs (anode → VDD(P) via 220 Ω, cathode → P; 0 lights the LED)
 *   P4–P7: four push buttons (pin → GND when pressed) with external pull-ups
 *          (the chip has no internal pull-ups)
 *
 * Runs a scanner pattern on the LEDs. Button presses raise an interrupt; the
 * callback prints the changed-pin mask and the port state and holds the LED
 * matching the pressed button (P4→P0, …) for one second.
 */
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca6408aFull } = require('periph/src/chips/io_expander/pca6408a');

async function main() {
    // --- Initialise bus and driver ---
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pca6408aFull(connection);                // Create PCA6408A full driver, (connection, addr=0x20)
    await chip.ready;

    // --- Configure LEDs and buttons ---
    // P0–P3 are outputs (all LEDs off = 1); P4–P7 stay inputs, a pressed button reads 0.
    await chip.writePort(0, 0xFF);                                  // Turn all LEDs off, (port=0, mask) → Promise<void>
    await chip.setDirection(0xF0);                                  // P0–P3 outputs, P4–P7 inputs, (mask) → Promise<void>

    // --- Subscribe to input changes ---
    // Any button edge raises INT (or the 5 ms poll sees it); the callback reports the changed
    // mask, then holds the matching LED.
    let holdUntil = 0;
    let holdMask = 0x0F;
    await chip.onInterrupt(async (changed) => {                     // Subscribe to INT, (callback, options) → Promise<void>
        const port = await chip.readPort();                         // Read all 8 pins, (port=0) → Promise<int bitmask>
        console.log('button changed=0x' + changed.toString(16) + ' port=0x' + port.toString(16));
        holdMask = ~((changed >> 4) & 0x0F) & 0x0F;
        holdUntil = Date.now() + 1000;
    });

    // --- Scanner loop ---
    let step = 0;
    console.log('Running — press buttons P4–P7 to hold the matching LED');
    const timer = setInterval(async () => {
        const leds = Date.now() < holdUntil ? holdMask : (~(1 << (step % 4)) & 0x0F);
        await chip.writePort(0, 0xF0 | leds);                       // Write all 8 pins, (port=0, mask) → Promise<void>
        const port = await chip.readPort();                         // Read all 8 pins, (port=0) → Promise<int bitmask>
        console.log('pos=' + (step % 4) + '  port=0x' + port.toString(16).padStart(2, '0'));
        step++;
        if (step >= 40) {
            clearInterval(timer);
            await chip.offInterrupt();                              // Unsubscribe, () → Promise<void>
            await chip.writePort(0, 0xFF);                          // Turn all LEDs off, (port=0, mask) → Promise<void>
            await connection.close();
        }
    }, 100);
}

main();
