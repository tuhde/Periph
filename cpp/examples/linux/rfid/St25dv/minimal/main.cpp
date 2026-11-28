#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "St25dv.h"

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}
static void pause_ms(unsigned ms) { usleep(ms * 1000); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;
    I2CConnectionLinux user_conn(bus, 0x53, nullptr, nullptr, 2);                  // Create user-area connection, (bus, addr=0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionLinux system_conn(bus, 0x57, nullptr, nullptr, 2);                // Create system-area connection, (bus, addr=0x57, int=null, en=null, reg_bytes=2)
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
