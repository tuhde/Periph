#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Spl06.h"

static void on_int(uint8_t status) {
    printf("interrupt 0x%02X\n", (unsigned)(status));
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    SPL06Full sensor(connection);                                  // Create SPL06 driver, (connection) → void
                                                                   // stores the connection only; no bus traffic yet
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
                                                                   // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature
    SPL06Coefficients co;
    sensor.coefficients(co);                                       // Decoded calibration, (coefficients&) → void
                                                                   // the nine factory coefficients used by every compensation
    printf("c0 %ld c1 %ld c00 %ld c10 %ld\n", (long)(co.c0), (long)(co.c1), (long)(co.c00), (long)(co.c10));
    printf("c01 %ld c11 %ld c20 %ld c21 %ld c30 %ld\n", (long)(co.c01), (long)(co.c11), (long)(co.c20), (long)(co.c21), (long)(co.c30));
    float t = 0, p = 0, alt = 0;
    bool ok = sensor.read(t, p);                                   // Temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                   // command mode: one temperature, then one pressure measurement
    printf("read %.2f C %.2f Pa\n", (double)(t), (double)(p));
    ok = sensor.read_pressure(p);                                  // Pressure, (pressure_pa&) → bool
                                                                   // measures temperature first, because the compensation needs it
    ok = sensor.read_temperature(t);                               // Temperature, (temperature_c&) → bool
                                                                   // one temperature measurement only
    printf("pressure %.2f Pa temperature %.2f C\n", (double)(p), (double)(t));
    int32_t praw = 0, traw = 0;
    ok = sensor.read_raw(praw, traw);                              // Raw results, (praw&, traw&) → bool
                                                                   // signed 24-bit values from the result registers
    printf("raw %ld %ld\n", (long)(praw), (long)(traw));
    ok = sensor.compensate(praw, traw, 16, 1, t, p);               // Compensate raw values, (praw, traw, p_oversampling, t_oversampling, temperature_c&, pressure_pa&) → bool
                                                                   // pure computation with the stored coefficients; no bus access
    printf("compensated %.2f C %.2f Pa\n", (double)(t), (double)(p));
    ok = sensor.altitude(alt, 101325.0f);                          // Altitude, (altitude_m&, sea_level_pa = 101325) → bool
                                                                   // international barometric formula against the given sea-level pressure
    printf("altitude %.2f m\n", (double)(alt));
    ok = sensor.set_pressure_config(64, 4);                        // Pressure oversampling and rate, (oversampling = 16, rate = 1) → bool
                                                                   // 64x oversampling, 4 background readings per second; sets P_SHIFT
    ok = sensor.set_temperature_config(2, 4);                      // Temperature oversampling and rate, (oversampling = 1, rate = 1) → bool
                                                                   // 2x oversampling, 4 per second; always keeps the external sensor
    ok = sensor.start_continuous(true, true);                      // Background mode, (pressure = true, temperature = true) → bool
                                                                   // MEAS_CTRL = 111: the chip measures both on its own
    ok = sensor.read_continuous(t, p);                             // Latest background result, (temperature_c&, pressure_pa&) → bool
                                                                   // waits for both ready flags; does not start a measurement
    printf("continuous %.2f C %.2f Pa\n", (double)(t), (double)(p));
    bool pr = false, tr = false;
    ok = sensor.data_ready(pr, tr);                                // Ready flags, (pressure_ready&, temperature_ready&) → bool
                                                                   // MEAS_CFG.PRS_RDY and TMP_RDY
    printf("ready %lu %lu\n", (unsigned long)(pr), (unsigned long)(tr));
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // MEAS_CTRL = 000
    ok = sensor.enable_fifo();                                     // FIFO on, () → bool
                                                                   // background results now queue in the 32-entry FIFO
    ok = sensor.start_continuous();                                // Background mode, (pressure = true, temperature = true) → bool
                                                                   // fill the FIFO for a second
    usleep((unsigned)(1000) * 1000);
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // stop adding entries
    bool empty = false, full = false;
    ok = sensor.fifo_status(empty, full);                          // FIFO state, (empty&, full&) → bool
                                                                   // FIFO_STS flags
    printf("fifo empty %lu full %lu\n", (unsigned long)(empty), (unsigned long)(full));
    SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);                     // Drain FIFO, (entries*, max_entries) → uint8_t
                                                                   // each entry is KIND_PRESSURE (Pa) or KIND_TEMPERATURE (C), in measured order
    printf("fifo %lu entries\n", (unsigned long)(n));
    ok = sensor.flush_fifo();                                      // Flush FIFO, () → bool
                                                                   // discards any remaining entries
    ok = sensor.disable_fifo();                                    // FIFO off, () → bool
                                                                   // results go to the result registers again
    ok = sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);      // Enable interrupt source, (source) → bool
                                                                   // pressure-ready interrupt on SDO (active low)
    printf("INT_STS 0x%02X\n", (unsigned)(sensor.poll_interrupt()));
    // poll_interrupt(): read and clear INT_STS, () → uint8_t
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
                                                                   // does nothing without an INT line wired; poll instead
    sensor.off_interrupt();                                        // Unsubscribe, () → void
                                                                   // removes the edge handler
    ok = sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);     // Disable interrupt source, (source) → bool
                                                                   // clears CFG_REG.INT_PRS
    ok = sensor.reset();                                           // Soft reset, () → bool
                                                                   // RESET = 0x09, re-reads the coefficients and restores the init defaults
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
