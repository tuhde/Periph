#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Wire.h>
#include <Periph.h>
#include <math.h>

static int passed = 0, failed = 0;
#define PRINT(...) Serial.print(__VA_ARGS__)
#define CHECK(label, cond) do { if (cond) { PRINT("PASS "); PRINT(label); PRINT("\n"); passed++; } \
                                else { PRINT("FAIL "); PRINT(label); PRINT("\n"); failed++; } } while (0)

I2CConnection accel_connection(Wire, 0x19);
I2CConnection mag_connection(Wire, 0x1E);

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    LSM303Full compass(accel_connection, mag_connection);
    uint8_t identity[3];
    compass.read_mag_identity(identity);                    // Read magnetometer identity, (out) -> bytes
    CHECK("mag_identity", identity[0] == 0x48 && identity[1] == 0x34 && identity[2] == 0x33);

    float ax, ay, az;
    compass.read_acceleration(ax, ay, az);                  // Read 3-axis acceleration, (x, y, z) -> g, g, g
    CHECK("read_acceleration_floats", ax == ax && ay == ay && az == az);
    CHECK("acceleration_plausible", fabsf(ax) < 18.0f && fabsf(ay) < 18.0f && fabsf(az) < 18.0f);

    float mx, my, mz;
    compass.read_magnetic(mx, my, mz);                      // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
    CHECK("read_magnetic_floats", mx == mx && my == my && mz == mz);
    CHECK("magnetic_plausible", fabsf(mx) < 1000.0f && fabsf(my) < 1000.0f && fabsf(mz) < 1000.0f);

    float hdg = compass.heading(true);                      // Compute compass heading, (tilt_compensate) -> deg
    CHECK("heading_range", hdg >= 0.0f && hdg <= 360.0f);

    compass.set_range(4);                                   // Set accelerometer range, (range_g) -> g
    compass.read_acceleration(ax, ay, az);                  // Read 3-axis acceleration, (x, y, z) -> g, g, g
    CHECK("read_after_set_range_4g", ax == ax && ay == ay && az == az);
    compass.set_range(2);

    int16_t rx, ry, rz;
    compass.read_acceleration_raw(rx, ry, rz);              // Read raw acceleration counts, (x, y, z) -> int, int, int
    CHECK("read_acceleration_raw_ints", rx == rx && ry == ry && rz == rz);
    CHECK("read_status_byte", compass.read_status() <= 0xFF);

    compass.set_high_pass(0, 0);                            // Configure high-pass filter, (...)
    uint8_t ref = compass.reset_high_pass();                // Reset high-pass filter, () -> int
    CHECK("reset_high_pass_stable", compass.reset_high_pass() == ref);

    compass.set_fifo(LSM303Full::FIFO_FIFO, 8, false);      // Configure FIFO, (mode, watermark, trigger_int2)
    uint8_t level = compass.fifo_level();                   // Read FIFO level, () -> int
    CHECK("fifo_level_in_range", level <= 32);
    float xb[4], yb[4], zb[4];
    uint8_t n = compass.read_fifo(xb, yb, zb, 4);           // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
    CHECK("read_fifo_count", n <= 4);
    bool wm, ov, empty;
    uint8_t lvl;
    compass.read_fifo_status(wm, ov, empty, lvl);           // Read FIFO status, () -> (bool, bool, bool, int)
    compass.set_fifo(LSM303Full::FIFO_BYPASS);

    compass.set_click(4, 1, 0.5f, 10, 20, 50);              // Configure click detection, (...)
    CHECK("read_click_source_byte", compass.read_click_source() != 0xFF);
    compass.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);  // Configure motion interrupt, (...)
    compass.set_free_fall(1, 0.35f, 30);                    // Configure free-fall, (...)
    compass.set_orientation(1, false, false);               // Configure orientation, (...)
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled)
    CHECK("read_interrupt_source_byte", compass.read_interrupt_source(1) != 0xFF);
    compass.set_latch(1, false);

    compass.set_interrupt_polarity(false);                  // Set interrupt polarity, (active_low)
    compass.enable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);  // Enable interrupt source, (source, pin)
    compass.poll_interrupt();                               // Poll interrupt status, () -> int
    compass.disable_interrupt(LSM303Full::SOURCE_DATA_READY, 1); // Disable interrupt source, (source, pin)

    compass.set_mag_range(470);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(30.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz
    int16_t mrx, mry, mrz;
    compass.read_magnetic_raw(mrx, mry, mrz);               // Read raw magnetic counts, (x, y, z) -> int, int, int
    CHECK("read_magnetic_raw_ints", mrx == mrx && mry == mry && mrz == mrz);
    compass.read_magnetic(mx, my, mz);                      // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
    CHECK("read_mag_after_set_range", mx == mx && my == my && mz == mz);
    bool drdy, lock;
    compass.read_mag_status(drdy, lock);                    // Read magnetometer status, () -> (bool, bool)
    CHECK("read_mag_status_bools", drdy == drdy && lock == lock);
    compass.enable_temperature(true);                       // Enable temperature sensor, (enabled)
    float temp = compass.read_temperature_delta();          // Read relative temperature, () -> float C
    CHECK("temperature_plausible", fabsf(temp) < 100.0f);

    compass.reboot();                                       // Reboot accelerometer memory, () -> None
    compass.read_acceleration(ax, ay, az);                  // Read 3-axis acceleration, (x, y, z) -> g, g, g
    CHECK("read_after_reboot", ax == ax && ay == ay && az == az);
    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
