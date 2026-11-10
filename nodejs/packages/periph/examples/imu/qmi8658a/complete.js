'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { QMI8658AFull } = require('../../../src/chips/imu/qmi8658a');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x6B', 16);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new QMI8658AFull(connection);                    // Create QMI8658A driver, (connection) → void
    const [ax, ay, az] = await imu.accel();                            // Read 3-axis acceleration, () → [number, number, number] m/s²
                                                                          // converts raw counts at 16384 LSB/g (±2 g)
    const [gx, gy, gz] = await imu.gyro();                             // Read 3-axis angular rate, () → [number, number, number] rad/s
                                                                          // converts raw counts at 128 LSB/dps (±256 dps)
    const [rax, ray, raz] = await imu.accelRaw();                      // Read raw accel counts, () → [number, number, number]
                                                                          // one 6-byte burst, signed 16-bit
    const [rgx, rgy, rgz] = await imu.gyroRaw();                       // Read raw gyro counts, () → [number, number, number]
                                                                          // one 6-byte burst, signed 16-bit
    const [accel, gyro, temp] = await imu.readAll();                   // Read one coherent sample, () → [[number, number, number], [number, number, number], number] m/s², rad/s, °C
                                                                          // one 14-byte burst of registers 0x33…0x40
    const t = await imu.temperature();                                 // Read die temperature, () → number °C
                                                                          // signed 8.8 fixed point, raw / 256
    const ts = await imu.timestamp();                                  // Read sample counter, () → number
                                                                          // 24-bit counter, wraps at 0xFFFFFF
    const ready = await imu.dataReady();                               // Check for new data, () → boolean
                                                                          // STATUS0 aDA / gDA
    await imu.configureAccel(1, 5);                                    // Configure accelerometer, (fullScale=0, odr=5) → void
                                                                          // CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; ODR code 5 = 224.2 Hz
    await imu.configureGyro(5, 5);                                     // Configure gyroscope, (fullScale=4, odr=5) → void
                                                                          // CTRL3: 0=±16 … 7=±2048 dps
    await imu.configureFilters(true, 3, true, 3);                      // Configure low-pass filters, (accelFilter=true, accelMode=3, gyroFilter=true, gyroMode=3) → void
                                                                          // CTRL5: mode 3 = 13.37 % of ODR
    await imu.setSensors(true, true, false);                           // Enable sensors, (accel=true, gyro=true, gyroSnooze=false) → void
                                                                          // CTRL7 aEN / gEN / gSN
    await imu.configureFifo(2, 1, 16);                                 // Configure FIFO, (mode=2, size=3, watermark=0) → void
                                                                          // stream mode, 32 samples, watermark 16
    await sleep(500);
    const fifoFlags = await imu.fifoStatus();                          // Read FIFO flags, () → number
                                                                          // raw FIFO_STATUS byte
    const fifoBytes = await imu.fifoCount();                           // Read FIFO fill, () → number bytes
                                                                          // two bytes per sample word
    const data = await imu.readFifo();                                 // Drain the FIFO, (count?) → Buffer
                                                                          // REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
    await imu.resetFifo();                                             // Reset the FIFO, () → void
                                                                          // CTRL9 RST_FIFO
    await imu.configureFifo(0);                                        // Back to bypass, (mode=2, size=3, watermark=0) → void
                                                                          // bypass re-enables data-ready
    await imu.configureAnyMotion(0.25, 3, 0b111, false);               // Configure any-motion, (thresholdG=0.25, window=3, axes=0b111, logicAnd=false) → void
                                                                          // two CTRL9 CONFIGURE_MOTION sets
    await imu.configureNoMotion(0.125, 30, 0b111, true);               // Configure no-motion, (thresholdG=0.125, window=30, axes=0b111, logicAnd=true) → void
                                                                          // same command, no-motion fields
    await imu.configureSignificantMotion(150, 600);                    // Configure significant motion, (waitWindow=150, confirmWindow=600) → void
                                                                          // wait and confirm windows in samples
    await imu.enableMotion(true, true, true);                          // Enable motion engines, (anyMotion=false, noMotion=false, significant=false) → void
                                                                          // CTRL8 bits 1–3
    await imu.enableMotion();                                          // Disable motion engines, (anyMotion=false, noMotion=false, significant=false) → void
                                                                          // clears CTRL8 bits 1–3
    await imu.configureTap();                                          // Configure tap engine, (priority=0, peakWindow=20, tapWindow=50, doubleTapWindow=250, alpha=0.0625, gamma=0.25, peakThreshold=0.8, quietThreshold=0.4) → void
                                                                          // two CTRL9 CONFIGURE_TAP sets
    await imu.enableTap(true);                                         // Enable tap engine, (enabled=true) → void
                                                                          // CTRL8.Tap_EN
    const [taps, axis, negative] = await imu.tapStatus();              // Read tap event, () → [number, number, boolean]
                                                                          // count 0/1/2, axis 0–3, polarity
    await imu.enableTap(false);                                        // Disable tap engine, (enabled=true) → void
                                                                          // clears CTRL8.Tap_EN
    await imu.configurePedometer();                                    // Configure pedometer, (sampleCount=50, peakToPeakMg=200, peakMg=100, timeUp=200, timeLow=20, entryCount=10, precision=0, reportEvery=4) → void
                                                                          // two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
    await imu.enablePedometer(true);                                   // Enable pedometer, (enabled=true) → void
                                                                          // CTRL8.Pedo_EN; 0→1 resets the count
    const steps = await imu.stepCount();                               // Read step count, () → number
                                                                          // 24-bit count
    await imu.resetStepCount();                                        // Clear step count, () → void
                                                                          // CTRL9 RESET_PEDOMETER
    await imu.enablePedometer(false);                                  // Disable pedometer, (enabled=true) → void
                                                                          // clears CTRL8.Pedo_EN
    await imu.setAccelOffset(0, 0, 0);                                 // Set accel offset, (x, y, z) m/s² → void
                                                                          // signed 4.12 g via CTRL9
    await imu.setGyroOffset(0, 0, 0);                                  // Set gyro offset, (x, y, z) rad/s → void
                                                                          // signed 11.5 dps via CTRL9
    const [calOk, codStatus] = await imu.calibrateGyro();              // Calibrate gyroscope, () → [boolean, number]
                                                                          // calibration-on-demand, ~1.5 s, keep still
    const [gainX, gainY, gainZ] = await imu.getGyroGains();            // Read gyro gains, () → [number, number, number]
                                                                          // raw 16-bit gains from dVX…dVZ
    await imu.setGyroGains(gainX, gainY, gainZ);                       // Restore gyro gains, (x, y, z) → void
                                                                          // CTRL9 APPLY_GYRO_GAINS
    const [accelOk, accelSelf] = await imu.selfTestAccel();            // Run accel self-test, () → [boolean, [number, number, number]] m/s²
                                                                          // passes when every axis exceeds 200 mg
    const [gyroOk, gyroSelf] = await imu.selfTestGyro();               // Run gyro self-test, () → [boolean, [number, number, number]] rad/s
                                                                          // passes when every axis exceeds 300 dps
    const info = await imu.deviceInfo();                               // Read device info, () → {revision, firmware, usid}
                                                                          // CTRL9 COPY_USID: revision, firmware, usid
    await imu.setPullups(true, true, true, true);                      // Control pull-ups, (aux=true, sdx=true, cs=true, i2c=true) → void
                                                                          // CTRL9 SET_RPU; false disables a group
    await imu.setWakeOnMotion(100, 1, 0, 0);                           // Arm wake-on-motion, (thresholdMg, interruptPin=1, initialLevel=0, blankingSamples=0) → void
                                                                          // accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
    await imu.setWakeOnMotion(0);                                      // Leave wake-on-motion, (thresholdMg, interruptPin=1, initialLevel=0, blankingSamples=0) → void
                                                                          // threshold 0 exits WoM mode
    await imu.setSensors(true, true);                                  // Re-enable sensors, (accel=true, gyro=true, gyroSnooze=false) → void
                                                                          // restores normal operation
    await imu.setInterruptPins(2, 2);                                  // Route interrupts, (fifoPin=2, activityPin=2) → void
                                                                          // CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
    await imu.enableInterrupt(QMI8658AFull.SOURCE_DATA_READY);         // Enable interrupt source, (source) → void
                                                                          // clears DRDY_DIS, enables INT2
    const status = await imu.pollInterrupt();                          // Poll interrupt status, () → number
                                                                          // STATUS0, STATUS1 and FIFO_STATUS as SOURCE_* bits
    await imu.onInterrupt((s) => console.log('INT status 0x%s', s.toString(16))); // Subscribe to interrupts, (callback) → void
                                                                          // uses the connection's interrupt pin or a 5 ms polling timer
    await sleep(200);
    await imu.offInterrupt();                                          // Unsubscribe, () → void
                                                                          // stops the callback
    await imu.disableInterrupt(QMI8658AFull.SOURCE_DATA_READY);        // Disable interrupt source, (source) → void
                                                                          // sets DRDY_DIS, releases INT2
    await imu.setSyncSample(true);                                     // Enable SyncSample, (enabled) → void
                                                                          // locks each sample; reads wait for Avail and Locked
    await imu.setSyncSample(false);                                    // Disable SyncSample, (enabled) → void
                                                                          // restores the AHB clock gating
    await imu.setPowerDown(true);                                      // Power down, (down=true) → void
                                                                          // CTRL1.SensorDisable after disabling the sensors
    await imu.setPowerDown(false);                                     // Wake, (down=true) → void
                                                                          // clears SensorDisable
    await imu.setSensors(true, true);                                  // Re-enable sensors, (accel=true, gyro=true, gyroSnooze=false) → void
                                                                          // needs 150 ms gyroscope turn-on
    await sleep(200);
    await imu.reset();                                                 // Soft reset, () → void
                                                                          // RESET register, waits 15 ms
    console.log(accel, gyro, temp, t, ts, ready, rax, ray, raz, rgx, rgy, rgz, fifoFlags, fifoBytes, data.length,
        taps, axis, negative, steps, calOk, codStatus, accelOk, gyroOk, accelSelf, gyroSelf, info, status);
    console.log('Complete example done');
}

main().catch(err => { console.error(err); process.exit(1); });
