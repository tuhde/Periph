#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x24);
PN532Minimal reader(connection);                                  // Create PN532 driver, (connection) → void

static void periph_printf(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) periph_printf("%02X", data[i]);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
}

void loop() {
    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                        // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    periph_printf("UID ");
    if (len) print_hex(uid, len); else periph_printf("none");
    periph_printf("\n");
    delay(1000);
}
