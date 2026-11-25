#include <Wire.h>
#include <Periph.h>
I2CConnection connection(Wire, 0x68);
ICM20948Full imu(connection);                      // Create ICM20948 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    delay(2000);

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
                                                  // converts raw accel registers to m/s² (16384 LSB/g at ±2g)
    imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
                                                  // converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
    float a[3], g[3], t;
    imu.read_all(a, g, t);                        // Read one coherent sample, (float[3], float[3], float&) → void m/s², rad/s, °C
                                                  // burst-reads registers 0x2D…0x3A so all three belong to one sample
    t = imu.temperature();                        // Read die temperature, () → float °C
                                                  // converts raw temp register: raw/333.87 + 21.0
    int16_t rax, ray, raz, rgx, rgy, rgz, rmx, rmy, rmz;
    imu.accel_raw(rax, ray, raz);                 // Read raw accel values, (int16_t&, int16_t&, int16_t&) → void
                                                  // returns signed 16-bit accelerometer counts
    imu.gyro_raw(rgx, rgy, rgz);                  // Read raw gyro values, (int16_t&, int16_t&, int16_t&) → void
                                                  // returns signed 16-bit gyroscope counts

    imu.configure_gyro(1);                        // Configure gyro range, (full_scale=0) → void
                                                  // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    imu.configure_accel(1);                       // Configure accel range, (full_scale=0) → void
                                                  // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    imu.configure_filters(3, 3, true, true);      // Configure low-pass filters, (gyro_dlpf=3, accel_dlpf=3, gyro_filter=true, accel_filter=true) → void
                                                  // sets *_DLPFCFG and *_FCHOICE; filter false bypasses the filter
    imu.configure_sample_rate(10, 10);            // Configure sample rate, (gyro_div=10, accel_div=10) → void
                                                  // sets the dividers; ODR = 1125 Hz / (1 + div)
    imu.set_sensors(true, true);                  // Power sensors, (accel=true, gyro=true) → void
                                                  // writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
    imu.set_low_power(false, false, 0, 0);        // Configure duty-cycled mode, (accel=false, gyro=false, accel_avg=0, gyro_avg=0) → void
                                                  // writes LP_CONFIG and averaging; LP_EN is set last
    imu.set_gyro_offset(0.0f, 0.0f, 0.0f);        // Set gyro offsets, (x, y, z) rad/s → void
                                                  // writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
    int16_t ox, oy, oz;
    imu.get_accel_offset(ox, oy, oz);             // Read accel offset codes, (int16_t&, int16_t&, int16_t&) → void
                                                  // reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
    imu.set_accel_offset(ox, oy, oz);             // Write accel offset codes, (x, y, z) → void
                                                  // writes the 15-bit offsets back unchanged

    bool mag_ok = imu.enable_mag();               // Enable magnetometer, () → bool
                                                  // starts the I²C master and reads the AK09916 block continuously
    float mx = 0, my = 0, mz = 0;
    imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → bool µT
                                                  // converts raw counts at 0.15 µT/LSB; false on HOFL
    imu.mag_raw(rmx, rmy, rmz);                   // Read raw mag values, (int16_t&, int16_t&, int16_t&) → bool
                                                  // returns signed 16-bit magnetometer counts
    bool mag_ready = imu.mag_data_ready();        // Check magnetometer data ready, () → bool
                                                  // reads ST1.DRDY from the shadowed block
    imu.mag_set_mode(8);                          // Set magnetometer mode, (mode) → bool
                                                  // writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
    uint8_t who = 0;
    imu.aux_read_byte(0x0C, 0x01, who);           // Read auxiliary byte, (address, reg, uint8_t&) → bool
                                                  // reads AK09916 WIA2 (0x09) through I²C-master slave 4
    imu.aux_write_byte(0x0C, 0x31, 0x08);         // Write auxiliary byte, (address, reg, value) → bool
                                                  // writes AK09916 CNTL2 through slave 4

    bool ready = imu.data_ready();                // Check data ready flag, () → bool
                                                  // reads INT_STATUS_1.RAW_DATA_0_RDY_INT

    imu.enable_fifo(true, true, false);           // Enable FIFO sources, (accel=true, gyro=true, temp=false) → void
                                                  // sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
    imu.set_fifo_mode(false);                     // Select FIFO mode, (snapshot=false) → void
                                                  // 0 = stream (overwrite oldest), 1 = snapshot (stop when full)
    delay(50);
    uint16_t count = imu.fifo_count();            // Read FIFO byte count, () → uint16_t
                                                  // reads FIFO_COUNTH/L: number of bytes available
    uint8_t data[256];
    uint16_t read = imu.read_fifo(data, 256);     // Read FIFO data, (uint8_t*, uint16_t) → uint16_t
                                                  // reads available bytes from FIFO_R_W in chunks
    imu.reset_fifo();                             // Reset FIFO buffer, () → void
                                                  // asserts and de-asserts FIFO_RST

    imu.set_wake_on_motion(200.0f, true);         // Arm wake-on-motion, (threshold_mg, enabled=true) → void
                                                  // writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
    imu.set_interrupt_pin(false, false, true, false); // Configure INT1 pin, (active_low=false, open_drain=false, latch=false, any_read_clears=false) → void
                                                  // writes INT_PIN_CFG
    imu.enable_interrupt(ICM20948Full::SOURCE_DATA_READY); // Enable interrupt source, (source) → void
                                                  // sets the source's enable bit in INT_ENABLE*
    uint8_t status = imu.poll_interrupt();        // Read interrupt status, () → uint8_t
                                                  // reads INT_STATUS, _1, _2 (clearing them) into SOURCE_* bits
    imu.disable_interrupt(ICM20948Full::SOURCE_DATA_READY); // Disable interrupt source, (source) → void
                                                  // clears the source's enable bit

    float st_accel[3], st_gyro[3];
    imu.self_test(st_accel, st_gyro);             // Run self-test, (float[3], float[3]) → void
                                                  // raw response (enabled minus disabled) per axis; no pass/fail
    float tbc = imu.read_timebase_correction();   // Read PLL time-base correction, () → float %
                                                  // reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)

    imu.set_sleep(true);                          // Enter sleep mode, (sleep=true) → void
                                                  // sets SLEEP bit in PWR_MGMT_1
    delay(10);
    imu.set_sleep(false);                         // Wake from sleep, (sleep=true) → void
                                                  // clears SLEEP bit in PWR_MGMT_1

    Serial.print("t="); Serial.print(t, 2); Serial.print("  mag="); Serial.print(mx, 1); Serial.print(","); Serial.print(my, 1); Serial.print(","); Serial.print(mz, 1); Serial.print("  ready="); Serial.println(ready);
    (void)ax; (void)ay; (void)az; (void)gx; (void)gy; (void)gz; (void)a; (void)g; (void)tbc; (void)st_accel; (void)st_gyro;
    (void)rax; (void)ray; (void)raz; (void)rgx; (void)rgy; (void)rgz; (void)rmx; (void)rmy; (void)rmz;
    (void)count; (void)read; (void)status; (void)who; (void)mag_ok; (void)mag_ready; (void)data;
    Serial.println("Complete example done");
}

void loop() {
    delay(1000);
}
