#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HDC1008.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    HDC1008Full sensor(connection);                                     // Create HDC1008 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;

    uint64_t serial = 0; uint16_t cfg = 0; uint8_t trig = 0x00;
    sensor.init();                                                      // Write default configuration, () → void
                                                                        // 0x1000: 14-bit T and RH, sequence mode, heater off
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));
    cfg = sensor.read_config();                                         // Read the configuration register, () → uint16_t
    printf("config 0x%04X\n", (unsigned)(cfg));
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // sequence mode, polls the NACKed read header (timeout 50 ms)
    printf("%.2f C, %.1f %%RH\n", t, rh);
    printf("temperature %.2f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure), single mode
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure), single mode
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit words, bits 1:0 zero
    printf("raw T %u raw RH %u\n", (unsigned)(raw_t), (unsigned)(raw_rh));

    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
                                                                        // T 14/11 bit, RH 14/11/8 bit; false for other values
    printf("conversion time %.2f ms\n", sensor.conversion_time() * 1000.0f);
    // sensor.conversion_time() → float s, typical T + RH time
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // runs only during measurements; readings drift while on
    printf("heater on %u\n", (unsigned)(sensor.is_heater_on()));
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // sets RST, waits 15 ms, rewrites the stored configuration

    auto on_ready = [](float tt, float hh) { printf("data ready %.2f C %.1f %%RH\n", tt, hh); };
    sensor.on_interrupt(on_ready);                                      // Subscribe to DRDYn, (callback, intPin) → void
                                                                        // needs a pin on connection.intPin(); otherwise poll_interrupt()
    connection.write(&trig, 1);  // trigger one sequence measurement
    sleep_ms(100);
    printf("data ready %u\n", (unsigned)(sensor.poll_interrupt()));
    // sensor.poll_interrupt() → bool, read header ACKed = ready
    sensor.off_interrupt();                                             // Unsubscribe, () → void
    printf("done\n");
    while (true) sleep_ms(1000);
    return 0;
}
