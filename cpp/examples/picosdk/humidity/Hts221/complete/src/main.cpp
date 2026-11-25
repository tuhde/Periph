#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Hts221.h"

static void on_drdy(uint8_t status) {
    printf("DRDY status 0x%02X\n", (unsigned)(status));
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5F);
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
    sleep_ms(1200);
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
    while (true) { sleep_ms(1000); }
    return 0;
}
