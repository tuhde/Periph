#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x49);
AS7262Full sensor(connection);                                      // Create AS7262 driver, (connection) → void

static void on_data(uint8_t status) {
    Serial.print("data ready "); Serial.print((unsigned long)(status)); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    AS7262Channels base = {}, lit = {}, ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0, u8c = 0;
    sensor.begin();                                                 // Reset and configure defaults, () → bool
    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    sensor.hardware_version(u8a, u8b);                              // Hardware version, (type, version) → bool
    sensor.firmware_version(u8a, u8b, u8c);                         // Firmware version, (major, minor, sub) → bool
    Serial.print("type 0x"); Serial.print((unsigned long)(sensor.device_type()), HEX); Serial.print(" hw "); Serial.print((unsigned long)(u8a)); Serial.print(" fw "); Serial.print((unsigned long)(u8c)); Serial.println();
    Serial.print("chip "); Serial.print(sensor.temperature(), 0); Serial.print(" C"); Serial.println();
    static const float gains[4] = {1.0f, 3.7f, 16.0f, 64.0f};
    for (int i = 0; i < 4; i++) {
        sensor.set_gain(gains[i]);                                  // Set gain, (gain) → bool
        sensor.read(ch);                                            // Read calibrated, (ch) → bool
        sensor.read_raw(raw);                                       // Read raw, (raw) → bool
        Serial.print("gain "); Serial.print(sensor.get_gain(), 1); Serial.print(" V "); Serial.print(ch.violet, 2); Serial.print(" raw V "); Serial.print((unsigned long)(raw.violet)); Serial.println();
    }
    sensor.set_gain(16);                                            // Set gain, (gain) → bool
    static const float times[2] = {50.0f, 500.0f};
    for (int i = 0; i < 2; i++) {
        sensor.set_integration_time(times[i]);                      // Set integration time, (ms) → bool
        sensor.read(ch);                                            // One-shot read, (ch) → bool
        Serial.print("integration "); Serial.print(sensor.get_integration_time(), 1); Serial.print(" ms"); Serial.println();
    }
    sensor.set_integration_time(165.2f);                            // Set integration time, (ms) → bool
    sensor.set_mode(3);                                             // Set bank mode, (mode) → bool
    sensor.read(base);                                              // Read calibrated, (ch) → bool
    sensor.set_indicator_led(true, 4.0f);                           // Indicator LED, (on, current_ma) → bool
    sensor.set_led(true, 25.0f);                                    // Driver LED, (on, current_ma) → bool
    sensor.read(lit);                                               // Read calibrated, (ch) → bool
    sensor.set_led(false);                                          // Driver LED off, (on, current_ma) → bool
    sensor.set_indicator_led(false);                                // Indicator LED off, (on, current_ma) → bool
    const float* b = &base.violet; const float* l = &lit.violet; int best = 0;
    for (int i = 1; i < 6; i++) { if (l[i] - b[i] > l[best] - b[best]) best = i; }
    Serial.print("reflectance dominant channel "); Serial.print((unsigned long)(best)); Serial.println();
    sensor.measure();                                               // Start one-shot, () → bool
    while (!sensor.data_ready()) { delay(50); }                     // Data ready, () → bool
    sensor.read_calibrated(ch);                                     // Last calibrated results, (ch) → bool
    sensor.read_raw_last(raw);                                      // Last raw counts, (raw) → bool
    Serial.print("last V "); Serial.print(sensor.read_channel(0), 2); Serial.print(" raw V "); Serial.print((unsigned long)(raw.violet)); Serial.println();
    sensor.set_continuous(2);                                       // Continuous mode, (mode) → bool
    sensor.on_interrupt(on_data);                                   // Subscribe to INT, (callback, intPin=nullptr) → void
    for (int i = 0; i < 10; i++) {
        delay(500);
        if (sensor.poll_interrupt()) { sensor.read_calibrated(ch); }
        Serial.print("V "); Serial.print(ch.violet, 2); Serial.println();
    }
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.set_mode(3);                                             // Back to one-shot, (mode) → bool
    sensor.write_virtual(0x05, 0x3B);                               // Write virtual register, (reg, value) → bool
    sensor.read_virtual(0x05, u8a);                                 // Read virtual register, (reg, value) → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
}

void loop() {
    delay(1000);
}
