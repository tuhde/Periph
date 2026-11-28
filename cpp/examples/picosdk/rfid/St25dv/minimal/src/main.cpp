#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "St25dv.h"

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}
static void pause_ms(unsigned ms) { sleep_ms(ms); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK user_conn(i2c0, 0x53, nullptr, nullptr, 2);               // Create user-area connection, (i2c, addr=0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionPicoSDK system_conn(i2c0, 0x57, nullptr, nullptr, 2);             // Create system-area connection, (i2c, addr=0x57, int=null, en=null, reg_bytes=2)
    ST25DVMinimal tag(user_conn, system_conn);                                    // Create ST25DV driver, (user, system, gpo_active_high=false)
    tag.init();                                                         // Read MEM_SIZE, BLK_SIZE, IC_REF, () → bool
    static const uint8_t text[] = {'H', 'e', 'l', 'l', 'o', ' ', 'N', 'F', 'C'};
    tag.write(0x0100, text, sizeof(text));                              // Write user memory, (address, data, length) → bool
    while (true) {
        uint8_t buf[9];
        tag.read(0x0100, buf, sizeof(buf));                                 // Read user memory, (address, buf, length) → bool
        print_hex("memory ", buf, sizeof(buf));
        pause_ms(1000);
    }
    return 0;
}
