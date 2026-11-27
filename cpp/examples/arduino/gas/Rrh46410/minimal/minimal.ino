#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x38);
RRH46410Minimal sensor(connection);                                  // Create RRH46410 driver, (connection) → void

static void periph_printf(const char* fmt, ...) {
    char buf[128];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static void print_result(const RRH46410Result& r) {
    unsigned long tvoc = (unsigned long)(r.tvoc_mg_m3 * 1000.0f + 0.5f);
    unsigned long etoh = (unsigned long)(r.etoh_ppm * 1000.0f + 0.5f);
    unsigned iaq = (unsigned)(r.iaq * 10.0f + 0.5f);
    periph_printf("sample %u  IAQ %u.%u  TVOC %lu.%03lu mg/m3  EtOH %lu.%03lu ppm  eCO2 %u ppm  rel %u  %s\n",
          (unsigned)r.sample, iaq / 10, iaq % 10, tvoc / 1000, tvoc % 1000, etoh / 1000, etoh % 1000,
          (unsigned)r.eco2_ppm, (unsigned)r.relative_iaq, r.warmed_up ? "ok" : "warming up");
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool
}

void loop() {
    RRH46410Result r;
    if (sensor.read(r)) print_result(r);                       // Wait for the next sample, (result&) → bool
    else periph_printf("read failed (error %d)\n", sensor.last_error());
}
