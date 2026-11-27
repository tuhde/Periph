#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
Si1145Minimal sensor(connection);                                     // Create Si1145 driver, (connection) → void

static void periph_printf(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
}

void loop() {
    Si1145Reading r;
    bool ok = sensor.read(r);                                  // Forced PS + ALS, (reading&) → bool
    periph_printf("prox %u UV %u.%02u vis %u ir %u (ok=%u)\n", (unsigned)r.proximity, uv_whole(r.uv_index),
          uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared, (unsigned)ok);
    delay(1000);
}
