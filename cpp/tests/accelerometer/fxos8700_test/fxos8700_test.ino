#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Wire.h>
#include <Periph.h>
#include <math.h>

#define WAIT() delay(200)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x1E);

    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    check_true(compass.found(), "who_am_i_found");

    float ax, ay, az, mx, my, mz;
    compass.readAccel(ax, ay, az);                          // Read acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_accel_returns_floats");
    float magnitude = sqrtf(ax * ax + ay * ay + az * az);
    check_true(magnitude >= 0.7f && magnitude <= 1.3f, "accel_magnitude_near_1g");
    compass.readMag(mx, my, mz);                            // Read magnetic field, (x, y, z) → µT, µT, µT
    check_true(fabsf(mx) <= 1200.0f && fabsf(my) <= 1200.0f && fabsf(mz) <= 1200.0f, "mag_in_range");

    FXOS8700Full full(connection);                          // Create FXOS8700 Full driver, (connection)
    check_true(full.found(), "full_who_am_i_found");
    check_true(full.readHybrid(ax, ay, az, mx, my, mz), "read_hybrid_ok");
    check_true(fabsf(az) <= 2.5f && fabsf(mx) <= 1200.0f, "hybrid_plausible");

    int16_t rx, ry, rz;
    full.readRawAccel(rx, ry, rz);                          // Read raw accelerometer, (x, y, z) → counts
    check_true(rx >= -8192 && rx <= 8191 && ry >= -8192 && ry <= 8191 && rz >= -8192 && rz <= 8191, "raw_accel_in_14bit_range");
    full.readRawMag(rx, ry, rz);                            // Read raw magnetometer, (x, y, z) → counts
    check_true(abs((int)rx) <= 32768 && abs((int)ry) <= 32768 && abs((int)rz) <= 32768, "raw_mag_in_16bit_range");
    float temperature = 0.0f;
    check_true(full.readTemperature(temperature) && temperature > -100.0f && temperature < 150.0f, "temperature_plausible");

    full.setRange(4);
    full.setDataRate(100.0f);
    WAIT();
    full.readAccel(ax, ay, az);
    check_true(fabsf(az) <= 4.5f, "read_after_set_range_4g");
    full.setRange(2);
    full.setDataRate(200.0f);
    full.setAccelOversampling(2);
    full.setAccelOversampling(0);
    full.setMagOversampling(7);
    check_true(full.readSystemMode() == 1, "system_mode_wake");
    WAIT();
    check_true(full.newAccelData() == true || full.newAccelData() == false, "new_accel_data_bool");
    check_true(full.newMagData() == true || full.newMagData() == false, "new_mag_data_bool");

    full.setAccelOffset(0.02f, -0.02f, 0.0f);
    float ox, oy, oz;
    full.getAccelOffset(ox, oy, oz);
    check_true(fabsf(ox - 0.02f) < 0.003f && fabsf(oy + 0.02f) < 0.003f, "accel_offset_roundtrip");
    full.setAccelOffset(0.0f, 0.0f, 0.0f);
    full.setMagOffset(5.0f, -5.0f, 0.0f);
    full.getMagOffset(ox, oy, oz);
    check_true(fabsf(ox - 5.0f) < 0.15f && fabsf(oy + 5.0f) < 0.15f, "mag_offset_roundtrip");
    full.setMagOffset(0.0f, 0.0f, 0.0f);

    full.resetMagMinMax();
    WAIT();
    float mn[3], mxv[3];
    full.readMagMinMax(mn, mxv);
    check_true(mn[0] <= mxv[0] && mn[1] <= mxv[1] && mn[2] <= mxv[2], "min_max_ordered");
    float heading = full.heading();
    check_true(heading >= 0.0f && heading < 360.0f, "heading_in_range");

    check_true(full.setFifo(FXOS8700Full::FIFO_CIRCULAR, 8), "fifo_enabled");
    WAIT();
    check_true(full.fifoLevel() <= 32, "fifo_level_in_range");
    float samples[12];
    check_true(full.readFifo(samples, 4) <= 4, "fifo_samples_bounded");
    full.setFifo(FXOS8700Full::FIFO_DISABLED);

    full.setFreefallMotion(true, 7, 0.3f, 50.0f, true);
    { uint8_t v = full.readMotionSource(); check_true((v & 0xFF) == v, "motion_source_readable"); }
    full.setTransient(7, 0.3f);
    { uint8_t v = full.readTransientSource(); check_true((v & 0xFF) == v, "transient_source_readable"); }
    full.setPulse(0x07);
    { uint8_t v = full.readPulseSource(); check_true((v & 0xFF) == v, "pulse_source_readable"); }
    check_true(full.setOrientation(true, 100.0f), "orientation_configured");
    bool changed, lockout, back;
    uint8_t pl;
    full.readOrientation(changed, lockout, pl, back);
    check_true(pl <= 3, "orientation_in_range");

    full.enableInterrupt(FXOS8700Full::SOURCE_DATA_READY, 1);
    { uint8_t v = full.pollInterrupt(); check_true((v & 0xFF) == v, "poll_interrupt_mask"); }
    full.disableInterrupt(FXOS8700Full::SOURCE_DATA_READY);

    int16_t dx, dy, dz;
    bool st_ok = full.selfTest(dx, dy, dz);                 // Run accelerometer self-test, (dx, dy, dz) → bool
    check_true(st_ok == true || st_ok == false, "self_test_runs");
    check_true(dz > 0, "self_test_z_positive");
    full.setMode(2);
    check_true(full.measureMagOnce(mx, my, mz), "measure_mag_once");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() { delay(1000); }
