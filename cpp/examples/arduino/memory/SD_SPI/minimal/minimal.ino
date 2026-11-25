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
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define PF out
#define DELAY_MS(ms) delay(ms)
static uint32_t now_ms() { return millis(); }

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}

static void app(SPIExtensions& spi) {
    SDSPIMinimal::Options opt;                                                // Driver options, (speed_hz=12000000, now_ms=nullptr, crc=false)
    opt.now_ms = now_ms;
    SDSPIMinimal sd(spi, opt);                                                // Create SD driver and init the card, (spi, options) → SDSPIMinimal

    while (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d), retrying\n", static_cast<int>(sd.status()));
        DELAY_MS(2000);
        sd.begin();                                                           // Re-run card init, () → BlockError
    }

    PF("capacity: %lu blocks = %lu MiB\n",
       static_cast<unsigned long>(sd.block_count()),                          // Capacity in 512-byte blocks, () → uint32 blocks
       static_cast<unsigned long>(sd.block_count() / 2048));
    static uint8_t block[512];
    while (true) {
        BlockError e = sd.read_block(0, block);                               // Read one 512-byte block, (block, buf) → BlockError
        PF("block 0 (%d): ", static_cast<int>(e));
        print_hex(block, 16);
        PF("boot signature: %02X%02X\n", block[510], block[511]);
        DELAY_MS(2000);
    }
}

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    SPISettings settings(400000, MSBFIRST, SPI_MODE0);                        // SD init needs <= 400 kHz; the driver raises it afterwards
    SPIConnection connection(SPI, TEST_CS_PIN, settings);                     // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
    app(connection);
}

void loop() {
}
