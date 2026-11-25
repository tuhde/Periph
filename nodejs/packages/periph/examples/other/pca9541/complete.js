'use strict';

// Complete example: exercise every method in the public API.
// Reads the registers, takes the bus (with and without bus initialization),
// disconnects and reconnects the downstream bus, programs the interrupt masks,
// runs the INT functional test and subscribes to this master's interrupt.

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCA9541Full } = require('periph/src/chips/other/pca9541');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, PCA9541Full.I2C_ADDRESS);
    const selector = new PCA9541Full(connection);                           // Create PCA9541 Full driver, (connection)
    await selector.init();                                                  // Run initialization sequence, () → void (reads CONTROL and ISTAT; writes nothing)

    console.log(await selector.getControl());                               // Read the CONTROL byte, () → int
    console.log(await selector.status());                                   // Decode CONTROL, () → {hasControl, connected, busInitRequested, ownTest, otherTest}
    console.log(await selector.readAll());                                  // Read IE, CONTROL, ISTAT, () → {ie, control, istat}
    // one auto-increment read; clears the ISTAT flags

    console.log(await selector.acquire());                                  // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    console.log(await selector.recoverBus());                               // Recover and take the bus, (timeoutMs=20) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    console.log(await selector.hasControl());                               // Report control, () → bool
    console.log(await selector.isConnected());                              // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    await selector.disconnect();                                            // Isolate the downstream bus, () → void
    // BUSON = NBUSON; ownership bits are left
    await selector.connect();                                               // Reconnect without changing ownership, () → void

    await selector.setInterruptMask({ busNotIdle: true });                  // Write IE, ({busLost, busNotIdle, busInit, intIn}) → void
    // true = masked, no interrupt
    console.log(await selector.getInterruptMask());                         // Read IE, () → int
    await selector.disableInterrupt(PCA9541Full.SOURCE_INT_IN);             // Mask one source, (source) → void
    await selector.enableInterrupt(PCA9541Full.SOURCE_INT_IN);              // Unmask one source, (source) → void

    await selector.onInterrupt((status) => console.log('interrupt', status));// Subscribe, (callback(status), {intPin}) → void
    // falling edge on this master's INT, or a 100 ms poll without a pin
    await selector.testInterrupt(true);                                     // Drive this master's INT low, (own=false, other=false) → void
    // the bus bits are written back unchanged
    await sleep(200);
    await selector.testInterrupt();                                         // Clear the test, () → void
    console.log(await selector.pollInterrupt());                            // Read and clear ISTAT, () → int
    await selector.offInterrupt();                                          // Unsubscribe, () → void
    await selector.release();                                               // Give the bus to the other master, () → void

    await connection.close();
})();
