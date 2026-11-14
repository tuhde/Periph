#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "VL6180X.h"

static volatile uint8_t g_status = 0;
static void onStatus(uint8_t status) { g_status = status; }

int main() {
    I2CConnectionLinux connection(1, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XFull sensor(connection);                         // Create VL6180X Full driver, (connection)
    sensor.init();                                          // Initialize, () → bool
                                                            // boot wait, private + recommended settings
    uint8_t mm = 0, err = 0;
    sensor.read_range(mm);                                  // Single-shot range, (uint8_t& mm) → bool
                                                            // false on a timeout or a non-zero error code; see last_error()
    sensor.read_range_status(mm, err);                      // Range and error code, (uint8_t& mm, uint8_t& err) → bool
                                                            // false only on a timeout; err 0 = valid
    printf("%u mm, error %u\n", (unsigned)mm, (unsigned)err);
    uint16_t raw = 0;
    sensor.read_als_raw(raw);                               // Single-shot ALS count, (uint16_t& raw) → bool
    float lux = sensor.read_lux();                          // Single-shot ALS in lux, () → float lux
                                                            // 0.32 lux/count at gain 1, 100 ms; NAN on a timeout
    printf("%u counts, %d.%02d lux\n", (unsigned)raw, (int)lux, (int)(lux * 100) % 100);

    sensor.start_continuous_range(50);                      // Start continuous ranging, (uint16_t period_ms) → bool
                                                            // 10–2550 ms in 10 ms steps
    sensor.read_range_continuous(mm);                       // Wait for next sample, (uint8_t& mm) → bool
    sensor.stop_continuous_range();                         // Stop continuous ranging, () → void
    sensor.start_continuous_als(100);                       // Start continuous ALS, (uint16_t period_ms) → bool
    sensor.stop_continuous_als();                           // Stop continuous ALS, () → void
    sensor.set_interleaved(false);                          // Interleaved ALS + range, (bool on) → void

    sensor.set_als_gain(6);                                 // ALS gain code, (uint8_t code) → bool
                                                            // 0 = 20 ... 6 = 1, 7 = 40
    sensor.set_als_integration_time(100);                   // ALS integration time, (uint16_t ms) → bool
                                                            // 1–512 ms
    sensor.set_als_scaler(1);                               // ALS result scaler, (uint8_t scaler) → bool
    sensor.set_range_thresholds(30, 80);                    // Range thresholds, (uint8_t low, uint8_t high) → bool
    sensor.set_als_thresholds(100, 5000);                   // ALS thresholds, (uint16_t low, uint16_t high) → bool
    sensor.set_max_convergence_time(49);                    // Max convergence time, (uint8_t ms) → bool
    sensor.set_averaging_period(48);                        // Averaging period, (uint8_t units) → void
    sensor.set_range_offset(0);                             // Part-to-part offset, (int8_t mm) → void
    printf("offset %d mm\n", (int)sensor.get_range_offset()); // Read the offset, () → int8_t mm
    sensor.set_crosstalk_rate(0.0f);                        // Crosstalk rate, (float rate_mcps) → bool
    sensor.recalibrate_vhv();                               // Recalibrate VHV, () → bool

    uint8_t major = 0, minor = 0;
    sensor.revision(major, minor);                          // Model revision, (uint8_t& major, uint8_t& minor) → void
    printf("model 0x%02X rev %u.%u\n", (unsigned)sensor.model_id(), (unsigned)major, (unsigned)minor); // Model ID, () → uint8_t
                                                                                                       // always 0xB4
    uint16_t hist[8];
    sensor.read_history(hist);                              // History buffer, (uint16_t out[8]) → void

    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    printf("pending 0x%02X\n", (unsigned)sensor.poll_interrupt()); // Read and clear, () → uint8_t
                                                                   // SOURCE_* bit mask
    sensor.on_interrupt(onStatus);                          // Subscribe to GPIO1, (callback, InputPin* intPin = nullptr) → void
                                                            // needs an INT pin on the connection or as argument
    sensor.off_interrupt();                                 // Unsubscribe, () → void
    sensor.disable_interrupt(VL6180XFull::SOURCE_RANGE);    // Disable an interrupt, (uint8_t source) → bool
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);
    // sensor.set_address(0x30);                            // Change address (volatile), (uint8_t address) → bool
    return 0;
}
