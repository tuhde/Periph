#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HS40xx.h"

static volatile uint8_t alert_status = 0;
static void on_alert(uint8_t status) { alert_status = status; }

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x54;
    I2CConnectionLinux connection(bus, addr);

    HS40xxFull sensor(connection);                                  // Create HS40xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t hb = 0, tb = 0, st = 0, reg = 0;
    uint32_t id = 0;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    printf("%.2f C, %.1f %%RH\n", t, rh);
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // no-hold 0xF3, 3 bytes; NAN on failure
    printf("temperature %.2f C\n", t);
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // NAN on failure
    printf("humidity %.1f %%RH\n", rh);
    sensor.read_hold(t, rh);                                        // Read with clock stretching, (t, rh) → bool °C, %RH
                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
    sensor.read_raw(rh_raw, t_raw);                                 // Read raw values, (humidity_raw, temperature_raw) → bool
                                                                    // 14-bit values, CRC verified
    printf("raw RH %lu raw T %lu\n", (unsigned long)(rh_raw), (unsigned long)(t_raw));
    sensor.set_resolution(12, 12);                                  // Set resolution, (humidity_bits, temperature_bits) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    sensor.get_resolution(hb, tb);                                  // Get resolution, (humidity_bits, temperature_bits) → bool
    printf("resolution %lu/%lu bit\n", (unsigned long)(hb), (unsigned long)(tb));
    sensor.read_register(0x00, reg);                                // Read a register, (reg, value) → bool
    sensor.write_register(0x00, 0x0F);                              // Write a register, (reg, value) → bool
                                                                    // 0x0F = 14 bit for both channels (default)
    sensor.set_alert_thresholds(60.0f, 55.0f, NAN, NAN, 40.0f, 38.0f);// Set alert thresholds, (rh_high, rh_high_reset, rh_low, rh_low_reset, temp_high, temp_high_reset, temp_low, temp_low_reset) → bool
                                                                    // %RH / °C; NAN leaves a threshold unchanged
    sensor.set_alert_polarity(true);                                // Set ALERT polarity, (active_high) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
                                                                    // 0.5, 1 or 2.5 s
    usleep(1500 * 1000);
    sensor.read_periodic(t, rh, st);                                // Fetch the latest periodic result, (t, rh, status) → bool
                                                                    // status is a SOURCE_* bitmask
    printf("periodic %.2f C %.1f %%RH status %lu\n", t, rh, (unsigned long)(st));
    st = sensor.poll_interrupt();                                   // Read alert status, () → uint8_t
                                                                    // SOURCE_* bitmask, 0 on failure
    sensor.on_interrupt(on_alert);                                  // Subscribe to the ALERT pin, (callback, intPin = nullptr) → void
                                                                    // needs an InputPin on the connection (or passed in)
    usleep(5000 * 1000);
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.disable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Disable alert sources, (source) → bool
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    printf("id 0x%lX\n", (unsigned long)(id));
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C\n", t);
    sensor.soft_reset();                                            // Soft reset, () → bool
                                                                    // stops periodic mode, waits 500 ms
    return 0;
}
