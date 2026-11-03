#include <Wire.h>
#include <math.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x1C);
MMA8451QFull accel(connection);                           // Create MMA8451Q driver, (connection)

// --- Configure ±4 g, 100 Hz, high-resolution oversampling ---
// ±4 g gives 2048 counts/g, enough headroom for a 1.0 g tap threshold
// while still covering a free-fall event. High-resolution oversampling
// lowers the noise floor, which matters because the orientation detector
// trips on small changes of the gravity vector.
void setup() {
    Serial.begin(115200);
    Wire.begin();

    accel.set_range(4);                                  // Set measurement range, (range_g=2) → g
                                                          // ±4 g, 2048 counts/g
    accel.set_data_rate(100);                            // Set output data rate, (rate_hz=100) → Hz
                                                          // 100 Hz — 10 ms between samples
    accel.set_oversampling(2, 0);                        // Set oversampling mode, (mode=0, sleep_mode=-1) → None
                                                          // mode 2 = high resolution in WAKE, normal in SLEEP

    bool st_ok = false;
    int16_t dx = 0, dy = 0, dz = 0;
    accel.self_test(st_ok, dx, dy, dz);                  // Run self-test, () → bool, int, int, int
                                                          // electrostatic self-test; prints the per-axis deltas
    Serial.print("self-test: ");
    Serial.print(st_ok ? "PASS" : "FAIL");
    Serial.print(" dx="); Serial.print(dx);
    Serial.print(" dy="); Serial.print(dy);
    Serial.print(" dz="); Serial.println(dz);

    accel.set_orientation(true, 100.0f, 45, 4, 4, 1);    // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
                                                          // portrait/landscape at 45°, 100 ms debounce, ±14° hysteresis
    accel.set_pulse(0, 0x04, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
                                                          // Configure pulse/tap, (single, double, ths_x, ths_y, ths_z, tmlt, latency, window, latch, abort, lpf, hpf) → None
                                                          // double tap on Z, 1.0 g threshold, latched
    accel.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
                                                          // Configure freefall/motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
                                                          // freefall below 0.2 g on all axes, 60 ms debounce, latched
    accel.set_auto_sleep(true, 12.5f, 10.0f,
                         MMA8451QFull::SOURCE_PULSE | MMA8451QFull::SOURCE_FF_MT);
                                                          // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
                                                          // 12.5 Hz sleep rate, 10 s timeout, wake on tap or freefall

    accel.enable_interrupt(MMA8451QFull::SOURCE_ORIENTATION, 1);
                                                          // Enable interrupt source, (source, pin=1) → None
                                                          // orientation change on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_PULSE, 1);  // Enable interrupt source, (source, pin=1) → None
                                                          // tap on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_FF_MT, 1);   // Enable interrupt source, (source, pin=1) → None
                                                          // freefall on INT1

    accel.set_fifo(1, 16);                               // Configure the FIFO, (mode=0, watermark=0) → None
                                                          // circular FIFO with a 16-sample watermark
}

// --- 60-second orientation / tap / free-fall monitor ---
// The chip sleeps at 12.5 Hz after 10 s of inactivity and wakes on a tap
// or a free-fall event. Every 250 ms the acceleration and the current
// system mode are printed; whenever poll_interrupt() reports an event the
// matching detail is printed too.
void loop() {
    static uint32_t last_print = 0;
    uint32_t now = millis();
    if (now - last_print < 250) return;
    last_print = now;

    float x, y, z;
    accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
    uint8_t mode = accel.read_system_mode();            // Read system mode, () → byte
                                                          // SYSMOD bits 1:0: 0 standby, 1 wake, 2 sleep

    Serial.print("x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.print(" mode=");
    Serial.print(mode == 0 ? "STANDBY" : (mode == 1 ? "WAKE" : "SLEEP"));

    uint8_t status = accel.poll_interrupt();            // Read interrupt status, () → byte
                                                          // INT_SOURCE; the source flags are cleared by the read
    if (status & MMA8451QFull::SOURCE_ORIENTATION) {
        bool changed = false, lockout = false, back = false;
        uint8_t lapo = 0;
        accel.read_orientation(changed, lockout, lapo, back);
                                                          // Read orientation, (changed, lockout, lapo, back) → bool, bool, byte, bool
        static const char* names[4] = { "portrait up", "portrait down",
                                        "landscape right", "landscape left" };
        Serial.print(" ORIENTATION=");
        Serial.print(names[lapo & 0x03]);
        if (back) Serial.print(" (back)");
        if (lockout) Serial.print(" (lockout)");
    }
    if (status & MMA8451QFull::SOURCE_PULSE) {
        uint8_t pulse = accel.read_pulse_source();      // Read pulse source, () → byte
                                                          // PULSE_SRC; reading it clears the interrupt
        Serial.print(" DOUBLE TAP src=0x");
        Serial.print(pulse, HEX);
    }
    if (status & MMA8451QFull::SOURCE_FF_MT) {
        uint8_t motion = accel.read_motion_source();    // Read motion source, () → byte
                                                          // FF_MT_SRC; reading it clears the latched flags
        Serial.print(" FREE FALL src=0x");
        Serial.print(motion, HEX);
    }

    uint8_t level = accel.fifo_level();                 // Read FIFO level, () → byte
                                                          // number of samples buffered in the FIFO
    Serial.print(" fifo="); Serial.print(level);
    Serial.println();
}
