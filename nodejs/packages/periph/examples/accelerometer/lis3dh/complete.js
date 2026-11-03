'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LIS3DHFull, LIS3DHSource } = require('periph/src/chips/accelerometer/lis3dh');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x18', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new LIS3DHFull(connection);                  // Create LIS3DH Full driver, (connection)

(async () => {
    // --- Operating mode, range and data rate -----------------------------
    await accel.setMode(2);                               // Set operating mode, (mode) → None
                                                          // 2 = high-resolution (12-bit); the alternatives are 0 (low-power, 8-bit) and 1 (normal, 10-bit)
    await accel.setRange(2);                              // Set full-scale range, (rangeG) → None
                                                          // ±2 g, i.e. 1 mg/LSB in high-resolution mode
    await accel.setDataRate(100);                         // Set output data rate, (rateHz) → None
                                                          // 100 Hz; the chip converts 100 times per second
    await accel.setAxes(true, true, true);                // Enable measurement axes, (x, y, z) → None
                                                          // all three axes converted; a disabled axis reads 0

    // --- Data reads -------------------------------------------------------
    const acc = await accel.read();                       // Read 3-axis acceleration, () → {x, y, z} g
                                                          // burst read of 0x28–0x2D scaled with the current sensitivity
    const raw = await accel.readRaw();                    // Read raw counts, () → {x, y, z}
                                                          // signed counts at the current resolution (12-bit in high-resolution mode)
    const ready = await accel.newDataAvailable();         // Check new data, () → bool
                                                          // true once a complete X/Y/Z sample set is ready
    const status = await accel.readStatus();              // Read status register, () → byte
                                                          // overrun and data-available flags of STATUS_REG (0x27)

    // --- High-pass filter --------------------------------------------------
    await accel.setHighPass({mode: 0, cutoff: 0, filterOutput: false, onIa1: true, onIa2: false, onClick: false});
                                                          // Configure high-pass filter, (mode, cutoff, filterOutput, onIa1, onIa2, onClick) → None
                                                          // HPM 00 (normal, reset by reading REFERENCE), applied to generator 1 only
    const ref = await accel.resetHighPass();              // Reset high-pass filter, () → byte
                                                          // reads REFERENCE, which resets the filter in HPM 00
    await accel.setReference(0x00);                       // Set high-pass reference, (value) → None
                                                          // REFERENCE = 0; only used with HPM = 01

    // --- Auxiliary ADC and temperature --------------------------------------
    await accel.enableAdc(true);                          // Enable auxiliary ADC, (enabled) → None
                                                          // ADC_EN = 1 in TEMP_CFG_REG
    const adc1 = await accel.readAdc(1);                  // Read auxiliary ADC channel, (channel) → V
                                                          // channel 1 input voltage; 1.200 V centre, ±400 mV range
    const temp = await accel.readTemperatureDelta();      // Read relative temperature, () → °C
                                                          // enables ADC_EN + TEMP_EN, then returns the 1 digit/°C delta
    await accel.enableAdc(false);                         // Enable auxiliary ADC, (enabled) → None
                                                          // ADC_EN = 0 again

    // --- FIFO ---------------------------------------------------------------
    await accel.setFifo(2, 16, false);                    // Configure FIFO, (mode, watermark, triggerInt2) → None
                                                          // mode 2 = stream, watermark 16 samples, trigger source INT1
    const level = await accel.fifoLevel();                // Read FIFO level, () → count
                                                          // number of unread sample sets (0-32)
    const samples = await accel.readFifo();               // Drain the FIFO, (count) → [{x, y, z}]
                                                          // reads every buffered sample set; each one is a 6-byte burst
    const fifo = await accel.readFifoStatus();            // Read FIFO status, () → {watermark, overrun, empty, level}
                                                          // decoded FIFO_SRC_REG flags
    await accel.setFifo(0);                              // Configure FIFO, (mode, watermark, triggerInt2) → None
                                                          // back to bypass, which empties the buffer

    // --- Inertial interrupt generators ---------------------------------------
    await accel.setMotionInterrupt(1, 0x05, 0.5, 20, false);
                                                          // Configure motion interrupt, (generator, events, thresholdG, durationMs, andMode) → None
                                                          // generator 1, X-high + Z-high events, 0.5 g threshold, 20 ms duration, OR combination
    await accel.setFreeFall(1, 0.35, 60);                 // Configure free fall, (generator, thresholdG, durationMs) → None
                                                          // all three axes below 0.35 g for 60 ms, AND combination
    await accel.setOrientation(1, false, false);          // Configure orientation, (generator, position, fourD) → None
                                                          // 6D movement recognition on generator 1
    await accel.setLatch(1, true);                        // Latch generator interrupt, (generator, enabled) → None
                                                          // LIR_INT1 = 1: the interrupt stays high until INT1_SRC is read
    const int1 = await accel.readInterruptSource(1);      // Read interrupt source, (generator) → byte
                                                          // INT1_SRC; reading it clears the latched interrupt

    // --- Click detection ------------------------------------------------------
    await accel.setClick(0, 0x04, 0.5, 10, 20, 50, false);
                                                          // Configure click detection, (single, double, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → None
                                                          // double click on Z, 0.5 g threshold, 10 ms time limit, 20 ms latency, 50 ms window
    const click = await accel.readClickSource();          // Read click source, () → byte
                                                          // CLICK_SRC; reading it clears the click interrupt

    // --- Sleep-to-wake activity -------------------------------------------------
    await accel.setActivity(0.1, 100);                    // Configure activity, (thresholdG, durationMs) → None
                                                          // drop to the 10 Hz low-power state below 0.1 g for 100 ms

    // --- Interrupt polarity and routing ------------------------------------------
    await accel.setInterruptPolarity(false);              // Set interrupt polarity, (activeLow) → None
                                                          // active-high INT1/INT2, the reset default
    await accel.enableInterrupt(LIS3DHSource.SOURCE_DATA_READY, 1);
                                                          // Route interrupt source, (source, pin) → None
                                                          // new X/Y/Z sample sets on INT1
    await accel.enableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
                                                          // Route interrupt source, (source, pin) → None
                                                          // click events on INT1
    let notified = 0;
    await accel.onInterrupt(status => { notified++; }, 1); // Subscribe to interrupts, (callback, pin) → None
                                                          // the callback receives the combined status int
    const poll = await accel.pollInterrupt();             // Poll interrupt status, () → int
                                                          // combined status of every enabled source
    await accel.offInterrupt(1);                          // Unsubscribe from interrupts, (pin) → None
                                                          // stops the delivery (and the 5 ms polling fallback)
    await accel.disableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
                                                          // Unroute interrupt source, (source, pin) → None
                                                          // click events no longer reach INT1

    // --- System functions ---------------------------------------------------------
    const st = await accel.selfTest(1);                   // Run self-test, (mode) → bool
                                                          // electrostatic self-test 0; true when every axis moved 17-360 LSb
    await accel.setSdoPullup(true);                       // Set SDO pull-up, (connected) → None
                                                          // keeps the pull-up on SDO/SA0
    await accel.powerDown();                              // Power down, () → None
                                                          // ODR = 0000 stops the conversions; the registers are retained
    await accel.reboot();                                 // Reboot, () → None
                                                          // BOOT reloads the factory trim and re-runs the initialization

    console.log(`acc=[${acc.x.toFixed(3)}, ${acc.y.toFixed(3)}, ${acc.z.toFixed(3)}] g raw=[${raw.x}, ${raw.y}, ${raw.z}] ready=${ready} status=0x${status.toString(16)}`);
    console.log(`reference=0x${ref.toString(16)} adc1=${adc1.toFixed(4)} V temp=${temp} °C`);
    console.log(`fifo level=${level} samples=${samples.length} status=${JSON.stringify(fifo)}`);
    console.log(`int1=0x${int1.toString(16)} click=0x${click.toString(16)} poll=0x${poll.toString(16)} notified=${notified} selfTest=${st ? 'PASS' : 'FAIL'}`);
    await connection.close();
})();
