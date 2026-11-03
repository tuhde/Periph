#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "MX25L12845E.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

#define PF printf
#define DELAY_MS(ms) usleep((ms) * 1000)
static uint32_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint32_t>(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}
static void delay_ms(uint32_t ms) { usleep(ms * 1000); }
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

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);  // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
    return 0;
}
