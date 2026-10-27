'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);                 // Open I2C bus for the accelerometer, (bus, addr=0x19)
const magConnection = new I2CConnection(I2C_BUS, 0x1E);                   // Open I2C bus for the magnetometer, (bus, addr=0x1E)
const lsm = new LSM303Full(accelConnection, magConnection);               // Create LSM303 Full driver, (accelConnection, magConnection)
                                                                          // runs init: ±2 g / 100 Hz / high-resolution accel, ±130 µT / 15 Hz / continuous mag

const COMPASS_POINTS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                        'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

// --- Configure the e-compass: ±2 g / 50 Hz high-resolution, ±130 µT / 15 Hz ---
// ±2 g keeps the best resolution (1 mg/LSB) for tilt sensing; 50 Hz ODR is
// plenty for the 16-sample FIFO watermark. The magnetometer stays at its
// default 15 Hz / ±130 µT (best resolution, 1100 LSB/gauss).
async function setup() {
    await lsm.setRange(2);                                               // Set accelerometer full-scale range, (rangeG) → g
    await lsm.setDataRate(50);                                           // Set accelerometer output data rate, (rateHz) → Hz
    await lsm.setMode(2);                                                // Set operating mode, (mode) → None
    await lsm.setMagRange(130);                                          // Set magnetometer full-scale range, (rangeUt) → µT
    await lsm.setMagDataRate(15);                                        // Set magnetometer data rate, (rateHz) → Hz
}

// --- Arm double-tap on Z and a latched free-fall interrupt on INT1 ---
// A double tap on Z (0.8 g threshold) and a 60 ms free-fall window are
// latched so the polling loop cannot miss a transient; both sources are
// routed to INT1 and acknowledged by pollInterrupt().
async function armInterrupts() {
    await lsm.setClick(0, 0x04, 0.8, 20, 40, 200);                       // Configure click detection, (single=0, double=0, thresholdG=0.5, timeLimitMs=10, latencyMs=20, windowMs=50) → None
                                                                         // double click on Z, 0.8 g, 20 ms limit, 40 ms latency, 200 ms window
    await lsm.setFreeFall(1, 0.35, 60);                                  // Configure free-fall detection, (generator=1, thresholdG=0.35, durationMs=30) → None
                                                                         // gen 1: all axes below 0.35 g for 60 ms, AND mode
    await lsm.setLatch(1, true);                                         // Latch generator interrupt, (generator, enabled) → None
                                                                         // INT1 stays asserted until INT1_SOURCE_A is read
    await lsm.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);               // Route interrupt source, (source, pin=1) → None
                                                                         // click events to INT1
    await lsm.enableInterrupt(LSM303Full.SOURCE_IA1, 1);                 // Route interrupt source, (source, pin=1) → None
                                                                         // generator-1 (free-fall) events to INT1
}

// --- 60-second tilt-compensated compass with tap / free-fall events ---
// Once per second the FIFO (stream mode, 16-sample watermark) is drained and
// averaged, the magnetic field is read, and the tilt-compensated heading is
// printed as degrees plus a 16-point compass name, with pitch / roll, field
// magnitude and the relative temperature. Latched INT1 events print DOUBLE
// TAP or FREE FALL lines between the compass readings.
(async () => {
    await setup();
    await armInterrupts();
    await lsm.setFifo(2, 16);                                            // Configure FIFO, (mode, watermark=0, triggerInt2=false) → None
                                                                         // stream mode, 16-sample watermark

    const start = Date.now();
    while (Date.now() - start < 60000) {
        const elapsed = Math.floor((Date.now() - start) / 1000);

        const status = await lsm.pollInterrupt();                        // Poll interrupt sources, () → int
                                                                         // reads INT1_SOURCE_A / CLICK_SRC_A; clears latched flags
        if (status & LSM303Full.SOURCE_CLICK) {
            console.log(`${String(elapsed).padStart(3)}s  DOUBLE TAP`);
        }
        if (status & LSM303Full.SOURCE_IA1) {
            console.log(`${String(elapsed).padStart(3)}s  FREE FALL`);
        }

        const samples = await lsm.readFifo();                            // Drain FIFO, (count=null) → [[number, number, number]] g
                                                                         // one 6-byte burst per unread sample set
        const [mx, my, mz] = await lsm.readMagnetic();                   // Read 3-axis magnetic flux density, () → [number, number, number] µT
        const heading = await lsm.heading();                             // Read compass heading, (tiltCompensate=true) → float degrees
                                                                         // tilt-compensated from the averaged acceleration
        const tempDelta = await lsm.readTemperatureDelta();               // Read relative temperature, () → float °C

        let ax = 0, ay = 0, az = 0;
        for (const [x, y, z] of samples) { ax += x; ay += y; az += z; }
        const n = samples.length || 1;
        ax /= n; ay /= n; az /= n;
        const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180.0 / Math.PI;
        const roll = Math.atan2(ay, az) * 180.0 / Math.PI;
        const magnitude = Math.sqrt(mx * mx + my * my + mz * mz);
        const compass = COMPASS_POINTS[Math.round(heading / 22.5) % 16];

        console.log(`${String(elapsed).padStart(3)}s  heading=${heading.toFixed(1)}° ${compass.padEnd(3)}  ` +
            `pitch=${pitch.toFixed(1)}° roll=${roll.toFixed(1)}°  ` +
            `|B|=${magnitude.toFixed(1)} µT  ΔT=${tempDelta.toFixed(1)}°C  fifo=${samples.length}`);

        await new Promise(r => setTimeout(r, 1000));
    }
    console.log('done');
    await accelConnection.close();
    await magConnection.close();
})().catch(err => { console.error(err); process.exit(1); });
