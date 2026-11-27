'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH46410Full } = require('../../../src/chips/gas/rrh46410');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x38) → Connection
// The module has the fixed address 0x38 and stretches the I²C clock.
const sensor = new RRH46410Full(connection);                             // Create RRH46410 driver, (connection) → void
// Constructor stores the connection only; no bus traffic yet.

async function main() {
    await sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → void
    // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

    console.log(await sensor.productId());                               // Product ID and firmware, () → {productId, firmware: [major, minor, patch]}
    // The product ID is always 0x2310.
    console.log(await sensor.trackingNumber());                          // ASIC tracking number, () → Buffer (6)
    // Unique per module.
    console.log(await sensor.getMode());                                 // Operation mode, () → number
    // 1 = IAQ 2nd Gen after init.

    await sensor.setHumidity(45.0);                                      // Ambient humidity, (percent %RH) → void
    // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
    console.log(await sensor.read());                                    // Next sample, () → {iaq, tvoc_mg_m3 mg/m³, etoh_ppm ppm, eco2_ppm ppm, relative_iaq, sample, warmed_up}
    // Waits for the sample counter to change; values are fixed during the warm-up.
    console.log(await sensor.waitForSample(4.0));                        // Wait for a new sample, (timeout s) → boolean
    // True when the counter changed (or INT went high) within the timeout.
    console.log(await sensor.readResults());                             // Latest result, () → object|null
    // Does not wait; null before the first sample of the mode.
    console.log(RRH46410Full.airQualityLabel(2.4));                      // UBA rating, (iaq) → string
    // "very good", "good", "medium", "poor" or "bad".
    console.log(sensor.samplePeriod(), sensor.warmupSamples());          // Mode timing, (mode=null) → number s / number
    // 3 s and 100 samples in IAQ 2nd Gen mode.
    console.log(sensor.status());                                        // Last status byte, () → object
    // Error bits and the operation-mode error code (0xA = sensor damaged).

    await sensor.onInterrupt((r) => console.log('sample', r.sample, r.iaq)); // Subscribe, (callback, intPin=null) → void
    // Rising edge on INT, or a 200 ms poll comparing the sample counter.
    await sleep(7000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    // Stops the pin handler or polling timer.
    console.log(await sensor.pollInterrupt());                           // New sample pending, () → boolean
    // INT level if wired, otherwise a counter comparison.

    await sensor.setMode(RRH46410Full.MODE_PBAQ);                        // Operation mode, (mode) → void
    // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
    console.log(await sensor.read());                                    // Next sample, () → {tvoc_mg_m3, etoh_ppm, sample, warmed_up}
    // PBAQ results have a finer resolution (raw / 1000).
    await sensor.setMode(RRH46410Full.MODE_ULP);                         // Operation mode, (mode) → void
    // ULP: one sample every 90 s at 0.23 mW.
    console.log(sensor.samplePeriod(), sensor.warmupSamples());          // Mode timing, (mode=null) → number s / number
    // 90 s and 10 samples.

    await sensor.configGpio(0x03);                                       // GPIO directions, (directions) → void
    // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
    await sensor.setGpio(0x01);                                          // Drive high, (mask) → void
    // GPIO1 high.
    console.log((await sensor.getGpio()).toString(2));                   // GPIO levels, () → number
    // Bits 0–3 = GPIO1–GPIO4.
    await sensor.clearGpio(0x01);                                        // Drive low, (mask) → void
    // GPIO1 low.

    console.log(await sensor.sendCommand(0x10, [], 1));                  // Raw request, (command, params=[], responseLen=0) → Buffer
    // Checksums added and verified; here Get Operation Mode.
    // await sensor.cleanSensor(true);                                   // One-time cleaning, (confirm=false) → void
    // Runs for one minute, once in the sensor's lifetime; left disabled here.
    await sensor.suspend();                                              // Suspend mode, () → void
    // Stops measuring, about 0.7 µA.
    await sensor.reset();                                                // Reset, () → void
    // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
