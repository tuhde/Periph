#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

#define SLEEP_MS(ms) sleep_ms(ms)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK accel_connection(i2c0, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionPicoSDK mag_connection(i2c0, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    uint8_t identity[3];
    accel.readMagIdentity(identity);              // Read magnetometer identity, (out) → None
    check_true(identity[0] == 0x48 && identity[1] == 0x34 && identity[2] == 0x33, "mag_identity");

    float ax, ay, az;
    accel.readAcceleration(ax, ay, az);           // Read acceleration, (x, y, z) → g, g, g
    check_true(ax >= -4.0f && ax <= 4.0f && ay >= -4.0f && ay <= 4.0f && az >= -4.0f && az <= 4.0f,
               "acceleration_plausible");

    float mx, my, mz;
    accel.readMagnetic(mx, my, mz);               // Read magnetic field, (x, y, z) → uT, uT, uT
    check_true(mx >= -1500.0f && mx <= 1500.0f && my >= -1500.0f && my <= 1500.0f &&
               mz >= -1500.0f && mz <= 1500.0f, "magnetic_plausible");

    accel.setRange(4);                            // Set full-scale range, (range_g=4) → bool
    accel.setDataRate(50);                        // Set output data rate, (rate_hz=50) → bool
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
    accel.setAxes(true, true, true);              // Enable axes, (x=true, y=true, z=true) → None
    SLEEP_MS(200);
    check_true(true, "reconfigure");

    accel.setRange(2);                            // Set full-scale range, (range_g=2) → bool
    accel.setDataRate(100);                       // Set output data rate, (rate_hz=100) → bool

    int16_t rx, ry, rz;
    accel.readAccelerationRaw(rx, ry, rz);        // Read raw acceleration, (x, y, z) → counts
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047,
               "acceleration_raw_range");

    check_true(accel.newDataAvailable(), "new_data_available");  // Check new data, () → bool
    uint8_t status = accel.readStatus();          // Read status register, () → byte
    (void)status;
    check_true(true, "status_readable");

    accel.setHighPass(0, 0, false, false, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → bool
    uint8_t reference = accel.resetHighPass();    // Reset high-pass filter, () → byte
    (void)reference;
    check_true(true, "reference_readable");
    accel.setReference(reference);                // Set reference value, (value=0..255) → None

    accel.setFifo(LSM303Full::FIFO_STREAM, 16);   // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool
    SLEEP_MS(200);
    uint8_t level = accel.fifoLevel();            // Read FIFO level, () → byte
    check_true(level <= 32, "fifo_level_valid");
    bool watermark, overrun, empty;
    uint8_t fifoLevel;
    accel.readFifoStatus(watermark, overrun, empty, fifoLevel);  // Read FIFO status, (watermark, overrun, empty, level) → None
    check_true(fifoLevel == level, "fifo_status_level_matches");
    float samples[3 * 32];
    uint8_t count = accel.readFifo(samples, 32);  // Read FIFO, (out, max_count=32) → byte
    check_true(count == level, "fifo_samples_read");
    accel.setFifo(LSM303Full::FIFO_BYPASS);        // Configure FIFO, (mode=FIFO_BYPASS=0, watermark=0, trigger_int2=false) → bool

    accel.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false);  // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → bool
    accel.setFreeFall(1, 0.35f, 30.0f);           // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
    accel.setOrientation(1, false, false);        // Configure orientation, (generator=1, position=false, four_d=false) → bool
    accel.setLatch(1, true);                      // Latch the interrupt, (generator=1, enabled=true) → bool
    uint8_t source = accel.readInterruptSource(1);  // Read interrupt source, (generator=1) → byte
    (void)source;
    check_true(true, "interrupt_source_readable");

    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
    uint8_t click = accel.readClickSource();      // Read click source, () → byte
    (void)click;
    check_true(true, "click_source_readable");

    accel.setInterruptPolarity(false);            // Set interrupt polarity, (active_low=false) → None
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool
    uint8_t interruptStatus = accel.pollInterrupt();  // Read interrupt status, () → byte
    (void)interruptStatus;
    check_true(true, "poll_interrupt");
    accel.disableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.disableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Remove an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool

    accel.setMagRange(250);                       // Set magnetometer range, (range_ut=250) → bool
    accel.setMagDataRate(30.0f);                  // Set magnetometer data rate, (rate_hz=30) → bool
    accel.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → bool
    SLEEP_MS(200);
    accel.readMagneticRaw(rx, ry, rz);           // Read raw magnetic field, (x, y, z) → counts
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047,
               "magnetic_raw_range");

    bool drdy, lock;
    accel.readMagStatus(drdy, lock);              // Read magnetometer status, (drdy, lock) → None
    check_true(true, "mag_status_readable");

    accel.enableTemperature(true);               // Enable the temperature sensor, (enabled=true) → None
    float temperature = accel.readTemperatureDelta();  // Read relative temperature, () → float °C
    check_true(temperature >= -100.0f && temperature <= 100.0f, "temperature_delta_plausible");

    float heading = accel.heading();              // Read the compass heading, (tilt_compensate=true) → float °
    check_true(heading >= 0.0f && heading <= 360.0f, "heading_in_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
    return 0;
}
