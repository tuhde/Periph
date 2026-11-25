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

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}
// All writes and erases stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
static const uint32_t SCRATCH = 0xFF0000;

static void app(Connection& connection, MX25L12845EOptions& opt) {
    // --- Bring up the chip ---
    // Plain single-I/O SPI: the driver releases deep power-down, checks the JEDEC ID and
    // waits until the chip is idle. The ESN is a factory-programmed unique serial number.
    static MX25L12845EFull flash(connection, opt);                                    // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EFull
    if (flash.status() != BlockError::Ok) {
        PF("MX25L12845E not found (error %d)\n", static_cast<int>(flash.status()));
        while (true) DELAY_MS(1000);
    }
    uint8_t esn[16];
    flash.serial_number(esn);                                                  // Read the factory serial number, (out[16]) → BlockError
    PF("MX25L12845E, %lu MiB, serial ", static_cast<unsigned long>(flash.size() / (1024 * 1024)));
    print_hex(esn, 16);

    // --- Append records to a ring of four sectors ---
    // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
    // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
    // enters the next sector is that sector erased first — one erase per 128 records.
    const uint32_t RING = 0xFFC000, SECTORS = 4, SLOT = 32, SLOTS = SECTORS * 4096 / SLOT;
    for (uint32_t s = 0; s < SECTORS; s++) flash.erase_sector(RING + s * 4096);   // Erase one 4 KiB sector, (address) → BlockError
    uint32_t t0 = now_ms(), erases = 0, slot = 0;
    uint32_t eraseMs = 0;
    for (uint32_t seq = 0; seq < 300; seq++) {
        if (slot % 128 == 0 && seq >= SLOTS) {                                 // wrapped into the next sector: make it blank
            uint32_t e0 = now_ms();
            flash.erase_sector(RING + (slot / 128) * 4096);
            eraseMs += now_ms() - e0;
            erases++;
        }
        uint8_t rec[SLOT];
        memset(rec, 0xA5, sizeof(rec));
        memcpy(rec, &seq, 4);
        uint32_t tick = now_ms();
        memcpy(rec + 4, &tick, 4);
        flash.page_program(RING + slot * SLOT, rec, SLOT);                     // Program one record (inside a page), (address, data, len) → BlockError
        slot = (slot + 1) % SLOTS;
    }
    PF("appended 300 records in %lu ms, %lu sector erases (%lu ms)\n", static_cast<unsigned long>(now_ms() - t0),
           static_cast<unsigned long>(erases), static_cast<unsigned long>(eraseMs));

    // --- Verify by scanning the ring ---
    // The newest 128 * 4 = 512 slots hold the last records; every slot must carry a valid
    // sequence number and the 0xA5 payload.
    uint32_t good = 0, newest = 0;
    for (uint32_t s = 0; s < SLOTS; s++) {
        uint8_t rec[SLOT];
        flash.read_bytes(RING + s * SLOT, rec, SLOT);                          // Read bytes, (address, buf, len) → BlockError
        uint32_t seq;
        memcpy(&seq, rec, 4);
        if (seq != 0xFFFFFFFFu && rec[20] == 0xA5) { good++; if (seq > newest) newest = seq; }
    }
    PF("ring holds %lu valid records, newest sequence number %lu\n", static_cast<unsigned long>(good), static_cast<unsigned long>(newest));

    // --- Leave the chip clean ---
    for (uint32_t s = 0; s < SECTORS; s++) flash.erase_sector(RING + s * 4096);
    PF("ring erased\n");
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
