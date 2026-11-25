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
    float t = 0, rh = 0, hz = 0;
    int16_t raw = 0;
    uint16_t ts = 0, hs = 0;
    HTS221Calibration cal = {};

    // --- Identify the chip and show the factory calibration ---
    // Every HTS221 stores its own two-point calibration; init() read it and
    // every conversion uses it.
    printf("who am i 0x%02X\n", (unsigned)(sensor.who_am_i()));     // Read WHO_AM_I, () → uint8_t (0xBC)
    sensor.read_calibration(cal);                                   // Read calibration, (cal) → void
    printf("H0_rH %.1f H1_rH %.1f T0 %.2f C T1 %.2f C\n", (double)(cal.h0_rh), (double)(cal.h1_rh), (double)(cal.t0_degc), (double)(cal.t1_degc));

    // --- One-shot hygrometer: 10 readings, 2 s apart ---
    // Each read() starts a conversion; the dew point shows what the pair
    // means for condensation risk.
    for (int i = 0; i < 10; i++) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    printf("    %.2f C  %.1f %%rH  dew %.2f C\n", (double)(t), (double)(rh), (double)(sensor.dew_point()));// Compute dew point, () → float °C
        ok = sensor.read_raw_humidity(raw);                         // Read raw humidity, (raw) → bool
    printf("    raw %ld %ld\n", (long)(raw), (long)(sensor.read_humidity()));// Read humidity only, () → float %rH
    printf("    %.2f C\n", (double)(sensor.read_temperature()));    // Read temperature only, () → float °C
        ok = sensor.read_raw_temperature(raw);                      // Read raw temperature, (raw) → bool
    vTaskDelay(pdMS_TO_TICKS(2000));
    }

    // --- Continuous sampling at 1 Hz and 12.5 Hz ---
    // data_ready() paces the loop; the sample count per second follows the rate.
    ok = sensor.set_block_data_update(true);                        // Set block data update, (enabled) → bool
    for (int r = 0; r < 2; r++) {
        ok = sensor.set_output_data_rate(r == 0 ? 1.0f : 12.5f);    // Set output data rate, (hz 0|1|7|12.5) → bool
        ok = sensor.get_output_data_rate(hz);                       // Get output data rate, (hz) → bool
    printf("    rate %.1f Hz\n", (double)(hz));
        int count = 0;
        for (int ms = 0; ms < 3000; ms += 5) {
            if (sensor.data_ready()) {                              // Check data available, () → bool
                ok = sensor.read_continuous(t, rh);                 // Read latest sample, (temperature_c, humidity_pct) → bool
                count++;
            }
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    printf("    %.1f samples/s\n", (double)(count / 3.0f));
    }
    ok = sensor.set_output_data_rate(0);                            // Set output data rate, (hz 0|1|7|12.5) → bool

    // --- Noise at lowest and highest averaging ---
    // Ten one-shot samples each; the spread of the raw humidity shrinks as
    // the averaging grows.
    for (int a = 0; a < 2; a++) {
        ok = sensor.set_averaging(a == 0 ? 2 : 256, a == 0 ? 4 : 512);// Set averaging, (temperature_samples, humidity_samples) → bool
        ok = sensor.get_averaging(ts, hs);                          // Get averaging, (temperature_samples, humidity_samples) → bool
    printf("    averaging %lu %lu\n", (unsigned long)(ts), (unsigned long)(hs));
        int lo = 32767, hi = -32768;
        for (int i = 0; i < 10; i++) {
            ok = sensor.read_raw_humidity(raw);                     // Read raw humidity, (raw) → bool
            if (raw < lo) lo = raw;
            if (raw > hi) hi = raw;
        }
    printf("    raw humidity spread %ld\n", (long)(hi - lo));
    }
    ok = sensor.set_averaging(16, 32);                              // Set averaging, (temperature_samples, humidity_samples) → bool

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    float t0 = 0, rh0 = 0;
    ok = sensor.read(t0, rh0);                                      // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    ok = sensor.set_heater(true);                                   // Switch the heater, (on) → bool
    printf("heater on %lu\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
    vTaskDelay(pdMS_TO_TICKS(10000));
    ok = sensor.set_heater(false);                                  // Switch the heater, (on) → bool
    ok = sensor.read(t, rh);                                        // Read temperature and humidity, (temperature_c, humidity_pct) → bool
    printf("before %.2f C %.1f %%rH, after %.2f C %.1f %%rH\n", (double)(t0), (double)(rh0), (double)(t), (double)(rh));

    // --- Data-ready notification for 5 s ---
    // Uses the DRDY pin when the connection has one, otherwise polls the status.
    ok = sensor.configure_drdy(true, true, false);                  // Configure DRDY pin, (enabled, active_high=true, open_drain=false) → bool
    ok = sensor.set_output_data_rate(1);                            // Set output data rate, (hz 0|1|7|12.5) → bool
    if (!sensor.on_interrupt(on_drdy)) {                            // Subscribe to data-ready, (callback, intPin=nullptr) → bool
        for (int i = 0; i < 5; i++) {
    printf("        DRDY status 0x%02X\n", (unsigned)(sensor.poll_interrupt()));// Poll interrupt status, () → uint8_t
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
    }
    else {
            vTaskDelay(pdMS_TO_TICKS(5000));
        sensor.off_interrupt();                                     // Unsubscribe, () → void
    }
    ok = sensor.configure_drdy(false);
    ok = sensor.set_output_data_rate(0);

    // --- Power cycle and reload ---
    ok = sensor.power_down();                                       // Power down, () → bool
    ok = sensor.power_up();                                         // Power up, () → bool
    ok = sensor.reboot();                                           // Reload trimming, () → bool
    ok = sensor.read(t, rh);
    printf("done %.2f C ok=%lu\n", (double)(t), (unsigned long)(ok));
}
