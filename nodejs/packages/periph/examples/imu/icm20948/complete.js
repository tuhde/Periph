'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ICM20948Full } = require('../../../src/chips/imu/icm20948');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new ICM20948Full(connection);                    // Create ICM20948 driver, (connection) → void
    await imu.init();                                            // Wait for initialization, () → void
                                                                 // runs the WHO_AM_I check and the default configuration

    const [ax, ay, az] = await imu.accel();                      // Read 3-axis acceleration, () → [number, number, number] m/s²
                                                                 // converts raw accel registers to m/s² (16384 LSB/g at ±2g)
    const [gx, gy, gz] = await imu.gyro();                       // Read 3-axis angular rate, () → [number, number, number] rad/s
                                                                 // converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
    const [accel, gyro, temp] = await imu.readAll();             // Read one coherent sample, () → [[number x3], [number x3], number] m/s², rad/s, °C
                                                                 // burst-reads registers 0x2D…0x3A so all three belong to one sample
    const t = await imu.temperature();                           // Read die temperature, () → number °C
                                                                 // converts raw temp register: raw/333.87 + 21.0

    const [rax, ray, raz] = await imu.accelRaw();                // Read raw accel values, () → [number, number, number]
                                                                 // returns signed 16-bit accelerometer counts
    const [rgx, rgy, rgz] = await imu.gyroRaw();                 // Read raw gyro values, () → [number, number, number]
                                                                 // returns signed 16-bit gyroscope counts

    await imu.configureGyro(1);                                  // Configure gyro range, (fullScale=0) → void
                                                                 // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    await imu.configureAccel(1);                                 // Configure accel range, (fullScale=0) → void
                                                                 // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    await imu.configureFilters(3, 3, true, true);                // Configure low-pass filters, (gyroDlpf=3, accelDlpf=3, gyroFilter=true, accelFilter=true) → void
                                                                 // sets *_DLPFCFG and *_FCHOICE; filter false bypasses the filter
    await imu.configureSampleRate(10, 10);                       // Configure sample rate, (gyroDiv=10, accelDiv=10) → void
                                                                 // sets the dividers; ODR = 1125 Hz / (1 + div)
    await imu.setSensors(true, true);                            // Power sensors, (accel=true, gyro=true) → void
                                                                 // writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
    await imu.setLowPower(false, false, 0, 0);                   // Configure duty-cycled mode, (accel=false, gyro=false, accelAvg=0, gyroAvg=0) → void
                                                                 // writes LP_CONFIG and averaging; LP_EN is set last
    await imu.setGyroOffset(0, 0, 0);                            // Set gyro offsets, (x, y, z) rad/s → void
                                                                 // writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
    const [ox, oy, oz] = await imu.getAccelOffset();             // Read accel offset codes, () → [number, number, number]
                                                                 // reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
    await imu.setAccelOffset(ox, oy, oz);                        // Write accel offset codes, (x, y, z) → void
                                                                 // writes the 15-bit offsets back unchanged

    await imu.enableMag();                                       // Enable magnetometer, () → void
                                                                 // starts the I²C master and reads the AK09916 block continuously
    const [mx, my, mz] = await imu.mag();                        // Read 3-axis magnetic field, () → [number, number, number] µT
                                                                 // converts raw counts at 0.15 µT/LSB; rejects on HOFL
    const [rmx, rmy, rmz] = await imu.magRaw();                  // Read raw mag values, () → [number, number, number]
                                                                 // returns signed 16-bit magnetometer counts
    const magReady = await imu.magDataReady();                   // Check magnetometer data ready, () → boolean
                                                                 // reads ST1.DRDY from the shadowed block
    await imu.magSetMode(8);                                     // Set magnetometer mode, (mode) → void
                                                                 // writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
    const who = await imu.auxReadByte(0x0C, 0x01);               // Read auxiliary byte, (address, register) → number
                                                                 // reads AK09916 WIA2 (0x09) through I²C-master slave 4
    await imu.auxWriteByte(0x0C, 0x31, 0x08);                    // Write auxiliary byte, (address, register, value) → void
                                                                 // writes AK09916 CNTL2 through slave 4

    const ready = await imu.dataReady();                         // Check data ready flag, () → boolean
                                                                 // reads INT_STATUS_1.RAW_DATA_0_RDY_INT

    await imu.enableFifo(true, true, false);                     // Enable FIFO sources, (accel=true, gyro=true, temp=false) → void
                                                                 // sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
    await imu.setFifoMode(false);                                // Select FIFO mode, (snapshot=false) → void
                                                                 // 0 = stream (overwrite oldest), 1 = snapshot (stop when full)
    await sleep(50);
    const count = await imu.fifoCount();                         // Read FIFO byte count, () → number
                                                                 // reads FIFO_COUNTH/L: number of bytes available
    const data = await imu.readFifo();                           // Read FIFO data, (count?) → Buffer
                                                                 // reads available bytes from FIFO_R_W in chunks
    await imu.resetFifo();                                       // Reset FIFO buffer, () → void
                                                                 // asserts and de-asserts FIFO_RST

    await imu.setWakeOnMotion(200, true);                        // Arm wake-on-motion, (thresholdMg, enabled=true) → void
                                                                 // writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
    await imu.setInterruptPin(false, false, true, false);        // Configure INT1 pin, (activeLow=false, openDrain=false, latch=false, anyReadClears=false) → void
                                                                 // writes INT_PIN_CFG
    await imu.enableInterrupt(ICM20948Full.SOURCE_DATA_READY);   // Enable interrupt source, (source) → void
                                                                 // sets the source's enable bit in INT_ENABLE*
    const status = await imu.pollInterrupt();                    // Read interrupt status, () → number
                                                                 // reads INT_STATUS, _1, _2 (clearing them) into SOURCE_* bits
    await imu.disableInterrupt(ICM20948Full.SOURCE_DATA_READY);  // Disable interrupt source, (source) → void
                                                                 // clears the source's enable bit
    await imu.onInterrupt((s) => console.log('INT', s));         // Subscribe to INT1, (callback) → void
                                                                 // callback receives the status from pollInterrupt
    await imu.offInterrupt();                                    // Unsubscribe, () → void
                                                                 // removes the callback

    const [stAccel, stGyro] = await imu.selfTest();              // Run self-test, () → [[number x3], [number x3]]
                                                                 // raw response (enabled minus disabled) per axis; no pass/fail
    const tbc = await imu.readTimebaseCorrection();              // Read PLL time-base correction, () → number %
                                                                 // reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)

    await imu.setSleep(true);                                    // Enter sleep mode, (sleep=true) → void
                                                                 // sets SLEEP bit in PWR_MGMT_1
    await sleep(10);
    await imu.setSleep(false);                                   // Wake from sleep, (sleep=true) → void
                                                                 // clears SLEEP bit in PWR_MGMT_1

    console.log('t=%s  mag=%s,%s,%s  ready=%s', t.toFixed(2), mx.toFixed(1), my.toFixed(1), mz.toFixed(1), ready);
    void [ax, ay, az, gx, gy, gz, accel, gyro, temp, rax, ray, raz, rgx, rgy, rgz, rmx, rmy, rmz,
          magReady, who, count, data, status, stAccel, stGyro, tbc];
    await imu.reset();                                           // Reset the device, () → void
                                                                 // DEVICE_RESET and wait; create a new driver afterwards
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
