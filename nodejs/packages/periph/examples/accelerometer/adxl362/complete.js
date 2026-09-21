'use strict';

const { SPIConnection } = require('../../../src/connection/spi');                    // SPIConnection class, (bus, dev, options) → SPIConnection
const { ADXL362Full } = require('../../../src/chips/accelerometer/adxl362');         // ADXL362Full class, (connection) → ADXL362Full

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });    // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const chip = new ADXL362Full(connection);                                                      // Create ADXL362 Full driver, (connection) → ADXL362Full
                                                                                                    // init() runs asynchronously; identity errors surface on the first register access

    // --- device_id triple ---
    const { devidAd, devidMst, partid, revid } = await chip.deviceId();                            // Read device IDs, () → Promise<{devidAd:int, devidMst:int, partid:int, revid:int}>
    console.log(`DEVID_AD=0x${devidAd.toString(16)} DEVID_MST=0x${devidMst.toString(16)} PARTID=0x${partid.toString(16)} REVID=0x${revid.toString(16)}`);

    await chip.setRange(4);                                                                        // Set measurement range, (rangeG=4) → Promise<void>
    await chip.setOdr(200.0);                                                                      // Set output data rate, (odrHz=200.0) → Promise<void>
    await chip.setHalfBandwidth(true);                                                             // Set antialiasing bandwidth, (enabled=true) → Promise<void>
    await chip.setNoiseMode(ADXL362Full.NOISE_LOW);                                                // Set noise mode, (mode=NOISE_LOW=1) → Promise<void>

    const r12 = await chip.read();                                                                 // Read 12-bit acceleration, () → Promise<{x:float, y:float, z:float}> g
    console.log(`12-bit: x=${r12.x.toFixed(3)}  y=${r12.y.toFixed(3)}  z=${r12.z.toFixed(3)}`);

    const r8 = await chip.read8bit();                                                              // Read 8-bit acceleration, () → Promise<{x:float, y:float, z:float}> g
    console.log(` 8-bit: x=${r8.x.toFixed(3)}  y=${r8.y.toFixed(3)}  z=${r8.z.toFixed(3)}`);

    const t = await chip.temperature();                                                            // Read temperature, () → Promise<float> °C
    console.log(`temperature: ${t.toFixed(2)} C`);

    const rawStatus = await chip.status();                                                         // Read STATUS register, () → Promise<byte>
    console.log(`status: 0x${rawStatus.toString(16)}`);
    console.log(`awake: ${await chip.awake() ? 1 : 0}`);                                           // Check AWAKE bit, () → Promise<bool>
    console.log(`data_ready: ${await chip.dataReady() ? 1 : 0}`);                                  // Check DATA_READY, () → Promise<bool>
    console.log(`fifo_entries: ${await chip.fifoEntries()}`);                                      // Read FIFO entry count, () → Promise<int>

    await chip.configureFifo(ADXL362Full.FIFO_STREAM, false, 128);                                 // Configure FIFO, (mode=STREAM=2, storeTemp=false, watermark=128) → Promise<void>
    await chip.setActivityThreshold(0.5, true);                                                    // Set activity threshold, (thresholdG=0.5, referenced=true) → Promise<void>
    await chip.setActivityTime(5);                                                                 // Set activity time, (samples=5) → Promise<void>
    await chip.setInactivityThreshold(0.2, true);                                                  // Set inactivity threshold, (thresholdG=0.2, referenced=true) → Promise<void>
    await chip.setInactivityTime(30);                                                              // Set inactivity time, (samples=30) → Promise<void>
    await chip.enableActivityDetection(true);                                                      // Enable activity detection, (enabled=true) → Promise<void>
    await chip.enableInactivityDetection(true);                                                    // Enable inactivity detection, (enabled=true) → Promise<void>
    await chip.setLinkLoopMode(ADXL362Full.LINKLOOP_LOOP);                                        // Set link/loop mode, (mode=LOOP=3) → Promise<void>

    await chip.setInterrupt(1, ADXL362Full.SOURCE_DATA_READY, true);                              // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → Promise<void>
    await chip.setInterrupt(2, ADXL362Full.SOURCE_AWAKE, true);                                   // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → Promise<void>
    await chip.setInterruptPolarity(1, true);                                                      // Set INT1 active-low, (pin=1, activeLow=true) → Promise<void>

    await chip.selfTest(true);                                                                     // Enable self-test, (enabled=true) → Promise<void>
    await chip.selfTest(false);                                                                    // Disable self-test, (enabled=false) → Promise<void>

    await chip.softReset();                                                                        // Soft-reset the chip, () → Promise<void>

    await connection.close();
})().catch(err => { console.error(err); process.exit(1); });