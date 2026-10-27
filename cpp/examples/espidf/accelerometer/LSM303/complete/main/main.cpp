#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LSM303.h"

// Interrupt callback: receives the poll status byte. Not called in this example (no INT pin wired).
static void onInt(uint8_t status) {
    printf("int status=0x%02X\n", status);
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    // Accelerometer at 0x19 and magnetometer at 0x1E share the bus; each gets its own device handle.
    i2c_device_config_t accel_cfg = {};
    accel_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    accel_cfg.device_address = 0x19;
    accel_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t accelDev;
    i2c_master_bus_add_device(bus, &accel_cfg, &accelDev);

    i2c_device_config_t mag_cfg = {};
    mag_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    mag_cfg.device_address = 0x1E;
    mag_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_cfg, &magDev);

    I2CConnectionESPIDF accelConn(accelDev);                // Accelerometer connection at 0x19
    I2CConnectionESPIDF magConn(magDev);                    // Magnetometer connection at 0x1E

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz
                                                            // identity check runs here; valid() reports the result
    printf("valid=%d\n", chip.valid() ? 1 : 0);

    uint8_t id[3];
    chip.readMagIdentity(id);                               // Read magnetometer identity, (buf) → void
                                                            // copies IRA/IRB/IRC (0x48 0x34 0x33 on a genuine part)

    // --- Accelerometer configuration ---
    chip.setRange(4);                                       // Set accel full scale, (range_g=4) → bool
                                                            // selects ±4 g; updates the cached 2 mg/LSB sensitivity
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
                                                            // high-resolution (12-bit): LPen=0, HR=1
    chip.setDataRate(200);                                  // Set accel output rate, (rate_hz=200) → bool
                                                            // 200 Hz is legal in high-resolution mode
    chip.setAxes(true, true, true);                         // Enable axes, (x=true, y=true, z=true) → void
                                                            // Xen, Yen and Zen all set
    chip.setHighPass(0, 0, false, false, false, false);     // Configure high-pass, (mode=0, cutoff=0, filter_output=false, ...) → void
                                                            // filter bypassed on output and all generators
    chip.setReference(0);                                   // Set high-pass reference, (value=0) → void
                                                            // REFERENCE_A value used only in HPM=01
    uint8_t ref = chip.resetHighPass();                     // Reset high-pass filter, () → uint8
                                                            // reading REFERENCE_A resets the filter in HPM=00
    (void)ref;
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=false) → bool
                                                            // stream mode, watermark 16 samples, trigger on INT1

    // --- Interrupts ---
    chip.setFreeFall(1, 0.35f, 30.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                            // all three axes below threshold, AND mode
    chip.setOrientation(2, false, false);                   // 6D movement on generator 2, (generator=2, position=false, four_d=false) → bool
                                                            // reports orientation changes between zones
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=true) → bool
                                                            // holds IA1 until INT1_SOURCE_A is read
    chip.setClick(0x05, 0x02, 0.5f, 10.0f, 20.0f, 50.0f);   // Configure click, (single=0x05, double=0x02, threshold_g=0.5, ...) → void
                                                            // single on X and Z, double on Y
    chip.setInterruptPolarity(false);                       // Set INT polarity, (active_low=false) → void
                                                            // INT1 and INT2 active-high
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool
                                                            // generator 1 event appears on INT1
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
                                                            // single/double click appears on INT1
    chip.disableInterrupt(LSM303Source::CLICK, 1);          // Remove click from INT1, (source, pin=1) → bool
                                                            // click no longer routed to INT1
    bool subscribed = chip.onInterrupt(onInt, 1);           // Subscribe to INT1, (callback, pin=1, int_pin=nullptr) → bool
                                                            // returns false here: no InputPin is wired to accelConn
    (void)subscribed;
    chip.offInterrupt(1);                                   // Unsubscribe from INT1, (pin=1) → void
                                                            // removes this driver's handler, if any

    // --- Magnetometer configuration ---
    chip.setMagRange(190);                                  // Set mag full scale, (range_ut=190) → bool
                                                            // ±1.9 gauss; updates the cached gains
    chip.setMagDataRate(30.0f);                             // Set mag output rate, (rate_hz=30) → bool
                                                            // DO=101; TEMP_EN is kept
    chip.setMagMode(0);                                     // Set mag mode, (mode=0) → bool
                                                            // continuous conversion
    chip.enableTemperature(true);                           // Enable temperature, (enabled=true) → void
                                                            // sets TEMP_EN in CRA_REG_M

    // --- Read everything once ---
    vTaskDelay(pdMS_TO_TICKS(300));                         // Let the sensors produce samples (7/ODR and 1/30 Hz)
    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
                                                            // six-byte burst with auto-increment, converted to g
    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);                   // Read raw acceleration, (x, y, z) → counts
                                                            // shifted to the current resolution (12-bit in HR)
    bool fresh = chip.newDataAvailable();                   // Check new accel data, () → bool
                                                            // STATUS_REG_A.ZYXDA
    uint8_t st = chip.readStatus();                         // Read accel status, () → uint8
                                                            // raw STATUS_REG_A byte
    bool wm, ov, em;
    uint8_t lvl;
    chip.readFifoStatus(wm, ov, em, lvl);                   // Decode FIFO status, (watermark, overrun, empty, level) → void
                                                            // FIFO_SRC_REG_A fields
    uint8_t fl = chip.fifoLevel();                          // Read FIFO level, () → uint8
                                                            // unread sample sets, 0–32
    float xs[8], ys[8], zs[8];
    uint8_t got = chip.readFifo(xs, ys, zs, 8);             // Drain FIFO, (x, y, z, count=8) → uint8
                                                            // up to 8 sample sets, in g
    uint8_t src1 = chip.readInterruptSource(1);             // Read INT1 source, (generator=1) → uint8
                                                            // reading clears IA1
    uint8_t csrc = chip.readClickSource();                  // Read click source, () → uint8
                                                            // reading clears the click flag
    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);                          // Read magnetic field, (x, y, z) → µT, µT, µT
                                                            // reordered from X, Z, Y on the wire
    int16_t mrx, mry, mrz;
    chip.readMagneticRaw(mrx, mry, mrz);                    // Read raw magnetic field, (x, y, z) → counts
                                                            // signed counts in X, Y, Z order
    bool mdr = chip.magDataReady();                         // Check new mag data, () → bool
                                                            // SR_REG_M.DRDY
    bool drdy, lock;
    chip.readMagStatus(drdy, lock);                         // Decode mag status, (drdy, lock) → void
                                                            // SR_REG_M fields
    float dT = chip.readTemperatureDelta();                 // Read temperature, () → float °C
                                                            // relative delta at 8 LSB/°C, no absolute reference
    float hdg = chip.heading(true);                         // Tilt-compensated heading, (tilt_compensate=true) → deg
                                                            // atan2 of the tilt-corrected field, 0–360
    uint8_t poll = chip.pollInterrupt();                    // Poll interrupt sources, () → uint8
                                                            // combined status of every routed source

    printf("a=%.3f %.3f %.3f g\n", ax, ay, az);
    printf("raw=%d %d %d\n", rx, ry, rz);
    printf("fresh=%d status=0x%02X fifo_level=%u got=%u\n", fresh ? 1 : 0, st, fl, got);
    printf("wm=%d ov=%d em=%d lvl=%u\n", wm ? 1 : 0, ov ? 1 : 0, em ? 1 : 0, lvl);
    printf("src1=0x%02X click=0x%02X\n", src1, csrc);
    printf("m=%.1f %.1f %.1f uT\n", mx, my, mz);
    printf("mraw=%d %d %d\n", mrx, mry, mrz);
    printf("mdr=%d drdy=%d lock=%d\n", mdr ? 1 : 0, drdy ? 1 : 0, lock ? 1 : 0);
    printf("temp_delta=%.2f C heading=%.1f deg\n", dT, hdg);
    printf("poll=0x%02X\n", poll);

    // --- Power management and maintenance ---
    chip.setMagMode(2);                                     // Set mag mode, (mode=2) → bool
                                                            // sleep mode to save power
    chip.powerDown();                                       // Power down accelerometer, () → void
                                                            // ODR=0; register contents are kept
    chip.reboot();                                          // Reboot accelerometer memory, () → void
                                                            // BOOT bit, wait 5 ms, then re-run the init sequence
}
