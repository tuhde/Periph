#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

static void sleepMs(uint32_t ms) { delay(ms); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    Serial.print(label);
    Serial.print(" x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.print(" "); Serial.println(unit);
}
static void printNum(const char* label, float v, const char* unit) {
    Serial.print(label); Serial.print(" "); Serial.print(v, 2); Serial.print(" "); Serial.println(unit);
}
static void printInt(const char* label, long v) { Serial.print(label); Serial.print(" "); Serial.println(v); }
static void printText(const char* s) { Serial.println(s); }

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x1E);
    FXOS8700Full compass(connection);                       // Create FXOS8700 Full driver, (connection)

    // --- Configure hybrid mode ---
    // ±4 g keeps tilt maths precise while tolerating handling; magnetometer
    // oversampling ratio 7 gives the lowest noise at this data rate.
    compass.setRange(4);                                    // Set accelerometer range, (rangeG 2|4|8) → bool
    compass.setMagOversampling(7);                          // Set magnetometer oversampling, (ratio 0..7, sleepRatio=-1) → bool

    // --- Check the accelerometer ---
    // The electrostatic self-test must move every axis by the datasheet minimum.
    int16_t dx, dy, dz;
    bool ok = compass.selfTest(dx, dy, dz);                 // Run accelerometer self-test, (dx, dy, dz) → bool
    printText(ok ? "self-test OK" : "self-test FAILED");

    // --- Calibrate the hard-iron offsets ---
    // Auto-calibration writes the midpoint of the tracked min/max to the
    // offset registers every cycle; rotate the board through all
    // orientations meanwhile.
    compass.setMagAutoCalibration(true);                    // Hardware hard-iron calibration, (enabled) → void
    printText("Rotate the board in all directions for 15 s ...");
    for (int s = 0; s < 15; s++) {
        sleepMs(1000);
        float mn[3], mxv[3];
        compass.readMagMinMax(mn, mxv);                     // Read tracked min / max, (minUt[3], maxUt[3]) → µT
        printVec("span", mxv[0] - mn[0], mxv[1] - mn[1], mxv[2] - mn[2], "uT");
    }
    compass.setMagAutoCalibration(false);                   // Hardware hard-iron calibration, (enabled) → void
    float ox, oy, oz;
    compass.getMagOffset(ox, oy, oz);                       // Get hard-iron offsets, (xUt, yUt, zUt) → µT, µT, µT
    printVec("offsets", ox, oy, oz, "uT");

    // --- Arm orientation and motion detection ---
    // Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
    // chip to a low rate after 10 s without motion, and motion wakes it again.
    compass.setOrientation(true, 100.0f);                   // Configure orientation detection, (enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=2) → bool
    compass.setFreefallMotion(true, 7, 0.3f, 60.0f, true, false); // Configure freefall / motion, (motion, axes=7, thresholdG=0.3, debounceMs=0, latch=false, debounceClear=false) → void
    compass.setAutoSleep(true, 12.5f, 10.0f, FXOS8700Full::SOURCE_FF_MT); // Configure Auto-SLEEP, (enabled, sleepRateHz=50, timeoutS=10, wakeSources=0) → bool
    compass.enableInterrupt(FXOS8700Full::SOURCE_ORIENTATION | FXOS8700Full::SOURCE_FF_MT, 1); // Enable and route interrupt, (source mask, pin 1|2) → bool

    // --- Print the heading ---
    // Every 250 ms the tilt-compensated heading is shown with the board's
    // pitch and roll; the temperature is printed once per second.
    static const char* const ORIENTATIONS[4] = { "PORTRAIT UP", "PORTRAIT DOWN", "LANDSCAPE RIGHT", "LANDSCAPE LEFT" };
    static const char* const DIRECTIONS[16] = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
    for (int tick = 0; tick < 240; tick++) {
        float heading = compass.heading(3.0f);              // Compute tilt-compensated heading, (declinationDeg=0) → float degrees
        float ax, ay, az, mx, my, mz;
        compass.readHybrid(ax, ay, az, mx, my, mz);         // Read both sensors, (ax..az, mx..mz) → bool
        float roll = atan2f(ay, az) * 180.0f / 3.14159265f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.14159265f;
        printText(DIRECTIONS[(int)((heading + 11.25f) / 22.5f) % 16]);
        printNum("heading", heading, "deg");
        printNum("pitch", pitch, "deg");
        printNum("roll", roll, "deg");
        uint8_t status = compass.pollInterrupt();           // Poll and clear interrupt sources, () → uint8_t
        if (status & FXOS8700Full::SOURCE_ORIENTATION) {
            bool changed, lockout, back;
            uint8_t pl;
            compass.readOrientation(changed, lockout, pl, back); // Read orientation, (changed, lockout, portraitLandscape, back) → void
            printText(ORIENTATIONS[pl]);
        }
        if (status & FXOS8700Full::SOURCE_FF_MT) {
            printInt("MOTION, system mode", compass.readSystemMode()); // Read SYSMOD, () → uint8_t 0 standby | 1 wake | 2 sleep
        }
        if (tick % 4 == 0) {
            float temperature;
            compass.readTemperature(temperature);           // Read die temperature, (celsius) → bool
            printNum("temperature", temperature, "C");
        }
        sleepMs(250);
    }
}

void loop() { delay(1000); }
