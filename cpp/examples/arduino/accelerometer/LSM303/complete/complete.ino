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
    // --- Accelerometer range, mode, data rate, axes ---
    compass.set_range(8);                                   // Set accelerometer range, (range_g) -> g
    compass.set_mode(1);                                    // Set accelerometer mode, (mode) -> None
    compass.set_data_rate(400);                             // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_axes(true, true, true);                     // Enable accelerometer axes, (x, y, z) -> None

    // --- Power cycle and restore defaults ---
    compass.power_down();                                   // Stop accelerometer conversions, () -> None
    compass.set_data_rate(100);                             // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_mode(2);                                    // Set accelerometer mode, (mode) -> None
    compass.set_range(2);                                   // Set accelerometer range, (range_g) -> g

    // --- Reads ---
    float ax, ay, az;
    compass.read_acceleration(ax, ay, az);                  // Read 3-axis acceleration, (x, y, z) -> g, g, g
    int16_t rx, ry, rz;
    compass.read_acceleration_raw(rx, ry, rz);              // Read raw acceleration counts, (x, y, z) -> int, int, int
    bool ready = compass.new_data_available();              // Check new acceleration data, () -> bool
    uint8_t status = compass.read_status();                 // Read accelerometer status, () -> int

    // --- High-pass filter ---
    compass.set_high_pass(1, 2, true, true, false, true);   // Configure high-pass filter, (mode, cutoff, filter_output, on_ia1, on_ia2, on_click) -> None
    uint8_t ref = compass.reset_high_pass();                // Reset high-pass filter, () -> int
    compass.set_reference(0x40);                            // Set high-pass reference, (value) -> None

    // --- FIFO ---
    compass.set_fifo(LSM303Full::FIFO_FIFO, 16, false);     // Configure FIFO, (mode, watermark, trigger_int2) -> None
    uint8_t level = compass.fifo_level();                   // Read FIFO level, () -> int
    bool wm, ov, empty;
    uint8_t lvl;
    compass.read_fifo_status(wm, ov, empty, lvl);           // Read FIFO status, () -> (bool, bool, bool, int)
    float xb[8], yb[8], zb[8];
    uint8_t n = compass.read_fifo(xb, yb, zb, 8);           // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
    compass.set_fifo(LSM303Full::FIFO_BYPASS);              // Configure FIFO, (mode, watermark, trigger_int2) -> None

    // --- Inertial interrupt generators ---
    compass.set_motion_interrupt(1, 0x3F, 0.5f, 30, true); // Configure motion interrupt, (generator, events, threshold_g, duration_ms, and_mode) -> None
    compass.set_free_fall(1, 0.35f, 30);                    // Configure free-fall, (generator, threshold_g, duration_ms) -> None
    compass.set_orientation(1, false, false);               // Configure orientation, (generator, position, four_d) -> None
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled) -> None
    uint8_t src1 = compass.read_interrupt_source(1);       // Read interrupt source, (generator) -> int
    compass.set_latch(1, false);                            // Latch generator 1, (generator, enabled) -> None

    // --- Click detection ---
    compass.set_click(0x01, 0x04, 0.5f, 10, 20, 50);        // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms) -> None
    uint8_t click = compass.read_click_source();            // Read click source, () -> int

    // --- Interrupt routing and delivery ---
    compass.set_interrupt_polarity(false);                  // Set interrupt polarity, (active_low) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);  // Enable interrupt source, (source, pin) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);  // Enable interrupt source, (source, pin) -> None
    compass.disable_interrupt(LSM303Full::SOURCE_DATA_READY, 1); // Disable interrupt source, (source, pin) -> None
    compass.on_interrupt(on_int, 1);                        // Subscribe to INT1, (callback, pin) -> None
    uint16_t isr = compass.poll_interrupt();                // Poll interrupt status, () -> int
    compass.off_interrupt(1);                               // Unsubscribe from INT1, (pin) -> None

    // --- Magnetometer ---
    compass.set_mag_range(470);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(30.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz
    compass.set_mag_mode(1);                                // Set magnetometer mode, (mode) -> None
    compass.set_mag_mode(0);                                // Set magnetometer mode, (mode) -> None
    int16_t mrx, mry, mrz;
    compass.read_magnetic_raw(mrx, mry, mrz);               // Read raw magnetic counts, (x, y, z) -> int, int, int
    float mx, my, mz;
    compass.read_magnetic(mx, my, mz);                      // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
    bool mready = compass.mag_data_ready();                 // Check new magnetometer data, () -> bool
    bool drdy, lock;
    compass.read_mag_status(drdy, lock);                    // Read magnetometer status, () -> (bool, bool)
    uint8_t identity[3];
    compass.read_mag_identity(identity);                    // Read magnetometer identity, () -> bytes
    compass.enable_temperature(true);                       // Enable temperature sensor, (enabled) -> None
    float temp = compass.read_temperature_delta();          // Read relative temperature, () -> float C
    float heading = compass.heading(true);                  // Compute compass heading, (tilt_compensate) -> deg
    float flat = compass.heading(false);                    // Compute compass heading, (tilt_compensate) -> deg

    // --- System ---
    compass.reboot();                                       // Reboot accelerometer memory, () -> None

    LOG("accel: x=%.3f y=%.3f z=%.3f g\n", ax, ay, az);
    LOG("raw: %d %d %d  ready=%d status=0x%02X\n", rx, ry, rz, ready ? 1 : 0, status);
    LOG("reference=0x%02X fifo level=%d wm=%d ov=%d empty=%d read=%d\n",
        ref, level, wm ? 1 : 0, ov ? 1 : 0, empty ? 1 : 0, n);
    LOG("int1_src=0x%02X click_src=0x%02X isr=0x%04X\n", src1, click, isr);
    LOG("mag: x=%.1f y=%.1f z=%.1f uT  raw=%d %d %d\n", mx, my, mz, mrx, mry, mrz);
    LOG("mag ready=%d drdy=%d lock=%d id=%02X %02X %02X\n",
        mready ? 1 : 0, drdy ? 1 : 0, lock ? 1 : 0, identity[0], identity[1], identity[2]);
    LOG("temp=%+.1f C  heading=%.1f deg  flat=%.1f deg\n", temp, heading, flat);
}

void loop() { delay(1000); }
