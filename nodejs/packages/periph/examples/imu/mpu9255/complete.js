'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPU9255Full } = require('../../packages/periph/src/chips/imu/mpu9255');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const magConnection = new I2CConnection(I2C_BUS, 0x0C);              // AK8963, same bus, reached via I²C bypass
const imu = new MPU9255Full(connection, magConnection);              // Create MPU9255 driver, (connection, magConnection) → void

async function main() {
    const [ax, ay, az] = await imu.accel();                          // Read 3-axis acceleration, () → [number, number, number] m/s²
                                                        // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    const [gx, gy, gz] = await imu.gyro();                           // Read 3-axis angular rate, () → [number, number, number] rad/s
                                                        // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

    await imu.configureGyro(1);                                      // Configure gyro range, (fullScale=0) → void
                                                        // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    await imu.configureAccel(1);                                     // Configure accel range, (fullScale=0) → void
                                                        // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    await imu.configureDlpf(3, 3);                                   // Configure DLPF bandwidth, (gyroDlpf=3, accelDlpf=3) → void
                                                        // sets DLPF_CFG/A_DLPFCFG: 0=256/460Hz … 6=5/10Hz (gyro/accel BW)
    await imu.configureSampleRate(4);                                // Configure sample rate, (divider=4) → void
                                                        // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    const t = await imu.temperature();                               // Read die temperature, () → number °C
                                                        // converts raw temp register: raw/333.87 + 21.0

    await imu.enableMag(16, 6);                                      // Initialize magnetometer, (bits=16, mode=6) → void
                                                        // reads ASA calibration, sets 16-bit 100 Hz continuous mode
    const [mx, my, mz] = await imu.mag();                            // Read 3-axis magnetic field, () → [number, number, number] µT
                                                        // applies factory ASA calibration and sensitivity scaling

    const [rax, ray, raz] = await imu.accelRaw();                    // Read raw accel values, () → [number, number, number]
                                                        // returns raw 16-bit signed accelerometer register values
    const [rgx, rgy, rgz] = await imu.gyroRaw();                     // Read raw gyro values, () → [number, number, number]
                                                        // returns raw 16-bit signed gyroscope register values
    const [rmx, rmy, rmz] = await imu.magRaw();                      // Read raw mag values, () → [number, number, number]
                                                        // returns raw 16-bit signed magnetometer register values

    const ready = await imu.dataReady();                             // Check data ready flag, () → boolean
                                                        // reads RAW_DATA_RDY_INT bit from INT_STATUS register

    await imu.setSleep(true);                                        // Enter sleep mode, (sleep=true) → void
                                                        // sets SLEEP bit in PWR_MGMT_1
    await new Promise(r => setTimeout(r, 10));
    await imu.setSleep(false);                                       // Wake from sleep, (sleep=true) → void
                                                        // clears SLEEP bit in PWR_MGMT_1

    await imu.resetFifo();                                           // Reset FIFO buffer, () → void
                                                        // sets FIFO_RST bit in USER_CTRL to clear the buffer
    await imu.enableFifo(true, true);                                // Enable FIFO sources, (gyro=true, accel=true, temp=false) → void
                                                        // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
    await new Promise(r => setTimeout(r, 50));
    const count = await imu.fifoCount();                             // Read FIFO byte count, () → number
                                                        // reads FIFO_COUNTH/L: number of bytes available
    const data = await imu.readFifo();                               // Read FIFO data, () → Buffer
                                                        // reads all available bytes from FIFO_R_W register
    await imu.resetFifo();                                           // Reset FIFO buffer, () → void
                                                        // sets FIFO_RST bit in USER_CTRL to clear the buffer

    await imu.configureWakeOnMotion(64, 31.25);                      // Configure wake-on-motion, (thresholdMg=64, odrHz=31.25) → void
                                                        // disables gyro, arms hardware motion detection at 31.25 Hz
    const motion = await imu.motionDetected();                       // Check motion detected, () → boolean
                                                        // reads WOM_INT bit from INT_STATUS register (clears on read)

    console.log('t=%.2f  mag=%.1f,%.1f,%.1f  ready=%s  motion=%s',
                t, mx, my, mz, ready, motion);
}

main().catch(err => { console.error(err); process.exit(1); });