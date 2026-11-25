'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { MMA8451QFull }    = require('periph/src/chips/accelerometer/mma8451q');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new MMA8451QFull(connection);                   // Create MMA8451Q Full driver, (connection)

(async () => {
    await accel.init();                                        // Run init sequence, () → None
                                                             // verifies WHO_AM_I, resets and sets ±2 g / 100 Hz / 14-bit

    await accel.setRange(4);                                  // Set measurement range, (range_g) → g
                                                             // selects ±4 g; 2048 LSB/g instead of 4096 LSB/g
    await accel.setDataRate(200);                              // Set output data rate, (rate_hz) → Hz
                                                             // DR code 010; the turn-on time shrinks to 2/ODR + 2 ms
    await accel.setOversampling(0);                            // Set oversampling mode, (mode, sleep_mode=None) → None
                                                             // mode 0 = normal; mode 2 = high resolution, mode 3 = low power
    await accel.setLowNoise(false);                            // Set reduced-noise mode, (enabled) → None
                                                             // LNOISE caps the range at ±4 g, so it is left off here
    await accel.setResolution(14);                             // Set output resolution, (bits) → bits
                                                             // 14 = 14-bit; 8 = fast read, MSB registers only
    await accel.setHighPassOutput(false, 0);                   // Set high-pass output, (enabled, cutoff=0) → None
                                                             // HPF_OUT off; cutoff is the SEL code 0..3

    const [x, y, z] = await accel.read();                      // Read 3-axis acceleration, () → [number, number, number] g
    const raw = await accel.readRaw();                         // Read raw counts, () → [int, int, int]
                                                             // signed counts at the current resolution (14-bit right-aligned)
    const status = await accel.readStatus();                   // Read STATUS register, () → bitmask
                                                             // ZYXDR / ZYXOW data-ready and overwrite flags
    const ready = await accel.newDataAvailable();              // Check new data, () → bool
                                                             // True once ZYXDR is set; invalid while the FIFO is on
    const sysmod = await accel.readSystemMode();               // Read system mode, () → int
                                                             // SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep

    await accel.setOffset(0.01, -0.01, 0.0);                  // Set offsets, (x_g, y_g, z_g) → g
                                                             // OFF_x = round(offset / 0.002 g), clamped to ±128 counts (±0.256 g)
    const offsets = await accel.getOffset();                   // Get offsets, () → [number, number, number] g
                                                             // current per-axis offset correction

    await accel.setFifo(1, 16);                               // Configure FIFO, (mode, watermark=0) → None
                                                             // mode 1 = circular, watermark 16 samples
    await accel.setFifoTrigger(false, true, false, false);     // Set FIFO triggers, (transient, orientation, pulse, motion) → None
                                                             // trigger the FIFO on an orientation change
    const level = await accel.fifoLevel();                     // Read FIFO level, () → int
                                                             // F_CNT, 0..32 samples
    const fstatus = await accel.readFifoStatus();              // Read FIFO status, () → {overflow, watermark, count}
                                                             // reading F_STATUS clears the FIFO interrupt

    await accel.setFreefallMotion(false, 7, 0.2, 60, true);    // Configure freefall, (motion, axes, threshold_g, debounce_ms, latch) → g, ms
                                                             // OAE = 0 (AND of all axes); 0.2 g threshold, 60 ms debounce, latched
    await accel.setTransient(7, 0.25, 0, false, true);         // Configure transient, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → g, ms
                                                             // 0.25 g threshold on all axes, latched
    await accel.setPulse(0, 4, [0.5, 0.5, 1.0], 40, 100, 300, true);
                                                             // Configure tap, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → g, ms
                                                             // double tap on Z with a 1.0 g Z threshold

    await accel.setOrientation(true, 100, 45, 4, 4, 1);        // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → ms, deg
                                                             // 45° trip angle, ±14° hysteresis, 100 ms debounce
    const orient = await accel.readOrientation();              // Read orientation, () → {changed, lockout, portraitLandscape, back}
                                                             // LAPO 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left

    await accel.setAutoSleep(true, 12.5, 10, 0);              // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → Hz, s
                                                             // sleeps after 10 s of inactivity at 12.5 Hz
    await accel.setFifoGate(false);                            // Set FIFO gate, (enabled) → None
                                                             // block FIFO input around WAKE/SLEEP transitions
    await accel.setInterruptPins(false, false);                // Set interrupt pins, (active_high, open_drain) → None
                                                             // IPOL = 0 (active low), PP_OD = 0 (push-pull)

    await accel.enableInterrupt(0x04, 1);                      // Enable interrupt, (source, pin=1) → None
                                                             // enables the freefall/motion source and routes it to INT1
    await accel.disableInterrupt(0x04);                        // Disable interrupt, (source) → None
                                                             // clears the INT_EN bit in CTRL_REG4

    const polled = await accel.pollInterrupt();                // Poll interrupts, () → bitmask
                                                             // INT_SOURCE; also clears the latched source flags
    await accel.onInterrupt(() => {});                         // Subscribe to interrupts, (callback, pin=1) → None
                                                             // fires on every INT edge; the callback receives the status mask
    await accel.offInterrupt();                                // Unsubscribe from interrupts, (pin=1) → None
                                                             // stops interrupt delivery

    const st = await accel.selfTest();                        // Run self-test, () → {ok, dx, dy, dz}
                                                             // electrostatic force; compares the count deltas against the typical values
    await accel.standby();                                     // Enter standby, () → None
                                                             // ACTIVE = 0; configuration registers may now be written
    await accel.activate();                                    // Leave standby, () → None
                                                             // ACTIVE = 1, then waits 2/ODR + 2 ms
    await accel.reset();                                       // Software reset, () → None
                                                             // RST bit; the chip returns to STANDBY with reset values

    console.log(`xyz=[${[x, y, z].map(v => v.toFixed(3)).join(', ')}] raw=${JSON.stringify(raw)} status=0x${status.toString(16)} ready=${ready} sysmod=${sysmod} offsets=${JSON.stringify(offsets)} fifoLevel=${level} fifo=${JSON.stringify(fstatus)} orient=${JSON.stringify(orient)} polled=0x${polled.toString(16)} selftest=${JSON.stringify(st)}`);
    await connection.close();
})();
