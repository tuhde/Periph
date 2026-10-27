#include <Wire.h>
#include <Periph.h>
#include <math.h>
#include <stdarg.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

I2CConnection accel_connection(Wire, 0x19);
I2CConnection mag_connection(Wire, 0x1E);

static char _logbuf[160];
static void logf_(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(_logbuf, sizeof(_logbuf), fmt, ap);
    va_end(ap);
    Serial.print(_logbuf);
}
#define LOG(...) logf_(__VA_ARGS__)

static void lsm303_sleep_ms(uint32_t ms) { delay(ms); }
static uint32_t lsm303_now_ms() { return millis(); }
static void on_int(uint16_t status) { LOG("interrupt status: 0x%04X\n", status); }

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    LSM303Minimal compass(accel_connection, mag_connection);
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.read_acceleration(ax, ay, az);              // Read 3-axis acceleration, (x, y, z) -> g, g, g
        compass.read_magnetic(mx, my, mz);                  // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
        LOG("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT\n", ax, ay, az, mx, my, mz);
        lsm303_sleep_ms(100);
    }
}

void loop() { delay(1000); }
