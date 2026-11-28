#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <Periph.h>

I2CConnection user_conn(Wire, 0x53, nullptr, nullptr, 2);                      // Create user-area connection, (wire, addr=0x53, int=null, en=null, reg_bytes=2)
I2CConnection system_conn(Wire, 0x57, nullptr, nullptr, 2);                    // Create system-area connection, (wire, addr=0x57, int=null, en=null, reg_bytes=2)
ST25DVMinimal tag(user_conn, system_conn);                                        // Create ST25DV driver, (user, system, gpo_active_high=false)

static void out(const char* fmt, ...) {
    char text[128];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    Serial.print(text);
}
static void pause_ms(unsigned ms) { delay(ms); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    tag.init();                                                         // Read MEM_SIZE, BLK_SIZE, IC_REF, () → bool
    static const uint8_t text[] = {'H', 'e', 'l', 'l', 'o', ' ', 'N', 'F', 'C'};
    tag.write(0x0100, text, sizeof(text));                              // Write user memory, (address, data, length) → bool
}

void loop() {
    uint8_t buf[9];
    tag.read(0x0100, buf, sizeof(buf));                                 // Read user memory, (address, buf, length) → bool
    print_hex("memory ", buf, sizeof(buf));
    pause_ms(1000);
}
