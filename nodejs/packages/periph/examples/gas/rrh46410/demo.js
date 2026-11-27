'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RRH46410Full } = require('../../../src/chips/gas/rrh46410');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x38) → Connection
const sensor = new RRH46410Full(connection);                             // Create RRH46410 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → void

    // --- Identity ---
    // Get Product ID returns 0x2310 and the firmware version; the tracking number
    // identifies the individual module.
    const { productId, firmware } = await sensor.productId();           // Product ID and firmware, () → {productId, firmware}
    console.log('product 0x%s, firmware %s', productId.toString(16), firmware.join('.'));
    console.log('tracking', (await sensor.trackingNumber()).toString('hex')); // Tracking number, () → Buffer
    console.log('mode', await sensor.getMode());                         // Operation mode, () → number

    // --- Humidity compensation ---
    // The MOx element reacts to humidity; feeding the real value (e.g. from a
    // humidity sensor) improves the results. The module assumes 50 %RH.
    await sensor.setHumidity(42.0);                                      // Ambient humidity, (percent %RH) → void

    // --- Indoor air-quality monitor ---
    // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
    // warm-up during which the values stay fixed.
    const warmup = sensor.warmupSamples();                               // Warm-up length, (mode=null) → number samples
    console.log('sample period %d s, warm-up %d samples', sensor.samplePeriod(), warmup); // Sample period, (mode=null) → number s
    for (let i = 0; i < 20; i++) {
        const r = await sensor.read();                                   // Next sample, () → object
        const progress = r.warmed_up ? 'ok' : `warm-up ${Math.min(100, Math.floor(r.sample * 100 / warmup))}%`;
        console.log('IAQ %s (%s)  TVOC %s mg/m3  EtOH %s ppm  eCO2 %d ppm  rel %d  %s', r.iaq.toFixed(1),
            sensor.airQualityLabel(r.iaq), r.tvoc_mg_m3.toFixed(2),     // UBA rating, (iaq) → string
            r.etoh_ppm.toFixed(2), r.eco2_ppm, r.relative_iaq, progress);
    }

    // --- Interrupt instead of polling ---
    // With INT wired the driver reacts to its rising edge; otherwise it polls the
    // sample counter. The callback receives the decoded result.
    await sensor.onInterrupt((r) => console.log('INT sample %d IAQ %s', r.sample, r.iaq.toFixed(1))); // Subscribe, (callback, intPin=null) → void
    await sleep(10000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    while (!(await sensor.pollInterrupt())) await sleep(500);           // New sample pending, () → boolean
    if (await sensor.waitForSample(4.0)) {                               // Wait for a new sample, (timeout s) → boolean
        console.log('latest', await sensor.readResults());              // Latest result, () → object|null
    }

    // --- Other operation modes ---
    // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
    // one sample every 90 s. Each mode change restarts the warm-up.
    for (const [mode, name] of [[RRH46410Full.MODE_PBAQ, 'PBAQ'], [RRH46410Full.MODE_ULP, 'ULP']]) {
        await sensor.setMode(mode);                                      // Operation mode, (mode) → void
        console.log('%s: sample every %d s, warm-up %d samples (%d min)', name, sensor.samplePeriod(),
            sensor.warmupSamples(), sensor.samplePeriod() * sensor.warmupSamples() / 60); // Mode timing, (mode=null) → number
    }
    await sensor.setMode(RRH46410Full.MODE_PBAQ);                        // Operation mode, (mode) → void
    console.log('PBAQ', await sensor.read());                            // Next sample, () → {tvoc_mg_m3, etoh_ppm, sample, warmed_up}

    // --- GPIOs ---
    // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
    await sensor.configGpio(0x03);                                       // Both outputs, (directions) → void
    for (let i = 0; i < 3; i++) {
        await sensor.setGpio(0x01);                                      // GPIO1 high, (mask) → void
        await sleep(200);
        await sensor.clearGpio(0x01);                                    // GPIO1 low, (mask) → void
        await sleep(200);
    }
    console.log('GPIO levels', (await sensor.getGpio()).toString(2));    // GPIO levels, () → number
    console.log('status', sensor.status());                              // Last status byte, () → object
    console.log('raw mode', (await sensor.sendCommand(0x10, [], 1))[0]); // Raw request, (command, params, responseLen) → Buffer

    // --- Wrap up ---
    // cleanSensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
    // it is skipped here. Suspend stops measuring; reset restarts the module in SUSPEND.
    await sensor.suspend();                                              // Suspend mode, () → void
    await sensor.reset();                                                // Reset, () → void
    console.log('mode after reset', await sensor.getMode());             // Operation mode, () → number
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
