'use strict';

// Exercises every method in the MCP9808 public API: temperature, resolution,
// Shutdown mode, the three boundaries and hysteresis, the Alert output, and
// the boundary-status interrupt API. The one-way lock methods are shown but
// left commented out — they cannot be undone without a power-on reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP9808Full } = require('periph/src/chips/temperature/mcp9808');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MCP9808Full.I2C_ADDRESS);
    const sensor = new MCP9808Full(connection);                                    // Create MCP9808 Full driver, (connection)
                                                                                   // starts the MANUFACTURER_ID / DEVICE_ID check
    await sensor.init();                                                           // Confirm device identity, () → None
                                                                                   // rejects if the IDs are not 0x0054 / 0x04

    const t = await sensor.readTemperature();                                      // Read ambient temperature, () → number °C
                                                                                   // masks TA's 3 status bits, decodes 1/16 °C two's complement
    console.log(`temperature ${t.toFixed(4)} °C`);

    await sensor.setResolution(0.25);                                              // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → None
                                                                                   // 0.25 °C step converts in ~65 ms instead of 250 ms
    console.log('resolution', await sensor.getResolution(), '°C');                 // Read resolution, () → number °C
                                                                                   // decodes the RESOLUTION register code

    await sensor.shutdown();                                                       // Enter Shutdown mode, () → None
                                                                                   // stops conversion; TA keeps its last value
    console.log('shutdown', await sensor.isShutdown());                            // Check Shutdown mode, () → boolean
                                                                                   // reads CONFIG.SHDN
    await sensor.wake();                                                           // Leave Shutdown mode, () → None
                                                                                   // resumes continuous conversion
    await sleep(100);

    await sensor.setUpperLimit(30.0);                                              // Set TUPPER, (celsius °C) → None
                                                                                   // rounded to the nearest 0.25 °C step
    await sensor.setLowerLimit(10.0);                                              // Set TLOWER, (celsius °C) → None
                                                                                   // rounded to the nearest 0.25 °C step
    await sensor.setCriticalLimit(45.0);                                           // Set TCRIT, (celsius °C) → None
                                                                                   // rounded to the nearest 0.25 °C step
    console.log('upper', await sensor.getUpperLimit());                            // Read TUPPER, () → number °C
                                                                                   // decodes the 0.25 °C two's-complement boundary
    console.log('lower', await sensor.getLowerLimit());                            // Read TLOWER, () → number °C
                                                                                   // decodes the 0.25 °C two's-complement boundary
    console.log('critical', await sensor.getCriticalLimit());                      // Read TCRIT, () → number °C
                                                                                   // decodes the 0.25 °C two's-complement boundary

    await sensor.setHysteresis(1.5);                                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → None
                                                                                   // applied on the cooling edge of each boundary only
    console.log('hysteresis', await sensor.getHysteresis());                       // Read hysteresis, () → number °C
                                                                                   // decodes CONFIG.THYST

    // await sensor.lockCriticalLimit();                                           // Lock TCRIT, () → None
    //                                                                             // irreversible until power-on reset
    // await sensor.lockWindowLimits();                                            // Lock TUPPER/TLOWER, () → None
    //                                                                             // irreversible until power-on reset
    console.log('crit locked', await sensor.isCriticalLimitLocked());              // Check TCRIT lock, () → boolean
                                                                                   // reads CONFIG.CRIT_LOCK
    console.log('win locked', await sensor.isWindowLimitsLocked());                // Check TUPPER/TLOWER lock, () → boolean
                                                                                   // reads CONFIG.WIN_LOCK

    await sensor.configureAlert('all', 'interrupt', 'active_low');                 // Configure Alert, (mode='all', output='comparator', polarity='active_low') → None
                                                                                   // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
    await sensor.enableAlert();                                                    // Enable Alert output, () → None
                                                                                   // sets CONFIG.ALERT_CNT
    console.log('alert asserted', await sensor.isAlertAsserted());                 // Check Alert output, () → boolean
                                                                                   // reads the read-only CONFIG.ALERT_STAT

    const status = await sensor.pollInterrupt();                                   // Read boundary status, () → number mask
                                                                                   // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
    console.log('below lower', (status & MCP9808Full.SOURCE_LOWER) !== 0,
        'above upper', (status & MCP9808Full.SOURCE_UPPER) !== 0,
        'critical', (status & MCP9808Full.SOURCE_CRITICAL) !== 0);
    await sensor.clearInterrupt();                                                 // Clear interrupt-mode Alert, () → None
                                                                                   // writes CONFIG.INT_CLEAR=1; no effect in comparator mode

    await sensor.onInterrupt((s) => console.log('alert, status mask', s));         // Subscribe to Alert, (callback) → None
                                                                                   // callback receives the pollInterrupt() mask
    await sleep(5000);
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
                                                                                   // detaches the edge handler or stops the polling timer
    await sensor.disableAlert();                                                   // Disable Alert output, () → None
                                                                                   // clears CONFIG.ALERT_CNT

    await connection.close();
})();
