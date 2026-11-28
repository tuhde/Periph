#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/sys/printk.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include "I2CConnectionZephyr.h"
#include "St25dv.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintk(fmt, ap);
    va_end(ap);
}
static void pause_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr user_conn(i2c_dev, 0x53, nullptr, nullptr, 2);             // Create user-area connection, (dev, addr=0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionZephyr system_conn(i2c_dev, 0x57, nullptr, nullptr, 2);           // Create system-area connection, (dev, addr=0x57, int=null, en=null, reg_bytes=2)
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
