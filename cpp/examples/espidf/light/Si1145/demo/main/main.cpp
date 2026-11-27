#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si1145.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define SI1145_ADDR 0x60

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

static volatile uint8_t g_events = 0;
static volatile uint8_t g_last = 0;
static void on_event(uint8_t status) { g_events = (uint8_t)(g_events + 1); g_last = status; }

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
    dev_cfg.device_address = SI1145_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1145Full sensor(connection);                                 // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool

    // --- Identity and calibration ---
    // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
    // factory calibration bytes.
    uint8_t part = 0, rev = 0, seq = 0;
    sensor.read_id(part, rev, seq);                                // Identity registers, (part_id&, rev_id&, seq_id&) → bool
    printf("PART_ID 0x%02X REV_ID 0x%02X SEQ_ID 0x%02X\n", (unsigned)part, (unsigned)rev, (unsigned)seq);
    uint8_t cal[12];
    sensor.read_calibration(cal);                                  // GET_CAL, (cal[12]) → bool
    printf("calibration");
    for (int i = 0; i < 12; i++) printf(" %02X", (unsigned)cal[i]);
    printf("\n");
    uint8_t counter = 0, err = 0;
    sensor.read_response(counter, err);                            // RESPONSE, (counter&, error&) → bool
    printf("counter %u error 0x%02X\n", (unsigned)counter, (unsigned)err);

    // --- Proximity, UV index and light for 10 s ---
    // Proximity counts rise as a hand approaches the LED/sensor window.
    Si1145Reading r;
    for (int i = 0; i < 10; i++) {
        sensor.read(r);                                            // Forced PS + ALS, (reading&) → bool
        printf("%2d s prox %5u UV %u.%02u vis %5u ir %5u\n", i, (unsigned)r.proximity,
              uv_whole(r.uv_index), uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- LED current and photodiode ---
    // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
    // photodiode a fraction of the large one.
    printf("Hold a hand above the sensor\n");
    vTaskDelay(pdMS_TO_TICKS(3000));
    const uint8_t levels[4] = {1, 4, 8, 15};
    uint16_t v = 0;
    for (int i = 0; i < 4; i++) {
        sensor.set_led_current(1, levels[i]);                      // LED drive, (led, level) → bool
        sensor.read_proximity(v);                                  // Forced PS, (value&, channel=1) → bool
        printf("LED level %2u -> %u\n", (unsigned)levels[i], (unsigned)v);
    }
    sensor.set_led_current(1, 3);
    for (int large = 0; large < 2; large++) {
        sensor.set_ps_photodiode(1, large != 0);                   // PS photodiode, (channel, large) → bool
        sensor.read_proximity(v);
        printf("%s photodiode -> %u\n", large ? "large" : "small", (unsigned)v);
    }
    sensor.set_ps_led(1, 0x01);                                    // PS LED select, (channel, leds) → bool

    // --- Gain and high range ---
    // Each gain step doubles the integration time; the high range divides the sensitivity by
    // 14.5 for direct sunlight.
    uint16_t ir = 0;
    for (uint8_t gain = 0; gain <= 4; gain += 2) {
        sensor.set_gain(gain, gain, 0);                            // ADC gains, (visible, infrared, proximity) → bool
        sensor.read_visible(v);                                    // Forced ALS, (value&) → bool
        sensor.read_infrared(ir);                                  // Forced ALS, (value&) → bool
        printf("gain %u vis %u ir %u\n", (unsigned)gain, (unsigned)v, (unsigned)ir);
    }
    sensor.set_gain();
    sensor.set_high_range(true, true, false);                      // Signal range, (visible, infrared, proximity) → bool
    float uv = 0.0f;
    sensor.read_visible(v);
    sensor.read_uv_index(uv);                                      // Forced ALS, (value&) → bool
    printf("high range vis %u UV %u.%02u\n", (unsigned)v, uv_whole(uv), uv_frac(uv));
    sensor.set_high_range(false, false, false);
    sensor.set_alignment();                                        // Result alignment, (...) → bool
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    sensor.set_uv_coefficients(ucoef);                             // UCOEF0..3, (ucoef[4]) → bool

    // --- Autonomous mode with interrupt events and a proximity switch ---
    // PS + ALS every 100 ms; each finished PS1 measurement raises INT (when wired).
    const uint16_t threshold = 2000;
    sensor.set_channels(true, true, true, true);                   // CHLIST, (uv, visible, infrared, ps1, ...) → bool
    sensor.enable_interrupt(Si1145Full::SOURCE_PS1);               // INT_CFG + IRQ_ENABLE, (source) → bool
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    sensor.start_autonomous(0.1f);                                 // Autonomous mode, (interval_s, proximity, als) → bool
    bool near = false;
    Si1145Raw raw;
    for (int i = 0; i < 50; i++) {
        sensor.read_raw(raw);                                      // Data registers, (raw&) → bool
        if ((raw.ps1 > threshold) != near) {
            near = raw.ps1 > threshold;
            printf("output %s (prox %u)\n", near ? "ON" : "OFF", (unsigned)raw.ps1);
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    sensor.pause();                                                // PSALS_PAUSE, () → bool
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    printf("%u interrupt events, last 0x%02X\n", (unsigned)g_events, (unsigned)g_last);
    sensor.disable_interrupt(Si1145Full::SOURCE_PS1);              // IRQ_ENABLE clear, (source) → bool
    sensor.poll_interrupt();                                       // IRQ_STATUS, () → uint8_t sources

    // --- Auxiliary channels ---
    uint16_t vdd = 0;
    sensor.read_temperature_raw(v);                                // AUX temperature, (value&) → bool
    sensor.read_vdd_raw(vdd);                                      // AUX VDD, (value&) → bool
    printf("temperature raw %u VDD raw %u\n", (unsigned)v, (unsigned)vdd);
    sensor.force(false, true);                                     // ALS_FORCE without waiting, (proximity, als) → bool
    vTaskDelay(pdMS_TO_TICKS(50));
    sensor.read_raw(raw);
    printf("raw vis %u ir %u aux %u\n", (unsigned)raw.visible, (unsigned)raw.infrared, (unsigned)raw.aux);

    // --- Address and reset ---
    sensor.set_i2c_address(0x60);                                  // I2C_ADDR + BUSADDR, (address) → bool
    sensor.reset();                                                // RESET + HW_KEY + defaults, () → bool
    sensor.read(r);
    printf("after reset prox %u vis %u\n", (unsigned)r.proximity, (unsigned)r.visible);
}
