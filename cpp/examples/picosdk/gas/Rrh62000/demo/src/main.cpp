#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Rrh62000.h"

#define P(...) printf(__VA_ARGS__)
#define DELAY(ms) sleep_ms(ms)

// Formats v with `dec` decimals using integers only (no %f on AVR / printk).
static const char* fix(char* buf, size_t n, float v, int dec) {
    long scale = dec == 2 ? 100 : 10;
    long x = (long)(v * scale + (v < 0 ? -0.5f : 0.5f));
    const char* sign = x < 0 ? "-" : "";
    if (x < 0) x = -x;
    snprintf(buf, n, dec == 2 ? "%s%ld.%02ld" : "%s%ld.%01ld", sign, x / scale, x % scale);
    return buf;
}

static void print_measurement(const RRH62000Measurement& m) {
    char a[12], b[12], c[12], t[12], h[12], q[12];
    P("PM1 %s  PM2.5 %s  PM10 %s ug/m3  %s C  %s %%RH  TVOC %lu ug/m3  eCO2 %u ppm  IAQ %s\n",
      fix(a, 12, m.pm1_ug_m3, 1), fix(b, 12, m.pm2_5_ug_m3, 1), fix(c, 12, m.pm10_ug_m3, 1),
      fix(t, 12, m.temperature_c, 2), fix(h, 12, m.humidity_rh, 2), (unsigned long)m.tvoc,
      (unsigned)m.eco2_ppm, fix(q, 12, m.iaq, 2));
}

static const char* iaq_label(float iaq) {
    if (iaq < 2.0f) return "very good";
    if (iaq < 3.0f) return "good";
    if (iaq < 4.0f) return "medium";
    if (iaq < 5.0f) return "poor";
    return "bad";
}

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x69);
    RRH62000Full sensor(connection);                               // Create RRH62000 driver, (connection, accessIntervalMs=1000) → void
    sensor.init();                                                 // Wait for start-up, check firmware version, () → bool

    // --- Identity and settings ---
    // The module may only be accessed once per second; every call below waits
    // for that interval, so this section takes several seconds.
    uint8_t major = 0, minor = 0, patch = 0;
    sensor.firmware_version(major, minor);                         // Firmware version, (major&, minor&) → bool
    P("firmware %u.%u\n", major, minor);
    sensor.algorithm_version(major, minor, patch);                 // Algorithm version, (major&, minor&, patch&) → bool
    P("algorithm %u.%u.%u\n", major, minor, patch);
    uint8_t uid[6];
    sensor.unique_id(uid);                                         // Unique ID, (uid[6]) → bool
    P("unique ID %02X%02X%02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3], uid[4], uid[5]);
    P("moving average %d, fan %d %%, ", sensor.get_moving_average(), sensor.get_fan_speed());  // Moving average, () → int / Fan speed, () → int
    P("cleaning every %ld s for %d s\n", (long)sensor.get_cleaning_interval(), sensor.get_cleaning_time());  // Cleaning interval, () → int32_t / Cleaning time, () → int

    // --- Room air-quality station ---
    // A complete sample arrives every 3 s. The IAQ index follows the German UBA
    // scale; PM2.5 uses the KCl reference. Allow up to 48 h of conditioning on
    // first use before trusting TVOC and eCO2.
    RRH62000Data d;
    char a[12], b[12], c[12], e[12], f[12];
    for (int i = 0; i < 10; i++) {
        if (!sensor.read_all(d)) {                                 // Complete block, (data&) → bool
            P("read failed (error %d)\n", sensor.last_error());   // Reason of the last failure, () → int
            continue;
        }
        P("IAQ %s (%s)  PM2.5 %s ug/m3  eCO2 %u ppm  TVOC %lu ug/m3  %s C  %s %%RH\n",
          fix(a, 12, d.iaq, 2), iaq_label(d.iaq), fix(b, 12, d.particles.pm2_5_ug_m3, 1), (unsigned)d.eco2_ppm,
          (unsigned long)d.tvoc, fix(c, 12, d.temperature_c, 1), fix(e, 12, d.humidity_rh, 1));
    }

    // --- Particle counts and status ---
    // Number concentrations count particles from the bin size up to 10 µm per cm³.
    RRH62000Particles p;
    if (sensor.read_particles(p)) {                                // Particle values, (particles&) → bool
        P("particles/cm3 >0.3um %s >0.5um %s >1um %s ", fix(a, 12, p.nc_0_3, 1), fix(b, 12, p.nc_0_5, 1), fix(c, 12, p.nc_1, 1));
        P(">2.5um %s >4um %s\n", fix(e, 12, p.nc_2_5, 1), fix(f, 12, p.nc_4, 1));
        P("PM2.5 KCl %s / smoke %s ug/m3\n", fix(a, 12, p.pm2_5_ug_m3, 1), fix(b, 12, p.pm2_5_smoke_ug_m3, 1));
    }
    RRH62000Status st;
    if (sensor.read_status(st))                                    // Status flags, (status&) → bool
        P("status: high %d dust %d fan speed %d fan %d\n", st.high_concentration, st.dust_accumulation,
          st.fan_speed, st.fan);
    P("new data pending: %d\n", sensor.data_ready());             // New sample available, () → int

    // --- Quieter, smoother operation ---
    // 30 moving averages smooth the PM values (slower response); 70 % fan speed
    // is quieter.
    sensor.set_moving_average(30);                                 // Moving average, (count 1–60) → bool
    sensor.set_fan_speed(70);                                      // Fan speed, (percent 60–100) → bool
    P("moving average %d, fan %d %%\n", sensor.get_moving_average(), sensor.get_fan_speed());

    // --- Fan cleaning ---
    // A manual cleaning blows dust out of the air channel; the automatic
    // cleaning is shortened to every 3 days for 10 s.
    sensor.start_cleaning();                                       // Start fan cleaning, () → bool
    for (int i = 0; i < 30; i++) {
        if (sensor.cleaning_done() == 1) {                         // Cleaning status, () → int
            P("cleaning done\n");
            break;
        }
    }
    sensor.set_cleaning_interval(3UL * 24 * 3600);                 // Cleaning interval, (seconds) → bool
    sensor.set_cleaning_time(10);                                  // Cleaning time, (seconds) → bool
    P("cleaning every %ld s\n", (long)sensor.get_cleaning_interval());

    // --- Sleep, diagnostics, reset ---
    // Sleep stops the fan; after wake-up the first valid sample takes 3.3 s.
    sensor.sleep();                                                // Sleep, () → bool
    DELAY(5000);
    sensor.wake();                                                 // Wake up, () → bool
    uint32_t ohms = 0;
    sensor.read_mox_resistance(ohms);                              // MOX resistance, (ohms&) → bool
    P("MOX resistance %lu ohm\n", (unsigned long)ohms);
    sensor.soft_reset();                                           // Reset, () → bool
    RRH62000Measurement m;
    if (sensor.read(m)) print_measurement(m);                      // Main values, (measurement&) → bool
    return 0;
}
