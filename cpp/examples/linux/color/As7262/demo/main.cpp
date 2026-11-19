#include <chrono>
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

    AS7262Channels base = {}, lit = {}, ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0, u8c = 0;
    sensor.begin();                                                 // Reset and configure defaults, () → bool
    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    sensor.hardware_version(u8a, u8b);                              // Hardware version, (type, version) → bool
    sensor.firmware_version(u8a, u8b, u8c);                         // Firmware version, (major, minor, sub) → bool
    printf("type 0x%lX hw %lu fw %lu\n", (unsigned long)(sensor.device_type()), (unsigned long)(u8a), (unsigned long)(u8c));
    printf("chip %.0f C\n", (double)(sensor.temperature()));
    static const float gains[4] = {1.0f, 3.7f, 16.0f, 64.0f};
    for (int i = 0; i < 4; i++) {
        sensor.set_gain(gains[i]);                                  // Set gain, (gain) → bool
        sensor.read(ch);                                            // Read calibrated, (ch) → bool
        sensor.read_raw(raw);                                       // Read raw, (raw) → bool
        printf("gain %.1f V %.2f raw V %lu\n", (double)(sensor.get_gain()), (double)(ch.violet), (unsigned long)(raw.violet));
    }
    sensor.set_gain(16);                                            // Set gain, (gain) → bool
    static const float times[2] = {50.0f, 500.0f};
    for (int i = 0; i < 2; i++) {
        sensor.set_integration_time(times[i]);                      // Set integration time, (ms) → bool
        sensor.read(ch);                                            // One-shot read, (ch) → bool
        printf("integration %.1f ms\n", (double)(sensor.get_integration_time()));
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
    printf("reflectance dominant channel %lu\n", (unsigned long)(best));
    sensor.measure();                                               // Start one-shot, () → bool
    while (!sensor.data_ready()) { usleep(50 * 1000); }             // Data ready, () → bool
    sensor.read_calibrated(ch);                                     // Last calibrated results, (ch) → bool
    sensor.read_raw_last(raw);                                      // Last raw counts, (raw) → bool
    printf("last V %.2f raw V %lu\n", (double)(sensor.read_channel(0)), (unsigned long)(raw.violet));
    sensor.set_continuous(2);                                       // Continuous mode, (mode) → bool
    sensor.on_interrupt(on_data);                                   // Subscribe to INT, (callback, intPin=nullptr) → void
    for (int i = 0; i < 10; i++) {
        usleep(500 * 1000);
        if (sensor.poll_interrupt()) { sensor.read_calibrated(ch); }
        printf("V %.2f\n", (double)(ch.violet));
    }
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.set_mode(3);                                             // Back to one-shot, (mode) → bool
    sensor.write_virtual(0x05, 0x3B);                               // Write virtual register, (reg, value) → bool
    sensor.read_virtual(0x05, u8a);                                 // Read virtual register, (reg, value) → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
    return 0;
}
