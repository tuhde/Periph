'use strict';
/**
 * PCA6416A demo — LED scanner with interrupt-driven button input.
 *
 * Hardware:
 *   P0_0–P0_7: eight active-low LEDs (anode → VDD(P) via 220 Ω, cathode → P; 0 lights the LED)
 *   P1_0–P1_7: eight push buttons (pin → GND when pressed) with external pull-ups
 *              (the chip has no internal pull-ups)
 *
 * Runs a scanner pattern on the LEDs. Button presses raise an interrupt; the
 * callback prints the 16-bit changed-pin mask and the pin state and holds the
 * LED matching the pressed button (P1_0→P0_0, …) for one second.
 */
const { I2CConnection } = require('periph/src/connection/i2c');
const { Pca6416aFull } = require('periph/src/chips/io_expander/pca6416a');

async function main() {
    // --- Initialise bus and driver ---
    const connection = new I2CConnection(1, 0x20);                 // Create I2C connection, (bus, addr=0x20)
    const chip       = new Pca6416aFull(connection);                // Create PCA6416A full driver, (connection, addr=0x20)
    await chip.ready;

    // --- Configure LEDs and buttons ---
    // Port 0 holds the LED outputs (all LEDs off = 1); port 1 stays input, a pressed button
    // reads 0 — polarity inversion makes it read 1.
    await chip.writeAll(0xFFFF);                                    // Turn all LEDs off, (value) → Promise<void>
    await chip.setDirection(0xFF00);                                // Port 0 outputs, port 1 inputs, (mask) → Promise<void>
    await chip.configurePolarity(0xFF00);                           // Invert port 1 inputs, (mask) → Promise<void>

    // --- Subscribe to input changes ---
    // Any button edge raises INT (or the 5 ms poll sees it); the callback reports the changed
    // mask, then holds the matching LED.
    let holdUntil = 0;
    let holdMask = 0xFF;
    await chip.onInterrupt(async (changed) => {                     // Subscribe to INT, (callback, options) → Promise<void>
        const all = await chip.readAll();                           // Read all 16 pins, () → Promise<int>
        console.log('button changed=0x' + changed.toString(16) + ' all=0x' + all.toString(16));
        holdMask = ~((changed >> 8) & 0xFF) & 0xFF;
        holdUntil = Date.now() + 1000;
    });

    // --- Scanner loop ---
    let step = 0;
    console.log('Running — press buttons P1_0–P1_7 to hold the matching LED');
    const timer = setInterval(async () => {
        const leds = Date.now() < holdUntil ? holdMask : (~(1 << (step % 8)) & 0xFF);
        await chip.writeAll(0xFF00 | leds);                         // Write all 16 pins, (value) → Promise<void>
        const all = await chip.readAll();                           // Read all 16 pins, () → Promise<int>
        console.log('pos=' + (step % 8) + '  all=0x' + all.toString(16).padStart(4, '0'));
        step++;
        if (step >= 80) {
            clearInterval(timer);
            await chip.offInterrupt();                              // Unsubscribe, () → Promise<void>
            await chip.writeAll(0xFFFF);                            // Turn all LEDs off, (value) → Promise<void>
            await connection.close();
        }
    }, 100);
}

main();
