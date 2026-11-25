#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Mpl3115a2.h"

static void on_interrupt(uint8_t status) {
    printf("INT_SOURCE 0x%02X\n", (unsigned)(status));
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MPL3115A2Full sensor(connection);                              // Create MPL3115A2 driver, (connection) → void
    float p = 0, t = 0, v = 0, dv = 0, dt = 0, alt = 0;
    sensor.init();                                                 // Reset and apply defaults, () → bool
                                                                   // 0xC4 identity check, PT_DATA_CFG = 0x07, barometer, OSR 128
    printf("who_am_i 0x%02X\n", (unsigned)(sensor.who_am_i()));
    bool ok = sensor.read_pressure(p);                             // One-shot pressure, (pressure_pa&) → bool
    ok = sensor.read_temperature(t);                               // One-shot temperature, (temperature_c&) → bool
    ok = sensor.read(p, t);                                        // Both together, (pressure_pa&, temperature_c&) → bool
    printf("pressure %.2f Pa temperature %.2f C\n", (double)(p), (double)(t));
    ok = sensor.set_oversample(32);                                // Oversample ratio, (ratio 1/2/4/…/128) → bool
                                                                   // ratio 128 gives 0.25 Pa resolution at 512 ms per sample
    printf("oversample %lu\n", (unsigned long)(sensor.get_oversample()));
    ok = sensor.set_sea_level_pressure(101325.0f);                 // Sea-level reference, (pa) → bool
                                                                   // BAR_IN in 2 Pa units
    ok = sensor.set_offsets(0.0f, 0.0f, 0);                        // Offset trims, (pressure_pa, temperature_c, altitude_m) → bool
    ok = sensor.read_altitude(alt);                                // Altimeter reading, (altitude_m&) → bool
                                                                   // switches to altimeter mode
    printf("altitude %.2f m\n", (double)(alt));
    ok = sensor.set_mode(false);                                   // Barometer mode, (altimeter) → bool
    ok = sensor.start_continuous(0);                               // Periodic acquisition, (step_exp 0-15) → bool
                                                                   // one sample every 2^n seconds
    printf("active %lu\n", (unsigned long)(sensor.is_active()));
    ok = sensor.read_continuous(v, t);                             // Wait for PTDR and read, (value&, temperature_c&) → bool
    printf("continuous %.2f %.2f\n", (double)(v), (double)(t));
    ok = sensor.data_ready();                                      // Data-ready flag, () → bool
    ok = sensor.read_delta(dv, dt);                                // Change since previous sample, (delta_value&, delta_temperature_c&) → bool
    ok = sensor.stop_continuous();                                 // Back to STANDBY, () → bool
    ok = sensor.set_target(100000.0f, 200.0f, -5, 3);              // Threshold targets, (value, window, temperature_c, temperature_window) → bool
    ok = sensor.load_current_as_target();                          // Current output as target, () → bool
    float pmin = 0, pmax = 0, tmin = 0, tmax = 0;
    ok = sensor.read_min_max(pmin, pmax, tmin, tmax);              // Captured extremes, (p_min&, p_max&, t_min&, t_max&) → bool
    printf("min %.2f max %.2f\n", (double)(pmin), (double)(pmax));
    ok = sensor.clear_min_max();                                   // Clear extremes, () → bool
    ok = sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 16);      // Configure the FIFO, (mode 0/1/2, watermark 0-63) → bool
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    ok = sensor.fifo_status(overflow, watermark, count);           // FIFO state, (overflow&, watermark&, count&) → bool
    float fv[32], ft[32];
    uint8_t n = sensor.fifo_read(fv, ft, 32);                      // Drain the FIFO, (values*, temperatures*, max) → uint8_t
    printf("fifo %lu samples\n", (unsigned long)(n));
    ok = sensor.fifo_setup(MPL3115A2Full::FIFO_OFF);               // FIFO off, (mode, watermark = 0) → bool
    ok = sensor.set_interrupt_pins(false, false, false, false);    // INT polarity / drive, (polarity1, open_drain1, polarity2, open_drain2) → bool
    ok = sensor.enable_interrupt(MPL3115A2Full::SOURCE_DRDY, 1);   // Route a source to INT1, (source, pin 1/2) → bool
    printf("INT_SOURCE 0x%02X\n", (unsigned)(sensor.poll_interrupt()));
    sensor.on_interrupt(on_interrupt, 1);                          // Subscribe to INT1 edges, (callback, pin = 1, intPin = nullptr) → void
                                                                   // does nothing without an INT line wired
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    ok = sensor.disable_interrupt(MPL3115A2Full::SOURCE_DRDY);     // Disable the source, (source) → bool
    ok = sensor.reset();                                           // Software reset and defaults, () → bool
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
