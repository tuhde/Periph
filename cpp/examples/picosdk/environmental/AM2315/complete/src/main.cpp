#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AM2315.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    sensor.read(t, rh);                                                      // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    printf("%.1f C, %.1f %%RH\n", t, rh);
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    printf("temperature %.1f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    sensor.read_raw(rh_raw, t_raw);                                          // Read raw words, (humidity_raw, temperature_raw) → bool
                                                                             // humidity x10; temperature x10 as sign + magnitude
    printf("raw RH %u raw T %u\n", (unsigned)(rh_raw), (unsigned)(t_raw));
    sensor.read_fresh(t, rh);                                                // Real-time value, (t, rh) → bool
                                                                             // two reads >= 2 s apart; returns the second
    printf("fresh %.1f C, %.1f %%RH\n", t, rh);
    sensor.read_registers(0x08, 7, regs);                                    // Read registers, (start, count, out) → bool
                                                                             // function 0x03, 1-10 registers, CRC verified
    printf("register 0x08 = 0x%02X\n", (unsigned)(regs[0]));
    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    printf("model %u version %u device id 0x%08lX\n", (unsigned)(model), (unsigned)(version), (unsigned long)(device_id));
    sensor.write_user_registers(0x1234, 0xABCD);                             // Write user registers, (user1, user2) → bool
                                                                             // registers 0x10-0x13
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    printf("user registers 0x%02X 0x%02X\n", (unsigned)(user1), (unsigned)(user2));
    sensor.write_registers(0x10, regs, 2);                                   // Write registers, (start, data, len) → bool
                                                                             // only the user registers 0x10-0x13 are allowed
    sensor.wake();                                                           // Wake the sensor, () → void
                                                                             // dummy write, the NACK is ignored
    printf("last error %u\n", (unsigned)(sensor.last_error()));
    // sensor.last_error() → uint8_t, 0x80-0x84 after a sensor error response, else 0
    printf("done\n");
    while (true) sleep_ms(1000);
    return 0;
}
