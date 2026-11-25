#include <string.h>
#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "MX25L12845E.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) sleep_ms(ms)
static uint32_t now_ms() { return to_ms_since_boot(get_absolute_time()); }
static void delay_ms(uint32_t ms) { sleep_ms(ms); }

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

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 8000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                             // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
    return 0;
}
