#include <SPI.h>
#include <Periph.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

// Formatted output over Serial (AVR has no printf() to Serial).
static void out(const char* fmt, ...) {
    char buf[128];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define PF out
#define DELAY_MS(ms) delay(ms)
static uint32_t now_ms() { return millis(); }
// All writes and erases stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
static const uint32_t SCRATCH = 0xFF0000;

static void app(Connection& connection, MX25L12845EOptions& opt) {
    static MX25L12845EMinimal flash(connection, opt);                                 // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EMinimal
    if (flash.status() != BlockError::Ok) {                                    // Result of init, () → BlockError
        PF("MX25L12845E not found (error %d)\n", static_cast<int>(flash.status()));
        while (true) DELAY_MS(1000);
    }
    uint8_t id[3];
    flash.jedec_id(id);                                                        // Read JEDEC ID, (out[3]) → BlockError
    PF("JEDEC ID %02X %02X %02X, %lu MiB\n", id[0], id[1], id[2], static_cast<unsigned long>(flash.size() / (1024 * 1024)));  // Capacity in bytes, () → uint32 bytes

    for (uint8_t counter = 0; counter < 10; counter++) {
        flash.write_bytes(SCRATCH, &counter, 1);                               // Write bytes (erases the sector when needed), (address, data, len) → BlockError
        uint8_t value = 0;
        flash.read_bytes(SCRATCH, &value, 1);                                  // Read bytes, (address, buf, len) → BlockError
        PF("wrote 0x%02X, read 0x%02X\n", counter, value);
        DELAY_MS(500);
    }
    flash.erase_sector(SCRATCH);                                               // Erase one 4 KiB sector, (address) → BlockError
    PF("done, scratch sector erased\n");
    while (true) DELAY_MS(1000);
}

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
    SPIConnection connection(SPI, TEST_CS_PIN, settings);                      // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay;
    app(connection, opt);
}

void loop() {
}
