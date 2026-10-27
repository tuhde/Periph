#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Wire.h>
#include <Periph.h>

// Both connections are constructed globally (no I²C traffic in their constructors).
// The driver is constructed in setup(), after Wire is started, because its
// constructor performs I²C.
I2CConnection accelConn(Wire, 0x19);   // Accelerometer at 0x19
I2CConnection magConn(Wire, 0x1E);     // Magnetometer at 0x1E

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

static bool finite(float v) { return v == v && v > -1e30f && v < 1e30f; }

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel, mag)
    check_true("identity valid", chip.valid());

    uint8_t id[3] = {0, 0, 0};
    chip.readMagIdentity(id);                               // Read magnetometer identity, (buf) → void
    check_true("mag identity 0x48 0x34 0x33", id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33);

    delay(200);                                             // Wait for the first samples (7/ODR and 1/15 Hz)

    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
    check_true("accel_x finite", finite(ax));
    check_true("accel_y finite", finite(ay));
    check_true("accel_z finite", finite(az));
    float amag = sqrt(ax * ax + ay * ay + az * az);
    check_true("accel magnitude 0.5..1.5 g", amag > 0.5f && amag < 1.5f);

    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);                          // Read magnetic field, (x, y, z) → µT, µT, µT
    check_true("mag_x finite", finite(mx));
    check_true("mag_y finite", finite(my));
    check_true("mag_z finite", finite(mz));

    float h = chip.heading(false);                          // Flat compass heading, (tilt_compensate=False) → deg
    check_true("heading flat in 0..360", h >= 0.0f && h < 360.0f);
    float ht = chip.heading(true);                          // Tilt-compensated heading, (tilt_compensate=True) → deg
    check_true("heading tilt in 0..360", ht >= 0.0f && ht < 360.0f);

    float t = chip.readTemperatureDelta();                  // Relative temperature, () → float °C
    check_true("temperature delta finite", finite(t));

    check_true("set_range 4", chip.setRange(4));            // Set accel full scale, (range_g) → bool
    chip.readAcceleration(ax, ay, az);
    check_true("accel finite at ±4 g", finite(ax) && finite(ay) && finite(az));
    check_true("set_range 2", chip.setRange(2));
    check_true("set_range 3 rejected", !chip.setRange(3));

    check_true("set_mag_range 810", chip.setMagRange(810)); // Set mag full scale, (range_ut) → bool
    check_true("set_mag_range 130", chip.setMagRange(130));
    check_true("set_mag_data_rate 15", chip.setMagDataRate(15.0f));

    check_true("enable DATA_READY on INT1", chip.enableInterrupt(LSM303Source::DATA_READY, 1));
    check_true("disable DATA_READY on INT1", chip.disableInterrupt(LSM303Source::DATA_READY, 1));
    check_true("mag DRDY not routable", !chip.enableInterrupt(LSM303Source::MAG_DATA_READY, 1));
    uint8_t status = chip.pollInterrupt();                  // Poll interrupt sources, () → status
    check_true("poll status only carries mag DRDY", status == 0x00 || status == 0x80);

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() {}
