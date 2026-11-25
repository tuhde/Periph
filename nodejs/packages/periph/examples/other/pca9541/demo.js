'use strict';

// Demo: master failover for one of two redundant masters. Prints the power-up
// registers (which reveal the /01 or /03 version), unmasks all interrupts, takes
// the downstream bus with a bus initialization, checks the INT wire with the
// functional test, hands the bus over, takes it back, toggles the connection and
// prints the ISTAT flags every 500 ms for 10 s.

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCA9541Full } = require('periph/src/chips/other/pca9541');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, PCA9541Full.I2C_ADDRESS);
    const selector = new PCA9541Full(connection);                           // Create PCA9541 Full driver, (connection)
    await selector.init();                                                  // Run initialization sequence, () → void (reads CONTROL and ISTAT; writes nothing)

    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    console.log('IE, CONTROL, ISTAT:', await selector.readAll());           // Read IE, CONTROL, ISTAT, () → {ie, control, istat}
    console.log('status:', await selector.status());                        // Decode CONTROL, () → {...}

    // --- Interrupts ---
    await selector.setInterruptMask();                                      // Unmask all interrupts, ({...}) → void
    await selector.onInterrupt((status) => {                                // Subscribe, (callback(status), {intPin}) → void
        if (status & PCA9541Full.SOURCE_BUS_LOST) console.log('BUS_LOST: the other master took the bus');
        if (status & PCA9541Full.SOURCE_TEST) console.log('MYTEST: the INT wire works');
        console.log('ISTAT', status.toString(16));
    });

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    console.log('recoverBus', await selector.recoverBus());                 // Recover and take the bus, (timeoutMs=20) → bool
    await sleep(10);
    // tBUF: bus-free time before talking to a downstream device through a second Connection

    // --- INT wire check ---
    await selector.testInterrupt(true);                                     // Drive this master's INT low, (own=false, other=false) → void
    await sleep(100);
    await selector.testInterrupt();                                         // Clear the test, () → void

    // --- Hand over and take back ---
    await selector.release();                                               // Give the bus to the other master, () → void
    console.log('has control after release', await selector.hasControl());  // Report control, () → bool
    console.log('acquire', await selector.acquire());                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    await selector.disconnect();                                            // Isolate the downstream bus, () → void
    await selector.connect();                                               // Reconnect, () → void

    // --- Watch the flags for 10 s ---
    for (let i = 0; i < 20; i++) {
        console.log('ISTAT', (await selector.pollInterrupt()).toString(16));// Read and clear ISTAT, () → int
        await sleep(500);
    }

    await selector.offInterrupt();                                          // Unsubscribe, () → void

    await connection.close();
})();
