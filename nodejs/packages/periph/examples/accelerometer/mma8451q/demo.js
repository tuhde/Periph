'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const {
    MMA8451QFull,
    SOURCE_ORIENTATION,
    SOURCE_PULSE,
    SOURCE_FF_MT,
} = require('periph/src/chips/accelerometer/mma8451q');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new MMA8451QFull(connection);                   // Create MMA8451Q driver, (connection)

// --- Configure ±4 g, 100 Hz, high-resolution oversampling and run the self-test ---
// ±4 g gives 2048 LSB/g, enough headroom for the freefall and tap
// thresholds while keeping 14-bit resolution. High-resolution
// oversampling trades current for noise performance, which matters when
// the chip spends most of its time in Auto-SLEEP.
async function setup() {
    await accel.init();                                        // Run init sequence, () → None
                                                             // verifies WHO_AM_I, resets and sets ±2 g / 100 Hz / 14-bit
    await accel.setRange(4);                                  // Set measurement range, (range_g) → g
                                                             // selects ±4 g
    await accel.setOversampling(2);                            // Set oversampling mode, (mode, sleep_mode=None) → None
                                                             // mode 2 = high resolution
    await accel.setDataRate(100);                              // Set output data rate, (rate_hz) → Hz
                                                             // DR code 011

    const st = await accel.selfTest();                        // Run self-test, () → {ok, dx, dy, dz}
    console.log(`self-test: ok=${st.ok} dx=${st.dx} dy=${st.dy} dz=${st.dz}`);
}

// --- Arm the orientation, tap and freefall detectors ---
// The orientation detector reports portrait/landscape and back/front
// changes; the tap detector fires on a double tap along Z; the freefall
// detector fires when all three axes drop below the threshold.
async function armDetectors() {
    await accel.setOrientation(true, 100);                     // Configure orientation, (enabled, debounce_ms=0, threshold_deg=45, hysteresis=4, z_lock=4, back_front=1) → ms
                                                             // 100 ms debounce suppresses the bounces of a hand-held board
    await accel.setPulse(0, 0x04, [0.5, 0.5, 1.0], 40, 100, 300, true);
                                                             // Configure tap, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms, latch=False) → g, ms
                                                             // double tap on Z, latched until PULSE_SRC is read
    await accel.setFreefallMotion(false, 7, 0.2, 60, true);    // Configure freefall, (motion, axes, threshold_g, debounce_ms, latch) → g, ms
                                                             // 0.2 g threshold on all axes, 60 ms debounce, latched

    // --- Auto-SLEEP after 10 s of inactivity, woken by a tap or freefall ---
    // The chip drops to 12.5 Hz after 10 s without motion; a tap or a
    // freefall event brings it straight back to 100 Hz.
    await accel.setAutoSleep(true, 12.5, 10, SOURCE_PULSE | SOURCE_FF_MT);
                                                             // Configure Auto-SLEEP, (enabled, sleep_rate_hz=50, timeout_s=10, wake_sources=0) → Hz, s
                                                             // sleep rate 12.5 Hz, timeout 10 s, wake on tap or freefall

    // --- Route the three detector interrupts to INT1 and fill the FIFO ---
    // All three sources share INT1; the driver reports the cause through
    // the status mask, so one pin is enough.
    await accel.enableInterrupt(SOURCE_ORIENTATION, 1);       // Enable interrupt, (source, pin=1) → None
                                                             // orientation change → INT1
    await accel.enableInterrupt(SOURCE_PULSE, 1);              // Enable interrupt, (source, pin=1) → None
                                                             // tap → INT1
    await accel.enableInterrupt(SOURCE_FF_MT, 1);              // Enable interrupt, (source, pin=1) → None
                                                             // freefall/motion → INT1

    await accel.setFifo(1, 16);                               // Configure FIFO, (mode, watermark=0) → None
                                                             // circular FIFO with a 16-sample watermark
}

// --- 60-second orientation / tap / freefall monitor ---
// The user is expected to tilt, tap and drop the board during the 60 s
// window. Every 250 ms the acceleration and the system mode are printed,
// so the transition from WAKE to SLEEP becomes visible as well.
(async () => {
    await setup();
    await armDetectors();

    const start = Date.now();
    while (Date.now() - start < 60000) {
        const [x, y, z] = await accel.read();                 // Read 3-axis acceleration, () → [number, number, number] g
        const mode = await accel.readSystemMode();             // Read system mode, () → int
        const modeName = mode === 2 ? 'SLEEP' : (mode === 1 ? 'WAKE' : 'STANDBY');
        console.log(`${String(Math.floor((Date.now() - start) / 1000)).padStart(5)}  x=${x.toFixed(3).padStart(7)}  y=${y.toFixed(3).padStart(7)}  z=${z.toFixed(3).padStart(7)}  ${modeName}`);

        const status = await accel.pollInterrupt();            // Poll interrupts, () → bitmask
        if (status & SOURCE_ORIENTATION) {
            const o = await accel.readOrientation();           // Read orientation, () → {changed, lockout, portraitLandscape, back}
                                                             // LAPO 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left
            const lapo = ['portrait up', 'portrait down', 'landscape right', 'landscape left'][o.portraitLandscape];
            console.log(`        ORIENTATION: ${lapo}, ${o.back ? 'back' : 'front'}`);
        }
        if (status & SOURCE_PULSE) {
            await accel.readPulseSource();                     // Read tap source, () → int
                                                             // reading PULSE_SRC clears the latched tap flag
            console.log('        DOUBLE TAP');
        }
        if (status & SOURCE_FF_MT) {
            await accel.readMotionSource();                    // Read motion source, () → int
                                                             // reading FF_MT_SRC clears the latched freefall flag
            console.log('        FREE FALL');
        }

        await new Promise(r => setTimeout(r, 250));
    }

    // --- Drain the circular FIFO and report the mean acceleration ---
    // The FIFO holds the last 32 samples; the mean smooths the 100 Hz
    // noise down to a stable tilt angle.
    const samples = await accel.readFifo();                   // Read FIFO, (count=None) → [[number, number, number]] g
                                                             // reads up to 32 samples, one per transaction
    const n = samples.length;
    if (n > 0) {
        const mean = [0, 1, 2].map(i => samples.reduce((s, v) => s + v[i], 0) / n);
        console.log(`FIFO: ${n} samples, mean x=${mean[0].toFixed(3)} y=${mean[1].toFixed(3)} z=${mean[2].toFixed(3)} g`);
    } else {
        console.log('FIFO: empty');
    }
    console.log('done');
    await connection.close();
})();
