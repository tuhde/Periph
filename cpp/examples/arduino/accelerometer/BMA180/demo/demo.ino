#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x40);
BMA180Full accel(connection);                           // Create BMA180 Full driver, (connection)

void setup() {
    Serial.begin(115200);
    Wire.begin();
    delay(2000);

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz

    // --- Calibrate zero-g while the board sits level ---
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None

    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    accel.set_tap(0.5, 250);                               // Configure tap, (threshold_g, window_ms) → None
    accel.set_low_g(0.3, 40);                              // Configure low-g, (threshold_g, duration_ms) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None
}

void loop() {
    float x, y, z;
    accel.read(x, y, z);                                   // Read 3-axis acceleration, (x, y, z) → g, g, g
    float pitch = atan2(x, sqrt(y * y + z * z)) * 180.0 / 3.14159265;
    float roll  = atan2(y, sqrt(x * x + z * z)) * 180.0 / 3.14159265;
    float mag   = sqrt(x * x + y * y + z * z);
    float t     = accel.read_temperature();                // Read temperature, () → °C
    Serial.print("pitch="); Serial.print(pitch, 1);
    Serial.print(" roll="); Serial.print(roll, 1);
    Serial.print(" |a|="); Serial.print(mag, 3);
    Serial.print(" T="); Serial.print(t, 1); Serial.println(" C");

    uint8_t flags = accel.poll_interrupt();                // Read STATUS_REG3, () → bitmask
    if (flags & 0x10) {
        Serial.println("DOUBLE TAP");
        accel.clear_interrupt();                            // Clear latched interrupts, () → None
    }
    if (flags & 0x40) {
        Serial.println("FREE FALL");
        accel.clear_interrupt();                            // Clear latched interrupts, () → None
    }
    delay(100);
}