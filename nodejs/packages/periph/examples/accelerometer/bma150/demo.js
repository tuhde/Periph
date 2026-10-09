'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { BMA150Full }    = require('periph/src/chips/accelerometer/bma150');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new BMA150Full(connection);                  // Create BMA150 driver, (connection)

// --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
// ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
// bandwidth is wide enough to capture a 2 ms high-g spike without
// aliasing. Latched interrupts free the polling loop from having to
// catch a transient.
async function setup() {
    await accel.setRange(8);                              // Set measurement range, (rangeG=2) → g
    await accel.setBandwidth(190);                        // Set bandwidth, (bandwidthHz=25) → Hz
    await accel.setLatch(true);                           // Set latched interrupts, (enabled=false) → None
    await accel.setLowG(0.4, 40);                        // Configure low-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
    await accel.setHighG(4.0, 2);                        // Configure high-g, (thresholdG, durationMs, hysteresisG=0, counter=0) → g, ms
}

// --- 60-second free-fall / shock logger ---
// User is expected to drop or shake the board at some point during
// the 60 s window. Between events the magnitude sits at ≈1.00 g
// (gravity). Each latched interrupt is reported with a timestamp,
// the latest (x, y, z), temperature, and a free-fall or shock tag.
(async () => {
    await setup();
    const start = Date.now();
    let lastHeartbeat = 0;
    let lastPoll = 0;

    while (Date.now() - start < 60000) {
        const now = Date.now();
        if (now - lastHeartbeat >= 1000) {
            const [x, y, z] = await accel.read();         // Read 3-axis acceleration, () → [number, number, number] g
            const mag = Math.sqrt(x * x + y * y + z * z);
            const temp = await accel.readTemperature();    // Read temperature, () → °C
            console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  x=${x.toFixed(3).padStart(7)}  y=${y.toFixed(3).padStart(7)}  z=${z.toFixed(3).padStart(7)}  |a|=${mag.toFixed(3)} g  T=${temp.toFixed(1)} C`);
            lastHeartbeat = now;
        }
        if (now - lastPoll >= 50) {
            const status = await accel.pollInterrupt();    // Read STATUS, () → bitmask
            if (status & 0x08) {                          // STATUS_LG_LATCHED (bit 3)
                const [x, y, z] = await accel.read();     // Read 3-axis acceleration, () → [number, number, number] g
                const temp = await accel.readTemperature();// Read temperature, () → °C
                console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  FREE FALL detected  x=${x.toFixed(3)}  y=${y.toFixed(3)}  z=${z.toFixed(3)}  T=${temp.toFixed(1)} C`);
                await accel.clearInterrupt();              // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                          // STATUS_HG_LATCHED (bit 2)
                const [x, y, z] = await accel.read();     // Read 3-axis acceleration, () → [number, number, number] g
                const temp = await accel.readTemperature();// Read temperature, () → °C
                console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  SHOCK detected     x=${x.toFixed(3)}  y=${y.toFixed(3)}  z=${z.toFixed(3)}  T=${temp.toFixed(1)} C`);
                await accel.clearInterrupt();              // Clear latched interrupts, () → None
            }
            lastPoll = now;
        }
        await new Promise(r => setTimeout(r, 10));
    }
    console.log('done');
    await connection.close();
})();
