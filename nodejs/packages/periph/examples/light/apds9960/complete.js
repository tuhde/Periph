'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { APDS9960Full } = require('../../../src/chips/light/apds9960');

const connection = new I2CConnection(1, 0x39);
const apds = new APDS9960Full(connection);                  // Create APDS9960 driver, (connection) → APDS9960Full

(async () => {
    console.log('0x' + (await apds.chipId()).toString(16)); // Read device ID, () → number

    const { clear, red, green, blue } = await apds.color(); // Read all RGBC channels, () → { clear, red, green, blue }
                                                           // burst read 0x94-0x9B latches all channels atomically
    console.log(await apds.colorClear());                  // Read clear channel, () → number 0-65535
    console.log(await apds.colorRed());                    // Read red channel, () → number 0-65535
    console.log(await apds.colorGreen());                  // Read green channel, () → number 0-65535
    console.log(await apds.colorBlue());                   // Read blue channel, () → number 0-65535

    await apds.configureAls(0xB6, 1);                       // Configure ALS, (atime 0-255, again 0-3) → void
                                                           // sets integration time and gain for the ALS/color engine
    await apds.configureWait(0xFF, false);                  // Configure wait, (wtime 0-255, wlong=false) → void
                                                           // sets idle period between measurement cycles
    await apds.enableWait(true);                            // Enable wait engine, (enabled) → void

    await apds.enableProximity(true);                       // Enable proximity engine, (enabled) → void
    await apds.configureProximityLed(0, 0, 0, 1);           // Configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → void
                                                           // sets LED drive strength, gain, pulse count and length
    await apds.setLedBoost(0);                              // Set LED boost, (boost 0-3) → void
                                                           // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
    console.log(await apds.proximity());                    // Read proximity count, () → number 0-255

    await apds.alsThreshold(100, 60000);                    // Set ALS thresholds, (low 0-65535, high 0-65535) → void
    await apds.proximityThreshold(10, 200);                 // Set proximity thresholds, (low 0-255, high 0-255) → void
    await apds.setPersistence(0, 1);                        // Set persistence, (ppers 0-15, apers 0-15) → void

    await apds.enableAlsInterrupt(true);                    // Enable ALS interrupt, (enabled) → void
    await apds.enableProximityInterrupt(true);              // Enable proximity interrupt, (enabled) → void
    await apds.clearAlsInterrupt();                         // Clear ALS interrupt, () → void
    await apds.clearProximityInterrupt();                   // Clear proximity interrupt, () → void
    await apds.clearAllInterrupts();                        // Clear all interrupts, () → void

    await apds.setProximityOffset(10, -5);                  // Set proximity offset, (ur -127..127, dl -127..127) → void
                                                           // sign-magnitude encoding compensates for optical crosstalk
    await apds.setProximityMask(false, false, false, false); // Set proximity mask, (u, d, l, r) → void

    await apds.enableGesture(true);                         // Enable gesture engine, (enabled) → void
    await apds.configureGesture(1, 0, 0, 1, 1, 50, 20);     // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
                                                           // sets gain, LED drive, pulse, wait time, entry/exit thresholds
    console.log(await apds.gestureAvailable());             // Check gesture data, () → boolean
    console.log(await apds.gestureFifoLevel());             // Read FIFO level, () → number
    console.log(await apds.readGestureFifo());              // Read gesture FIFO, () → Array<{u,d,l,r}>
    await apds.clearGestureFifo();                          // Clear gesture FIFO, () → void
    await apds.enableGestureInterrupt(false);               // Enable gesture interrupt, (enabled) → void
    await apds.enableGesture(false);                        // Disable gesture engine, (enabled) → void

    console.log(await apds.status());                       // Read STATUS register, () → number
    console.log(await apds.isAlsValid());                   // Check ALS data valid, () → boolean
    console.log(await apds.isProximityValid());              // Check proximity valid, () → boolean
    console.log(await apds.isAlsSaturated());                // Check ALS saturated, () → boolean
    console.log(await apds.isProximitySaturated());           // Check proximity saturated, () → boolean

    await apds.enableProximity(false);
    await connection.close();
})();
