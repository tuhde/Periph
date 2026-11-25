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
    AS7262Channels ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0, u8c = 0;
    uint16_t u16 = 0;
    bool ok = sensor.begin();                                       // Reset and configure defaults, () → bool
    ok = sensor.read(ch);                                           // Read calibrated channels, (ch) → bool
                                                                    // one one-shot conversion
    Serial.print("V "); Serial.print(ch.violet, 2); Serial.print(" R "); Serial.print(ch.red, 2); Serial.println();
    ok = sensor.read_raw(raw);                                      // Read raw counts, (raw) → bool
                                                                    // 16-bit counts
    Serial.print("raw V "); Serial.print((unsigned long)(raw.violet)); Serial.println();
    Serial.print("temperature "); Serial.print(sensor.temperature(), 0); Serial.print(" C"); Serial.println();
    ok = sensor.measure();                                          // Start a one-shot conversion, () → bool
                                                                    // does not wait
    while (!sensor.data_ready()) { delay(50); }                     // Check data ready, () → bool
    ok = sensor.read_calibrated(ch);                                // Read last calibrated results, (ch) → bool
    ok = sensor.read_raw_last(raw);                                 // Read last raw counts, (raw) → bool
    Serial.print("channel 5 "); Serial.print(sensor.read_channel(5), 2); Serial.println();
    ok = sensor.read_channel_raw(5, u16);                           // Read one raw channel, (index, value) → bool
    ok = sensor.set_gain(16);                                       // Set gain, (gain) → bool
                                                                    // 0-3 index or 1, 3.7, 16, 64
    Serial.print("gain "); Serial.print(sensor.get_gain(), 1); Serial.println();
    ok = sensor.set_integration_time(165.2f);                       // Set integration time, (ms) → bool
                                                                    // 2.8 - 714 ms
    Serial.print("integration "); Serial.print(sensor.get_integration_time(), 1); Serial.print(" ms"); Serial.println();
    ok = sensor.set_mode(3);                                        // Set bank mode, (mode) → bool
                                                                    // 0, 1, 2 continuous; 3 one-shot
    Serial.print("mode "); Serial.print((unsigned long)(sensor.get_mode())); Serial.println();
    ok = sensor.set_continuous(2);                                  // Continuous conversions, (mode) → bool
    ok = sensor.set_mode(3);                                        // Back to one-shot, (mode) → bool
    ok = sensor.set_interrupt(false);                               // Enable/disable INT pin, (enabled) → bool
    ok = sensor.set_led(true, 25.0f);                               // Driver LED, (on, current_ma) → bool
                                                                    // 12.5, 25, 50 or 100 mA
    ok = sensor.set_led(false);                                     // Driver LED off, (on, current_ma) → bool
    ok = sensor.set_indicator_led(true, 4.0f);                      // Indicator LED, (on, current_ma) → bool
                                                                    // 1, 2, 4 or 8 mA
    ok = sensor.set_indicator_led(false);                           // Indicator LED off, (on, current_ma) → bool
    ok = sensor.hardware_version(u8a, u8b);                         // Hardware version, (type, version) → bool
    ok = sensor.firmware_version(u8a, u8b, u8c);                    // Firmware version, (major, minor, sub) → bool
    Serial.print("type 0x"); Serial.print((unsigned long)(sensor.device_type()), HEX); Serial.println();
    ok = sensor.read_virtual(0x05, u8a);                            // Read a virtual register, (reg, value) → bool
    ok = sensor.write_virtual(0x05, 0x3B);                          // Write a virtual register, (reg, value) → bool
    sensor.on_interrupt(on_data);                                   // Subscribe to INT, (callback, intPin=nullptr) → void
                                                                    // needs an INT pin on the connection
    Serial.print("data ready "); Serial.print((unsigned long)(sensor.poll_interrupt())); Serial.println();
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    ok = sensor.soft_reset();                                       // Soft reset, () → bool
                                                                    // restores defaults
    Serial.print("done ok="); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
    delay(1000);
}
