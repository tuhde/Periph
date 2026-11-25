#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "As7262.h"


static void on_data(uint8_t status) {
    printf("data ready %lu\n", (unsigned long)(status));
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x49;
    I2CConnectionLinux connection(bus, addr);

    AS7262Full sensor(connection);                                  // Create AS7262 driver, (connection) → void

    AS7262Channels ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0, u8c = 0;
    uint16_t u16 = 0;
    bool ok = sensor.begin();                                       // Reset and configure defaults, () → bool
    ok = sensor.read(ch);                                           // Read calibrated channels, (ch) → bool
                                                                    // one one-shot conversion
    printf("V %.2f R %.2f\n", (double)(ch.violet), (double)(ch.red));
    ok = sensor.read_raw(raw);                                      // Read raw counts, (raw) → bool
                                                                    // 16-bit counts
    printf("raw V %lu\n", (unsigned long)(raw.violet));
    printf("temperature %.0f C\n", (double)(sensor.temperature()));
    ok = sensor.measure();                                          // Start a one-shot conversion, () → bool
                                                                    // does not wait
    while (!sensor.data_ready()) { usleep(50 * 1000); }             // Check data ready, () → bool
    ok = sensor.read_calibrated(ch);                                // Read last calibrated results, (ch) → bool
    ok = sensor.read_raw_last(raw);                                 // Read last raw counts, (raw) → bool
    printf("channel 5 %.2f\n", (double)(sensor.read_channel(5)));
    ok = sensor.read_channel_raw(5, u16);                           // Read one raw channel, (index, value) → bool
    ok = sensor.set_gain(16);                                       // Set gain, (gain) → bool
                                                                    // 0-3 index or 1, 3.7, 16, 64
    printf("gain %.1f\n", (double)(sensor.get_gain()));
    ok = sensor.set_integration_time(165.2f);                       // Set integration time, (ms) → bool
                                                                    // 2.8 - 714 ms
    printf("integration %.1f ms\n", (double)(sensor.get_integration_time()));
    ok = sensor.set_mode(3);                                        // Set bank mode, (mode) → bool
                                                                    // 0, 1, 2 continuous; 3 one-shot
    printf("mode %lu\n", (unsigned long)(sensor.get_mode()));
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
    printf("type 0x%lX\n", (unsigned long)(sensor.device_type()));
    ok = sensor.read_virtual(0x05, u8a);                            // Read a virtual register, (reg, value) → bool
    ok = sensor.write_virtual(0x05, 0x3B);                          // Write a virtual register, (reg, value) → bool
    sensor.on_interrupt(on_data);                                   // Subscribe to INT, (callback, intPin=nullptr) → void
                                                                    // needs an INT pin on the connection
    printf("data ready %lu\n", (unsigned long)(sensor.poll_interrupt()));
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    ok = sensor.soft_reset();                                       // Soft reset, () → bool
                                                                    // restores defaults
    printf("done ok=%lu\n", (unsigned long)(ok));
    return 0;
}
