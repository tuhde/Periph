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
    LSM303Full compass(accel_connection, mag_connection);
    static const char* NAMES[16] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    compass.set_range(2);                                   // Set accelerometer range, (range_g) -> g
    compass.set_mode(2);                                    // Set accelerometer mode, (mode) -> None
    compass.set_data_rate(50);                              // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_mag_range(130);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(15.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz

    compass.set_click(0, 0x04, 0.8f, 20, 40, 200);          // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms) -> None
    compass.set_free_fall(1, 0.35f, 60);                    // Configure free-fall, (generator, threshold_g, duration_ms) -> None
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);  // Route source to INT1, (source, pin) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_IA1, 1);    // Route source to INT1, (source, pin) -> None
    compass.set_fifo(LSM303Full::FIFO_STREAM, 16, false);   // Configure FIFO, (mode, watermark, trigger_int2) -> None

    uint32_t start = lsm303_now_ms();
    uint32_t last = start;
    while (lsm303_now_ms() - start < 60000) {
        uint32_t now = lsm303_now_ms();
        if (now - last >= 1000) {
            uint8_t level = compass.fifo_level();           // Read FIFO level, () -> int
            float xb[32], yb[32], zb[32];
            uint8_t n = compass.read_fifo(xb, yb, zb, 32);  // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
            float ax = 0, ay = 0, az = 0;
            if (n > 0) {
                for (uint8_t i = 0; i < n; i++) { ax += xb[i]; ay += yb[i]; az += zb[i]; }
                ax /= n; ay /= n; az /= n;
            } else {
                compass.read_acceleration(ax, ay, az);      // Read 3-axis acceleration, (x, y, z) -> g, g, g
            }
            float mx, my, mz;
            compass.read_magnetic(mx, my, mz);              // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
            float field = sqrtf(mx * mx + my * my + mz * mz);
            float pitch = atan2f(ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
            float roll = atan2f(ay, az) * 57.2957795f;
            float hdg = compass.heading(true);              // Compute compass heading, (tilt_compensate) -> deg
            const char* name = NAMES[(int)((hdg + 11.25f) / 22.5f) % 16];
            float temp = compass.read_temperature_delta();  // Read relative temperature, () -> float C
            LOG("%5.1f deg %s  pitch=%+.1f roll=%+.1f |B|=%.1f uT  T=%+.1f C  fifo=%d\n",
                hdg, name, pitch, roll, field, temp, level);

            uint16_t isr = compass.poll_interrupt();        // Read interrupt status, () -> int
            if (isr & LSM303Full::SOURCE_CLICK) LOG("DOUBLE TAP\n");
            if (isr & LSM303Full::SOURCE_IA1)   LOG("FREE FALL\n");
            last = now;
        }
        lsm303_sleep_ms(10);
    }
    LOG("done\n");
}

void loop() { delay(1000); }
