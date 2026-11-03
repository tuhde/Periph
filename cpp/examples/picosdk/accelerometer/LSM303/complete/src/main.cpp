#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

#define PRINTF(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK accel_connection(i2c0, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionPicoSDK mag_connection(i2c0, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)


    float ax, ay, az;
    float mx, my, mz;
    accel.readAcceleration(ax, ay, az);          // Read acceleration, (x, y, z) → g, g, g
                                                 // burst-reads the six output registers
    accel.readMagnetic(mx, my, mz);                // Read magnetic field, (x, y, z) → uT, uT, uT
                                                 // burst-reads X, Z, Y and reorders them
    PRINTF("accel x=%.4f y=%.4f z=%.4f g\n", (double)ax, (double)ay, (double)az);
    PRINTF("mag   x=%.2f y=%.2f z=%.2f uT\n", (double)mx, (double)my, (double)mz);

    int16_t rx, ry, rz;
    accel.readAccelerationRaw(rx, ry, rz);       // Read raw acceleration, (x, y, z) → counts
                                                 // signed counts at the current resolution
    PRINTF("raw accel %d %d %d\n", (int)rx, (int)ry, (int)rz);

    accel.setRange(4);                           // Set full-scale range, (range_g=4) → bool
                                                 // rewrites FS in CTRL_REG4_A and updates the sensitivity
    accel.setDataRate(50);                       // Set output data rate, (rate_hz=50) → bool
                                                 // rewrites ODR in CTRL_REG1_A
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
                                                 // LPen/HR select low-power, normal or high-resolution
    accel.setAxes(true, true, true);             // Enable axes, (x=true, y=true, z=true) → None
                                                 // rewrites Xen, Yen and Zen
    SLEEP_MS(200);

    accel.powerDown();                           // Power down the accelerometer, () → None
                                                 // ODR = 0000, the register contents are retained
    accel.setDataRate(100);                      // Set output data rate, (rate_hz=100) → bool
                                                 // leaves power-down and starts the conversions again

    accel.setHighPass(0, 0, false, true, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → bool
                                                 // rewrites HPM, HPCF, FDS and the per-generator enables
    PRINTF("reference %d\n", (int)accel.resetHighPass());  // Reset high-pass filter, () → byte
                                                 // reads REFERENCE_A, which resets the filter
    accel.setReference(0);                       // Set reference value, (value=0) → None
                                                 // the value the high-pass filter compares against

    accel.setFifo(LSM303Full::FIFO_STREAM, 16);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool
                                                 // stream mode with a 16-sample watermark
    PRINTF("fifo level %d\n", (int)accel.fifoLevel());  // Read FIFO level, () → byte
                                                 // number of unread sample sets
    bool watermark, overrun, empty;
    uint8_t level;
    accel.readFifoStatus(watermark, overrun, empty, level);  // Read FIFO status, (watermark, overrun, empty, level) → None
                                                 // decodes FIFO_SRC_REG_A
    PRINTF("fifo status wm=%d ov=%d empty=%d level=%d\n", (int)watermark, (int)overrun, (int)empty, (int)level);
    float samples[3 * 8];
    uint8_t count = accel.readFifo(samples, 8);  // Read FIFO, (out, max_count=8) → byte
                                                 // pops one sample set per six-byte burst read
    PRINTF("fifo samples %d\n", (int)count);

    accel.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false);  // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → bool
                                                 // writes INT1_CFG_A, INT1_THS_A and INT1_DURATION_A
    accel.setFreeFall(1, 0.35f, 30.0f);          // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                 // all three axes low, combined with AND
    accel.setOrientation(1, false, false);       // Configure orientation, (generator=1, position=false, four_d=false) → bool
                                                 // 6D movement recognition on all three axes
    accel.setLatch(1, true);                     // Latch the interrupt, (generator=1, enabled=true) → bool
                                                 // the INT1 line stays asserted until the source is read
    PRINTF("int1 source 0x%02X\n", (int)accel.readInterruptSource(1));  // Read interrupt source, (generator=1) → byte
                                                 // reads INT1_SOURCE_A, which clears the latched flag

    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
                                                 // writes CLICK_CFG_A, CLICK_THS_A and the three time registers
    PRINTF("click source 0x%02X\n", (int)accel.readClickSource());  // Read click source, () → byte
                                                 // reads CLICK_SRC_A, which clears the latched flag

    accel.setInterruptPolarity(false);           // Set interrupt polarity, (active_low=false) → None
                                                 // H_LACTIVE in CTRL_REG6_A; false keeps the active-high default
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // only the routing is set up, the generator is configured separately
    accel.disableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // clears the routing bit again

    accel.setMagRange(250);                        // Set magnetometer range, (range_ut=250) → bool
                                                 // rewrites GN in CRB_REG_M and updates the gains
    accel.setMagDataRate(30.0f);                   // Set magnetometer data rate, (rate_hz=30) → bool
                                                 // rewrites DO in CRA_REG_M, keeping TEMP_EN
    accel.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → bool
                                                 // continuous, single or sleep conversion
    accel.readMagneticRaw(rx, ry, rz);            // Read raw magnetic field, (x, y, z) → counts
                                                 // signed counts in X, Y, Z order
    PRINTF("raw mag %d %d %d\n", (int)rx, (int)ry, (int)rz);
    bool drdy, lock;
    accel.readMagStatus(drdy, lock);              // Read magnetometer status, (drdy, lock) → None
                                                 // decodes SR_REG_M
    PRINTF("mag drdy=%d lock=%d\n", (int)drdy, (int)lock);
    uint8_t identity[3];
    accel.readMagIdentity(identity);              // Read magnetometer identity, (out) → None
                                                 // returns the three identity bytes
    PRINTF("identity 0x%02X 0x%02X 0x%02X\n", (int)identity[0], (int)identity[1], (int)identity[2]);
    accel.enableTemperature(true);                // Enable the temperature sensor, (enabled=true) → None
                                                 // sets TEMP_EN in CRA_REG_M
    PRINTF("temperature %.2f C\n", (double)accel.readTemperatureDelta());  // Read relative temperature, () → float °C
                                                 // 8 LSB/°C, relative to an unspecified reference

    PRINTF("heading %.1f deg\n", (double)accel.heading());  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // tilt-compensated with the accelerometer
    PRINTF("flat heading %.1f deg\n", (double)accel.heading(false));  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // the magnetic-only heading

    PRINTF("status 0x%02X\n", (int)accel.readStatus());  // Read status register, () → byte
                                                 // raw STATUS_REG_A byte
    PRINTF("new data %d\n", (int)accel.newDataAvailable());  // Check new data, () → bool
                                                 // reads STATUS_REG_A.ZYXDA

    accel.reboot();                             // Reboot the accelerometer, () → None
                                                 // reloads the factory trim values and re-runs init()
    return 0;
}
