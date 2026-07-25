#include <Wire.h>
#include "I2CTransport.h"
#include "Mpu6050.h"

I2CTransport transport(Wire, 0x68);
MPU6050Full imu(transport);                              // Create MPU6050 driver, (transport, addr=0x68) → None

void setup() {
    Serial.begin(115200);
    Wire.begin();

    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                               // Read 3-axis acceleration, (x, y, z) → m/s²
                                                         // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    imu.gyro(gx, gy, gz);                                // Read 3-axis angular rate, (x, y, z) → rad/s
                                                         // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None
                                                         // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
                                                         // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    imu.configure_dlpf(3);                                // Configure DLPF bandwidth, (dlpf=3) → None
                                                         // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
    imu.configure_sample_rate(4);                         // Configure sample rate, (divider=4) → None
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    Serial.println(imu.temperature());                    // Read die temperature, () → °C
                                                         // converts raw temp register: raw/340 + 36.53

    int16_t rax, ray, raz;
    imu.accel_raw(rax, ray, raz);                         // Read raw accel values, (x, y, z) → int16_t
                                                         // returns raw 16-bit signed accelerometer register values
    int16_t rgx, rgy, rgz;
    imu.gyro_raw(rgx, rgy, rgz);                          // Read raw gyro values, (x, y, z) → int16_t
                                                         // returns raw 16-bit signed gyroscope register values

    Serial.println(imu.data_ready());                     // Check data ready flag, () → bool
                                                         // reads DATA_RDY_INT bit from INT_STATUS register

    imu.set_sleep(true);                                  // Enter sleep mode, (sleep=true) → None
                                                         // sets SLEEP bit in PWR_MGMT_1
    delay(10);
    imu.set_sleep(false);                                 // Wake from sleep, (sleep=true) → None
                                                         // clears SLEEP bit in PWR_MGMT_1

    imu.set_standby(true, false, false, true, false, false); // Set axes standby, (xa, ya, za, xg, yg, zg) → None
                                                         // puts individual axes into low-power standby mode
    imu.set_standby();                                    // Clear all standby, (xa=false, ...) → None
                                                         // restores all axes from standby

    imu.enable_fifo(true, true, false);                   // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
                                                         // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
    imu.reset_fifo();                                     // Reset FIFO buffer, () → None
                                                         // sets FIFO_RST bit in USER_CTRL to clear the buffer
    Serial.println(imu.fifo_count());                     // Read FIFO byte count, () → uint16_t
                                                         // reads FIFO_COUNTH/L: number of bytes available
    uint8_t buf[128];
    uint16_t n = imu.read_fifo(buf, sizeof(buf));         // Read FIFO data, (buf, len) → uint16_t
                                                         // reads all available bytes from FIFO_R_W register
    Serial.print("FIFO read: "); Serial.println(n);
}

void loop() {}
