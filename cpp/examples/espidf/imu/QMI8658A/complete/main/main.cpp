#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "QMI8658A.h"

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

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x6B;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    QMI8658AFull imu(connection);     // Create QMI8658A driver, (connection) → void

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
                                                  // converts raw counts at 16384 LSB/g (±2 g)
    imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
                                                  // converts raw counts at 128 LSB/dps (±256 dps)
    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);                 // Read raw accel counts, (int16_t&, int16_t&, int16_t&) → void
                                                  // one 6-byte burst, signed 16-bit
    imu.gyro_raw(rgx, rgy, rgz);                  // Read raw gyro counts, (int16_t&, int16_t&, int16_t&) → void
                                                  // one 6-byte burst, signed 16-bit
    float a[3], g[3], t;
    imu.read_all(a, g, t);                        // Read one coherent sample, (float[3], float[3], float&) → void m/s², rad/s, °C
                                                  // one 14-byte burst of registers 0x33…0x40
    t = imu.temperature();                        // Read die temperature, () → float °C
                                                  // signed 8.8 fixed point, raw / 256
    uint32_t ts = imu.timestamp();                // Read sample counter, () → uint32_t
                                                  // 24-bit counter, wraps at 0xFFFFFF
    bool ready = imu.data_ready();                // Check for new data, () → bool
                                                  // STATUS0 aDA / gDA
    imu.configure_accel(1, 5);                    // Configure accelerometer, (full_scale=0, odr=5) → void
                                                  // CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; ODR code 5 = 224.2 Hz
    imu.configure_gyro(5, 5);                     // Configure gyroscope, (full_scale=4, odr=5) → void
                                                  // CTRL3: 0=±16 … 7=±2048 dps
    imu.configure_filters(true, 3, true, 3);      // Configure low-pass filters, (accel_filter=true, accel_mode=3, gyro_filter=true, gyro_mode=3) → void
                                                  // CTRL5: mode 3 = 13.37 % of ODR
    imu.set_sensors(true, true, false);           // Enable sensors, (accel=true, gyro=true, gyro_snooze=false) → void
                                                  // CTRL7 aEN / gEN / gSN
    imu.configure_fifo(2, 1, 16);                 // Configure FIFO, (mode=2, size=3, watermark=0) → void
                                                  // stream mode, 32 samples, watermark 16
    vTaskDelay(pdMS_TO_TICKS(500));
    uint8_t fifo_flags = imu.fifo_status();       // Read FIFO flags, () → uint8_t
                                                  // raw FIFO_STATUS byte
    uint16_t fifo_bytes = imu.fifo_count();       // Read FIFO fill, () → uint16_t bytes
                                                  // two bytes per sample word
    uint8_t fifo[96];
    uint16_t got = imu.read_fifo(fifo, sizeof(fifo)); // Drain the FIFO, (uint8_t*, uint16_t) → uint16_t
                                                  // REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
    imu.reset_fifo();                             // Reset the FIFO, () → void
                                                  // CTRL9 RST_FIFO
    imu.configure_fifo(0);                        // Back to bypass, (mode=2, size=3, watermark=0) → void
                                                  // bypass re-enables data-ready
    imu.configure_any_motion(0.25f, 3, 0x07, false); // Configure any-motion, (threshold_g=0.25, window=3, axes=0x07, logic_and=false) → void
                                                  // two CTRL9 CONFIGURE_MOTION sets
    imu.configure_no_motion(0.125f, 30, 0x07, true); // Configure no-motion, (threshold_g=0.125, window=30, axes=0x07, logic_and=true) → void
                                                  // same command, no-motion fields
    imu.configure_significant_motion(150, 600);   // Configure significant motion, (wait_window=150, confirm_window=600) → void
                                                  // wait and confirm windows in samples
    imu.enable_motion(true, true, true);          // Enable motion engines, (any_motion=false, no_motion=false, significant=false) → void
                                                  // CTRL8 bits 1–3
    imu.enable_motion();                          // Disable motion engines, (any_motion=false, no_motion=false, significant=false) → void
                                                  // clears CTRL8 bits 1–3
    imu.configure_tap();                          // Configure tap engine, (priority=0, peak_window=20, tap_window=50, double_tap_window=250, alpha=0.0625, gamma=0.25, peak_threshold=0.8, quiet_threshold=0.4) → void
                                                  // two CTRL9 CONFIGURE_TAP sets
    imu.enable_tap(true);                         // Enable tap engine, (enabled=true) → void
                                                  // CTRL8.Tap_EN
    uint8_t taps, axis; bool negative;
    imu.tap_status(taps, axis, negative);         // Read tap event, (uint8_t&, uint8_t&, bool&) → void
                                                  // count 0/1/2, axis 0–3, polarity
    imu.enable_tap(false);                        // Disable tap engine, (enabled=true) → void
                                                  // clears CTRL8.Tap_EN
    imu.configure_pedometer();                    // Configure pedometer, (sample_count=50, peak_to_peak_mg=200, peak_mg=100, time_up=200, time_low=20, entry_count=10, precision=0, report_every=4) → void
                                                  // two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
    imu.enable_pedometer(true);                   // Enable pedometer, (enabled=true) → void
                                                  // CTRL8.Pedo_EN; 0→1 resets the count
    uint32_t steps = imu.step_count();            // Read step count, () → uint32_t
                                                  // 24-bit count
    imu.reset_step_count();                       // Clear step count, () → void
                                                  // CTRL9 RESET_PEDOMETER
    imu.enable_pedometer(false);                  // Disable pedometer, (enabled=true) → void
                                                  // clears CTRL8.Pedo_EN
    imu.set_accel_offset(0.0f, 0.0f, 0.0f);       // Set accel offset, (x, y, z) m/s² → void
                                                  // signed 4.12 g via CTRL9
    imu.set_gyro_offset(0.0f, 0.0f, 0.0f);        // Set gyro offset, (x, y, z) rad/s → void
                                                  // signed 11.5 dps via CTRL9
    uint8_t cod_status;
    bool cal_ok = imu.calibrate_gyro(&cod_status); // Calibrate gyroscope, (uint8_t*) → bool
                                                  // calibration-on-demand, ~1.5 s, keep still
    uint16_t gain_x, gain_y, gain_z;
    imu.get_gyro_gains(gain_x, gain_y, gain_z);   // Read gyro gains, (uint16_t&, uint16_t&, uint16_t&) → void
                                                  // raw 16-bit gains from dVX…dVZ
    imu.set_gyro_gains(gain_x, gain_y, gain_z);   // Restore gyro gains, (x, y, z) → void
                                                  // CTRL9 APPLY_GYRO_GAINS
    float st[3];
    bool accel_ok = imu.self_test_accel(st);      // Run accel self-test, (float[3]) → bool m/s²
                                                  // passes when every axis exceeds 200 mg
    bool gyro_ok = imu.self_test_gyro(st);        // Run gyro self-test, (float[3]) → bool rad/s
                                                  // passes when every axis exceeds 300 dps
    uint8_t revision, firmware[3], usid[6];
    imu.device_info(revision, firmware, usid);    // Read device info, (uint8_t&, uint8_t[3], uint8_t[6]) → void
                                                  // CTRL9 COPY_USID: revision, firmware, usid
    imu.set_pullups(true, true, true, true);      // Control pull-ups, (aux=true, sdx=true, cs=true, i2c=true) → void
                                                  // CTRL9 SET_RPU; false disables a group
    imu.set_wake_on_motion(100, 1, 0, 0);         // Arm wake-on-motion, (threshold_mg, interrupt_pin=1, initial_level=0, blanking_samples=0) → void
                                                  // accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
    imu.set_wake_on_motion(0);                    // Leave wake-on-motion, (threshold_mg, interrupt_pin=1, initial_level=0, blanking_samples=0) → void
                                                  // threshold 0 exits WoM mode
    imu.set_sensors(true, true);                  // Re-enable sensors, (accel=true, gyro=true, gyro_snooze=false) → void
                                                  // restores normal operation
    imu.set_interrupt_pins(2, 2);                 // Route interrupts, (fifo_pin=2, activity_pin=2) → void
                                                  // CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
    imu.enable_interrupt(QMI8658AFull::SOURCE_DATA_READY); // Enable interrupt source, (source) → void
                                                  // clears DRDY_DIS, enables INT2
    uint16_t status = imu.poll_interrupt();       // Poll interrupt status, () → uint16_t
                                                  // STATUS0, STATUS1 and FIFO_STATUS as SOURCE_* bits
    imu.disable_interrupt(QMI8658AFull::SOURCE_DATA_READY); // Disable interrupt source, (source) → void
                                                  // sets DRDY_DIS, releases INT2
    imu.set_sync_sample(true);                    // Enable SyncSample, (enabled) → void
                                                  // locks each sample; reads wait for Avail and Locked
    imu.set_sync_sample(false);                   // Disable SyncSample, (enabled) → void
                                                  // restores the AHB clock gating
    imu.set_power_down(true);                     // Power down, (down=true) → void
                                                  // CTRL1.SensorDisable after disabling the sensors
    imu.set_power_down(false);                    // Wake, (down=true) → void
                                                  // clears SensorDisable
    imu.set_sensors(true, true);                  // Re-enable sensors, (accel=true, gyro=true, gyro_snooze=false) → void
                                                  // needs 150 ms gyroscope turn-on
    vTaskDelay(pdMS_TO_TICKS(200));
    imu.reset();                                  // Soft reset, () → void
                                                  // RESET register, waits 15 ms
    printf("accel %.2f %.2f %.2f gyro %.2f %.2f %.2f temp %.1f\n", ax, ay, az, gx, gy, gz, t);
    (void)rax; (void)ray; (void)raz; (void)rgx; (void)rgy; (void)rgz; (void)a; (void)g; (void)ts; (void)ready;
    (void)fifo_flags; (void)fifo_bytes; (void)got; (void)fifo; (void)taps; (void)axis; (void)negative;
    (void)steps; (void)cod_status; (void)cal_ok; (void)gain_x; (void)gain_y; (void)gain_z; (void)st;
    (void)accel_ok; (void)gyro_ok; (void)revision; (void)firmware; (void)usid; (void)status;
    printf("Complete example done\n");
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
