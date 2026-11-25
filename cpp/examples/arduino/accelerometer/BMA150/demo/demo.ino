#include <Wire.h>
#include <math.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x38);
BMA150Full accel(connection);                           // Create BMA150 driver, (connection)

// --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
// ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
// bandwidth is wide enough to capture a 2 ms high-g spike without
// aliasing. Latched interrupts free the polling loop from having to
// catch a transient.
void setup() {
    Serial.begin(115200);
    Wire.begin();
    accel.set_range(8);                                  // Set measurement range, (range_g=2) → g
    accel.set_bandwidth(190);                            // Set bandwidth, (bandwidth_hz=25) → Hz
    accel.set_latch(true);                               // Set latched interrupts, (enabled=False) → None
    accel.set_low_g(0.4, 40);                            // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
    accel.set_high_g(4.0, 2);                            // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
}

// --- 60-second free-fall / shock logger ---
// User is expected to drop or shake the board at some point during
// the 60 s window. Between events the magnitude sits at ≈1.00 g
// (gravity). Each latched interrupt is reported with a timestamp,
// the latest (x, y, z), temperature, and a free-fall or shock tag.
void loop() {
    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    float mag = sqrtf(x * x + y * y + z * z);
    float temp = accel.read_temperature();                   // Read temperature, () → °C
    uint8_t status = accel.poll_interrupt();                // Read STATUS, () → bitmask
    if (status & 0x08) {                                   // STATUS_LG_LATCHED (bit 3)
        Serial.print("FREE FALL  x="); Serial.print(x, 3);
        Serial.print(" y="); Serial.print(y, 3);
        Serial.print(" z="); Serial.print(z, 3);
        Serial.print(" T="); Serial.print(temp, 1);
        Serial.println(" C");
        accel.clear_interrupt();                            // Clear latched interrupts, () → None
    }
    if (status & 0x04) {                                   // STATUS_HG_LATCHED (bit 2)
        Serial.print("SHOCK     x="); Serial.print(x, 3);
        Serial.print(" y="); Serial.print(y, 3);
        Serial.print(" z="); Serial.print(z, 3);
        Serial.print(" T="); Serial.print(temp, 1);
        Serial.println(" C");
        accel.clear_interrupt();                            // Clear latched interrupts, () → None
    }
    Serial.print("|a|="); Serial.print(mag, 3);
    Serial.print(" g  x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.println();
    delay(100);
}
