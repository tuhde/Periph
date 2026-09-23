'use strict';

// Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
// -5 °C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; the callback reports which boundary tripped. After a fixed number
// of alerts the monitor disables the Alert output and unsubscribes.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP9808Full } = require('periph/src/chips/temperature/mcp9808');

const MAX_ALERTS = 10;

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP9808Full.I2C_ADDRESS);
    const sensor = new MCP9808Full(connection);                                    // Create MCP9808 Full driver, (connection)
    await sensor.init();                                                           // Confirm device identity, () → None

    // --- Trade resolution for faster sampling ---
    // 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    await sensor.setResolution(0.25);                                              // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → None

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
    await sensor.setLowerLimit(-25.0);                                             // Set TLOWER, (celsius °C) → None
    await sensor.setUpperLimit(-15.0);                                             // Set TUPPER, (celsius °C) → None
    await sensor.setCriticalLimit(-5.0);                                           // Set TCRIT, (celsius °C) → None
    await sensor.setHysteresis(3.0);                                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → None

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clearInterrupt(), so a short
    // excursion is never missed between two reads.
    await sensor.configureAlert('all', 'interrupt', 'active_low');                 // Configure Alert, (mode='all', output='comparator', polarity='active_low') → None
    await sensor.enableAlert();                                                    // Enable Alert output, () → None

    let alerts = 0;
    let done;
    const finished = new Promise((r) => { done = r; });

    // --- Report which boundary tripped, then re-arm ---
    // The status mask is a live read of TA's boundary bits; an empty mask
    // means the temperature has come back inside the healthy window.
    await sensor.onInterrupt(async (status) => {                                   // Subscribe to Alert, (callback) → None
        const t = await sensor.readTemperature();                                  // Read ambient temperature, () → number °C
        if (status & MCP9808Full.SOURCE_CRITICAL) console.log(`${t.toFixed(2)} °C  CRITICAL — door open?`);
        else if (status & MCP9808Full.SOURCE_UPPER) console.log(`${t.toFixed(2)} °C  too warm`);
        else if (status & MCP9808Full.SOURCE_LOWER) console.log(`${t.toFixed(2)} °C  too cold`);
        else console.log(`${t.toFixed(2)} °C  back in range`);
        await sensor.clearInterrupt();                                             // Clear interrupt-mode Alert, () → None
        if (++alerts >= MAX_ALERTS) done();
    });
    console.log(`monitoring, ${(await sensor.readTemperature()).toFixed(2)} °C now`);  // Read ambient temperature, () → number °C
    await finished;

    // --- Shut down cleanly after the demo run ---
    await sensor.disableAlert();                                                   // Disable Alert output, () → None
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
    await connection.close();
})();
