'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LIS3DHFull, LIS3DHSource } = require('periph/src/chips/accelerometer/lis3dh');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x18', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new LIS3DHFull(connection);

// --- Configure the sensor for tilt, double-tap and free-fall detection ---
// ±2 g in high-resolution mode gives 1 mg/LSB, enough resolution for the
// pitch/roll estimate. 50 Hz halves the power budget compared to the 100 Hz
// default and still samples a double tap reliably. The self-test is run
// once at start-up to verify the MEMS elements and the sign of the axes.
async function setup() {
    await accel.setRange(2);                              // Set full-scale range, (rangeG) → None
                                                          // ±2 g, i.e. 1 mg/LSB in high-resolution mode
    await accel.setMode(2);                               // Set operating mode, (mode) → None
                                                          // 2 = high-resolution (12-bit)
    await accel.setDataRate(50);                          // Set output data rate, (rateHz) → None
                                                          // 50 Hz
    const st = await accel.selfTest(1);                   // Run self-test, (mode) → bool
                                                          // electrostatic self-test 0; the result is printed once
    console.log(`self-test: ${st ? 'PASS' : 'FAIL'}`);

    // A double tap on Z is detected with a 0.8 g threshold; the 200 ms
    // window is wide enough for two separate taps but rejects a shake.
    await accel.setClick(0, 0x04, 0.8, 20, 40, 200, true);
                                                          // Configure click detection, (single, double, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → None
                                                          // double click on Z, latched until CLICK_SRC is read
    // Free fall: all three axes below 0.35 g for 60 ms.
    await accel.setFreeFall(1, 0.35, 60);                 // Configure free fall, (generator, thresholdG, durationMs) → None
                                                          // generator 1, 0.35 g threshold, 60 ms duration
    await accel.setLatch(1, true);                        // Latch generator interrupt, (generator, enabled) → None
                                                          // the free-fall event stays latched until INT1_SRC is read
    await accel.enableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
                                                          // Route interrupt source, (source, pin) → None
                                                          // click events on INT1
    await accel.enableInterrupt(LIS3DHSource.SOURCE_IA1, 1);
                                                          // Route interrupt source, (source, pin) → None
                                                          // generator 1 (free fall) on INT1
    // Stream mode keeps the last 32 samples; the watermark is 16, so the
    // buffer is drained twice per second at 50 Hz.
    await accel.setFifo(2, 16, false);                    // Configure FIFO, (mode, watermark, triggerInt2) → None
                                                          // mode 2 = stream, 16-sample watermark
}

// --- 60-second tilt / double-tap / free-fall monitor ---
// Every 100 ms the FIFO is drained and the samples are averaged, which
// removes most of the quantization noise. The pitch and roll are derived
// from the averaged vector with atan2(): the pitch is the rotation in the
// X-Z plane, the roll the rotation around the forward axis. The vector
// magnitude stays near 1 g while the board is tilted, and drops towards
// 0 g during a free fall. The user is expected to tilt the board, double
// tap it and shake it during the 60 s window.
(async () => {
    await setup();
    const start = Date.now();
    let lastLoop = 0;

    while (Date.now() - start < 60000) {
        const now = Date.now();
        if (now - lastLoop >= 100) {
            lastLoop = now;
            const level = await accel.fifoLevel();        // Read FIFO level, () → count
                                                          // number of buffered sample sets
            const samples = await accel.readFifo();       // Drain the FIFO, (count) → [{x, y, z}]
                                                          // reads every buffered sample set
            if (samples.length > 0) {
                let sx = 0, sy = 0, sz = 0;
                for (const s of samples) { sx += s.x; sy += s.y; sz += s.z; }
                const x = sx / samples.length;
                const y = sy / samples.length;
                const z = sz / samples.length;
                const pitch = Math.atan2(x, z) * 180.0 / Math.PI;
                const roll = Math.atan2(y, Math.hypot(x, z)) * 180.0 / Math.PI;
                const mag = Math.hypot(x, y, z);
                const temp = await accel.readTemperatureDelta();
                                                          // Read relative temperature, () → °C
                                                          // 1 digit/°C relative to the power-on reference
                console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  n=${samples.length} (level ${level})  pitch=${pitch.toFixed(1).padStart(7)}°  roll=${roll.toFixed(1).padStart(7)}°  |a|=${mag.toFixed(3)} g  T=${temp} °C`);
            }

            const status = await accel.pollInterrupt();   // Poll interrupt status, () → int
                                                          // combined status of every enabled source
            if (status & LIS3DHSource.SOURCE_CLICK) {
                console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  DOUBLE TAP`);
            }
            if (status & LIS3DHSource.SOURCE_IA1) {
                console.log(`${String(Math.floor((now - start) / 1000)).padStart(5)}  FREE FALL`);
            }
        }
        await new Promise(r => setTimeout(r, 10));
    }
    console.log('done');
    await connection.close();
})();
