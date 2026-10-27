#include <Wire.h>
#include <Periph.h>

I2CConnection accelConn(Wire, 0x19);                        // Accelerometer connection at 0x19
I2CConnection magConn(Wire, 0x1E);                          // Magnetometer connection at 0x1E

static const char* const COMPASS16[16] = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
};

// 16-point compass name for a heading in degrees (0–360).
static const char* compassName(float deg) {
    int idx = (int)((deg + 11.25f) / 22.5f) % 16;
    if (idx < 0) idx += 16;
    return COMPASS16[idx];
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(21, 22, 400000);                             // SDA=GPIO21, SCL=GPIO22
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    // --- Configure for a tilt-compensated compass with tap and free-fall detection ---
    // ±2 g and 50 Hz keep the rate low enough for a steady average; high-resolution mode
    // gives 1 mg/LSB. The magnetometer runs at 15 Hz, the default, within ±1.3 gauss.
    chip.setRange(2);                                       // Set accel full scale, (range_g=2) → bool
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
    chip.setDataRate(50);                                   // Set accel output rate, (rate_hz=50) → bool
    chip.setMagRange(130);                                  // Set mag full scale, (range_ut=130) → bool
    chip.setMagDataRate(15.0f);                             // Set mag output rate, (rate_hz=15) → bool

    // --- Arm the double-tap and free-fall detectors, both routed to INT1 ---
    // A double tap on Z (0x04) at 0.8 g with a 20 ms limit, 40 ms latency and 200 ms window.
    chip.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);     // Configure click, (single=0, double=0x04, threshold_g=0.8, ...) → void
    // Free-fall: all three axes below 0.35 g for 60 ms, latched until the source is read.
    chip.setFreeFall(1, 0.35f, 60.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=60) → bool
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=True) → bool
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool

    // --- Stream into the FIFO, 16-sample watermark ---
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=False) → bool

    unsigned long t0 = millis();
    while (millis() - t0 < 60000UL) {
        // --- Once a second: drain the FIFO, average, and read the magnetometer ---
        // Averaging the FIFO removes most per-sample noise before the tilt is computed.
        delay(1000);
        float xs[16], ys[16], zs[16];
        uint8_t n = chip.readFifo(xs, ys, zs, 16);          // Drain FIFO, (x, y, z, count=16) → uint8
        float ax = 0, ay = 0, az = 0;
        if (n > 0) {
            for (uint8_t i = 0; i < n; i++) { ax += xs[i]; ay += ys[i]; az += zs[i]; }
            ax /= n; ay /= n; az /= n;
        } else {
            chip.readAcceleration(ax, ay, az);              // Read acceleration, (x, y, z) → g, g, g
        }

        float mx, my, mz;
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        float hdg = chip.heading(true);                     // Tilt-compensated heading, (tilt_compensate=True) → deg
        float dT = chip.readTemperatureDelta();             // Read temperature, () → float °C

        // Pitch and roll from the averaged gravity vector (degrees, display only).
        float roll  = atan2(ay, az) * 180.0f / 3.14159265f;
        float pitch = atan2(-ax, sqrt(ay * ay + az * az)) * 180.0f / 3.14159265f;
        float fieldUt = sqrt(mx * mx + my * my + mz * mz);

        Serial.print("heading="); Serial.print(hdg, 1); Serial.print(" deg ");
        Serial.print(compassName(hdg)); Serial.print("  pitch=");
        Serial.print(pitch, 1); Serial.print(" roll=");
        Serial.print(roll, 1); Serial.print("  |B|=");
        Serial.print(fieldUt, 1); Serial.print(" uT  temp_delta=");
        Serial.print(dT, 2); Serial.print(" C  samples=");
        Serial.println(n);

        // --- Poll for events and report them ---
        // Reading the source registers clears the latched flags, so each event prints once.
        uint8_t status = chip.pollInterrupt();              // Poll interrupt sources, () → uint8
        if (status & LSM303Source::CLICK) Serial.println("DOUBLE TAP");
        if (status & LSM303Source::IA1)   Serial.println("FREE FALL");
    }

    Serial.println("done");
}

void loop() {}
