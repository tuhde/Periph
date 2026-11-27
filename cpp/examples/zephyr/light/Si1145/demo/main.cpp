#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si1145.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SI1145_ADDR 0x60

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

static volatile uint8_t g_events = 0;
static volatile uint8_t g_last = 0;
static void on_event(uint8_t status) { g_events = (uint8_t)(g_events + 1); g_last = status; }

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SI1145_ADDR);
    Si1145Full sensor(connection);                                 // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool

    // --- Identity and calibration ---
    // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
    // factory calibration bytes.
    uint8_t part = 0, rev = 0, seq = 0;
    sensor.read_id(part, rev, seq);                                // Identity registers, (part_id&, rev_id&, seq_id&) → bool
    printk("PART_ID 0x%02X REV_ID 0x%02X SEQ_ID 0x%02X\n", (unsigned)part, (unsigned)rev, (unsigned)seq);
    uint8_t cal[12];
    sensor.read_calibration(cal);                                  // GET_CAL, (cal[12]) → bool
    printk("calibration");
    for (int i = 0; i < 12; i++) printk(" %02X", (unsigned)cal[i]);
    printk("\n");
    uint8_t counter = 0, err = 0;
    sensor.read_response(counter, err);                            // RESPONSE, (counter&, error&) → bool
    printk("counter %u error 0x%02X\n", (unsigned)counter, (unsigned)err);

    // --- Proximity, UV index and light for 10 s ---
    // Proximity counts rise as a hand approaches the LED/sensor window.
    Si1145Reading r;
    for (int i = 0; i < 10; i++) {
        sensor.read(r);                                            // Forced PS + ALS, (reading&) → bool
        printk("%2d s prox %5u UV %u.%02u vis %5u ir %5u\n", i, (unsigned)r.proximity,
              uv_whole(r.uv_index), uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared);
        k_msleep(1000);
    }

    // --- LED current and photodiode ---
    // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
    // photodiode a fraction of the large one.
    printk("Hold a hand above the sensor\n");
    k_msleep(3000);
    const uint8_t levels[4] = {1, 4, 8, 15};
    uint16_t v = 0;
    for (int i = 0; i < 4; i++) {
        sensor.set_led_current(1, levels[i]);                      // LED drive, (led, level) → bool
        sensor.read_proximity(v);                                  // Forced PS, (value&, channel=1) → bool
        printk("LED level %2u -> %u\n", (unsigned)levels[i], (unsigned)v);
    }
    sensor.set_led_current(1, 3);
    for (int large = 0; large < 2; large++) {
        sensor.set_ps_photodiode(1, large != 0);                   // PS photodiode, (channel, large) → bool
        sensor.read_proximity(v);
        printk("%s photodiode -> %u\n", large ? "large" : "small", (unsigned)v);
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
        printk("gain %u vis %u ir %u\n", (unsigned)gain, (unsigned)v, (unsigned)ir);
    }
    sensor.set_gain();
    sensor.set_high_range(true, true, false);                      // Signal range, (visible, infrared, proximity) → bool
    float uv = 0.0f;
    sensor.read_visible(v);
    sensor.read_uv_index(uv);                                      // Forced ALS, (value&) → bool
    printk("high range vis %u UV %u.%02u\n", (unsigned)v, uv_whole(uv), uv_frac(uv));
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
            printk("output %s (prox %u)\n", near ? "ON" : "OFF", (unsigned)raw.ps1);
        }
        k_msleep(100);
    }
    sensor.pause();                                                // PSALS_PAUSE, () → bool
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    printk("%u interrupt events, last 0x%02X\n", (unsigned)g_events, (unsigned)g_last);
    sensor.disable_interrupt(Si1145Full::SOURCE_PS1);              // IRQ_ENABLE clear, (source) → bool
    sensor.poll_interrupt();                                       // IRQ_STATUS, () → uint8_t sources

    // --- Auxiliary channels ---
    uint16_t vdd = 0;
    sensor.read_temperature_raw(v);                                // AUX temperature, (value&) → bool
    sensor.read_vdd_raw(vdd);                                      // AUX VDD, (value&) → bool
    printk("temperature raw %u VDD raw %u\n", (unsigned)v, (unsigned)vdd);
    sensor.force(false, true);                                     // ALS_FORCE without waiting, (proximity, als) → bool
    k_msleep(50);
    sensor.read_raw(raw);
    printk("raw vis %u ir %u aux %u\n", (unsigned)raw.visible, (unsigned)raw.infrared, (unsigned)raw.aux);

    // --- Address and reset ---
    sensor.set_i2c_address(0x60);                                  // I2C_ADDR + BUSADDR, (address) → bool
    sensor.reset();                                                // RESET + HW_KEY + defaults, () → bool
    sensor.read(r);
    printk("after reset prox %u vis %u\n", (unsigned)r.proximity, (unsigned)r.visible);
    return 0;
}
