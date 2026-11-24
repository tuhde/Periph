#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Hts221.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define HTS221_ADDR 0x5F

static void on_drdy(uint8_t status) {
    printf("DRDY status 0x%02X\n", (unsigned)(status));
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = HTS221_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTS221Full sensor(connection);                                  // Create HTS221 driver, (connection) → void
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
                                                                    // decodes the per-device two-point coefficients
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};
    printf("who am i 0x%02X\n", (unsigned)(sensor.who_am_i()));     // Read WHO_AM_I, () → uint8_t (0xBC)
                                                                    // identifies the chip on the bus
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
                                                                    // one-shot conversion, converted with the calibration
    printf("temperature %.2f C humidity %.1f %%rH\n", (double)(t), (double)(rh));
    printf("humidity %.1f\n", (double)(sensor.read_humidity()));    // Read humidity only, () → float %rH
                                                                    // one-shot conversion, clamped to 0–100 (NAN on error)
    printf("temperature %.2f\n", (double)(sensor.read_temperature()));// Read temperature only, () → float °C
                                                                    // one-shot conversion (NAN on error)
    ok = sensor.read_raw_humidity(raw);                             // Read raw humidity, (raw) → bool
                                                                    // signed 16-bit ADC output
    printf("raw humidity %ld\n", (long)(raw));
    ok = sensor.read_raw_temperature(raw);                          // Read raw temperature, (raw) → bool
                                                                    // signed 16-bit ADC output
    printf("raw temperature %ld\n", (long)(raw));
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
                                                                    // decoded two-point coefficients
    printf("H0_rH %.1f H1_rH %.1f T0 %.2f C T1 %.2f C\n", (double)(cal.h0_rh), (double)(cal.h1_rh), (double)(cal.t0_degc), (double)(cal.t1_degc));
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // Magnus formula over one measurement (NAN on error)
    printf("status 0x%02X\n", (unsigned)(sensor.status()));         // Read STATUS_REG, () → uint8_t
                                                                    // STATUS_H_DA = 0x02, STATUS_T_DA = 0x01
    printf("data ready %lu\n", (unsigned long)(sensor.data_ready()));// Check data available, () → bool
                                                                    // both H_DA and T_DA set

    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // continuous sampling at 1 Hz
    ok = sensor.get_output_data_rate(hz);                           // Get output data rate, (hz) → bool
                                                                    // decodes CTRL_REG1.ODR
    printf("rate %.1f Hz\n", (double)(hz));
    vTaskDelay(pdMS_TO_TICKS(1200));
    ok = sensor.read_continuous(t, rh);                             // Read latest sample, (temperature_c, humidity_pct) → bool
                                                                    // no conversion is started
    printf("continuous %.2f C %.1f %%rH\n", (double)(t), (double)(rh));
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool
                                                                    // back to one-shot mode

    ok = sensor.set_averaging(32, 64);                              // Set averaging, (temperature_samples 2–256, humidity_samples 4–512) → bool
                                                                    // more samples lower the noise and lengthen the conversion
    ok = sensor.get_averaging(ts, hs);                              // Get averaging, (temperature_samples, humidity_samples) → bool
                                                                    // decodes AV_CONF
    printf("averaging %lu %lu\n", (unsigned long)(ts), (unsigned long)(hs));
    ok = sensor.set_averaging(16, 32);
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
                                                                    // keeps LSB/MSB of one sample consistent

    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
                                                                    // drives off condensation; do not measure while on
    printf("heater on %lu\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
                                                                    // reads CTRL_REG2.HEATER
    ok = sensor.set_heater(false);

    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
                                                                    // routes data-ready to the DRDY pin
    printf("poll 0x%02X\n", (unsigned)(sensor.poll_interrupt()));   // Poll interrupt status, () → uint8_t
                                                                    // STATUS_REG; flags clear when the data is read
    ok = sensor.configure_drdy(false);

    ok = sensor.power_down();                                       // Power down, () → bool
                                                                    // clears CTRL_REG1.PD
    ok = sensor.power_up();                                         // Power up, () → bool
                                                                    // sets CTRL_REG1.PD
    ok = sensor.on_interrupt(on_drdy);                              // Subscribe to data-ready, (callback, intPin=nullptr) → bool
                                                                    // needs a DRDY pin on the connection
    sensor.off_interrupt();                                         // Unsubscribe, () → void
                                                                    // disables the DRDY output
    ok = sensor.reboot();                                           // Reload trimming, () → bool
                                                                    // sets BOOT, waits, re-reads the calibration
    printf("done ok=%lu\n", (unsigned long)(ok));
}
