'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MPU6050Full } = require('../../../src/chips/imu/mpu6050');

const transport = new I2CTransport(1, 0x68);
const imu = new MPU6050Full(transport);                  // Create MPU6050 driver, (transport, addr=0x68) → None

const [ax, ay, az] = imu.accel();                        // Read 3-axis acceleration, () → [float, float, float] m/s²
                                                         // converts raw accel register to m/s² (16384 LSB/g at ±2g)
const [gx, gy, gz] = imu.gyro();                         // Read 3-axis angular rate, () → [float, float, float] rad/s
                                                         // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

imu.configureGyro(1);                                     // Configure gyro range, (fullScale=0) → None
                                                         // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
imu.configureAccel(1);                                    // Configure accel range, (fullScale=0) → None
                                                         // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
imu.configureDlpf(3);                                     // Configure DLPF bandwidth, (dlpf=3) → None
                                                         // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
imu.configureSampleRate(4);                               // Configure sample rate, (divider=4) → None
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

console.log('temp:', imu.temperature());                  // Read die temperature, () → float °C
                                                         // converts raw temp register: raw/340 + 36.53

const [rax, ray, raz] = imu.accelRaw();                  // Read raw accel values, () → [int, int, int]
                                                         // returns raw 16-bit signed accelerometer register values
const [rgx, rgy, rgz] = imu.gyroRaw();                   // Read raw gyro values, () → [int, int, int]
                                                         // returns raw 16-bit signed gyroscope register values

console.log('data_ready:', imu.dataReady());              // Check data ready flag, () → bool
                                                         // reads DATA_RDY_INT bit from INT_STATUS register

imu.setSleep(true);                                       // Enter sleep mode, (sleep=true) → None
                                                         // sets SLEEP bit in PWR_MGMT_1
const end1 = Date.now() + 10;
while (Date.now() < end1) {}
imu.setSleep(false);                                      // Wake from sleep, (sleep=true) → None
                                                         // clears SLEEP bit in PWR_MGMT_1

imu.setStandby(true, false, false, false, false, false); // Set axes standby, (xa, ya, za, xg, yg, zg) → None
                                                         // puts individual axes into low-power standby mode
imu.setStandby();                                         // Clear all standby, (xa=false, ...) → None
                                                         // restores all axes from standby

imu.enableFifo(true, true, false);                        // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
                                                         // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
imu.resetFifo();                                          // Reset FIFO buffer, () → None
                                                         // sets FIFO_RST bit in USER_CTRL to clear the buffer
console.log('fifo_count:', imu.fifoCount());              // Read FIFO byte count, () → int
                                                         // reads FIFO_COUNTH/L: number of bytes available
const data = imu.readFifo();                              // Read FIFO data, () → Buffer
                                                         // reads all available bytes from FIFO_R_W register
console.log('fifo read:', data.length, 'bytes');

transport.close();
