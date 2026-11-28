#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x69);
RRH62000Full sensor(connection);                               // Create RRH62000 driver, (connection, accessIntervalMs=1000) → void

static void periph_printf(const char* fmt, ...) {
    char buf[128];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

#define P(...) periph_printf(__VA_ARGS__)
#define DELAY(ms) delay(ms)

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

void setup() {
    Serial.begin(115200);
    Wire.begin();
    // Starts the start-up timers: I²C after 1 s, valid data after 3.3 s; no bus access yet.
    sensor.init();                                                 // Wait for start-up, check firmware version, () → bool
    // Every access is at least 1 s after the previous one (tRD); the driver waits.
    uint8_t major = 0, minor = 0, patch = 0;
    sensor.firmware_version(major, minor);                         // Firmware version, (major&, minor&) → bool
    // Two bytes from 0x75.
    P("firmware %u.%u\n", major, minor);
    sensor.algorithm_version(major, minor, patch);                 // TVOC algorithm version, (major&, minor&, patch&) → bool
    // Version of the ZMOD4410 algorithm in the module.
    P("algorithm %u.%u.%u\n", major, minor, patch);
    uint8_t uid[6];
    sensor.unique_id(uid);                                         // Unique ID, (uid[6]) → bool
    // Identifies the individual module.
    P("unique ID %02X%02X%02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3], uid[4], uid[5]);

    RRH62000Measurement m;
    if (sensor.read(m)) print_measurement(m);                      // Main values, (measurement&) → bool
    // Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
    RRH62000Data d;
    char x[12], y[12];
    if (sensor.read_all(d))                                        // Complete block, (data&) → bool
        // Status, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
        P("PM2.5 KCl %s / smoke %s ug/m3\n", fix(x, 12, d.particles.pm2_5_ug_m3, 1), fix(y, 12, d.particles.pm2_5_smoke_ug_m3, 1));
    RRH62000Status st;
    if (sensor.read_status(st))                                    // Status flags, (status&) → bool
        // Reads the current block without waiting for new data.
        P("status 0x%04X fan %d fan speed %d dust %d high %d\n", (unsigned)st.raw, st.fan, st.fan_speed,
          st.dust_accumulation, st.high_concentration);
    RRH62000Particles p;
    if (sensor.read_particles(p))                                  // Particle values, (particles&) → bool
        // PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
        P(">0.3um %s >2.5um %s per cm3\n", fix(x, 12, p.nc_0_3, 1), fix(y, 12, p.nc_2_5, 1));
    P("data ready %d\n", sensor.data_ready());                    // New sample available, () → int
    // DATA (0x40); 1 = new data, 0 = none, negative = error.

    sensor.set_moving_average(20);                                 // Moving average, (count 1–60) → bool
    // More averages: smoother values, slower response (24 s at 10).
    P("moving average %d\n", sensor.get_moving_average());        // Moving average, () → int
    // 20.
    sensor.set_fan_speed(80);                                      // Fan speed, (percent 60–100) → bool
    // Lower speed is quieter; the default is 86 %.
    P("fan %d %%\n", sensor.get_fan_speed());                     // Fan speed, () → int
    // 80.
    sensor.set_cleaning_interval(3UL * 24 * 3600);                 // Cleaning interval, (seconds, multiple of 30) → bool
    // Written as a 16-bit count of 30 s units, high byte first.
    P("cleaning interval %ld s\n", (long)sensor.get_cleaning_interval());  // Cleaning interval, () → int32_t
    // 259200.
    sensor.set_cleaning_time(15);                                  // Cleaning time, (seconds 0–60) → bool
    // How long the fan runs at full speed during the automatic cleaning.
    P("cleaning time %d s\n", sensor.get_cleaning_time());        // Cleaning time, () → int
    // 15.

    sensor.start_cleaning();                                       // Start fan cleaning, () → bool
    // Blows dust out of the air channel.
    DELAY(15000);
    P("cleaning done %d\n", sensor.cleaning_done());              // Cleaning status, () → int
    // CSTATUS (0x74): 1 when completed.
    uint32_t ohms = 0;
    sensor.read_mox_resistance(ohms);                              // MOX resistance, (ohms&) → bool
    // Raw resistance of the TVOC sensing element.
    P("MOX %lu ohm\n", (unsigned long)ohms);

    sensor.sleep();                                                // Sleep, () → bool
    // Stops the fan and the measurement.
    DELAY(5000);
    sensor.wake();                                                 // Wake up, () → bool
    // The next access waits 1 s, the next measurement 3.3 s.
    sensor.soft_reset();                                           // Reset, () → bool
    // Same as power-on: restarts the start-up timers.
    if (sensor.read(m)) print_measurement(m);                      // Main values, (measurement&) → bool
    // First sample after the reset.
    P("done (error %d)\n", sensor.last_error());                  // Reason of the last failure, () → int
    // 0 when the last call succeeded.
}

void loop() {
}
