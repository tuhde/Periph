#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x69);
RRH62000Minimal sensor(connection);                            // Create RRH62000 driver, (connection, accessIntervalMs=1000) → void

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
    sensor.init();                                                 // Wait for start-up, check firmware version, () → bool
}

void loop() {
    RRH62000Measurement m;
    if (sensor.read(m)) print_measurement(m);                  // Wait for a new sample, (measurement&) → bool
    else P("read failed (error %d)\n", sensor.last_error());   // Reason of the last failure, () → int
}
