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

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}
// All writes and erases stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
static const uint32_t SCRATCH = 0xFF0000;

static void app(Connection& connection, MX25L12845EOptions& opt) {
    static MX25L12845EFull flash(connection, opt);                                    // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EFull
    if (flash.status() != BlockError::Ok) {
        PF("MX25L12845E not found (error %d)\n", static_cast<int>(flash.status()));
        while (true) DELAY_MS(1000);
    }

    uint8_t id[3], res = 0, man = 0, dev = 0;
    flash.jedec_id(id);                                                        // Read JEDEC ID, (out[3]) → BlockError
    flash.electronic_id(&res);                                                 // Read the old-style electronic ID (RES), (out) → BlockError
    flash.device_ids(&man, &dev);                                              // Read manufacturer + device ID (REMS), (&man, &dev) → BlockError
    PF("JEDEC %02X %02X %02X, RES %02X, REMS %02X %02X\n", id[0], id[1], id[2], res, man, dev);

    uint8_t sr = 0, bp = 0, scur = 0;
    flash.read_status(&sr);                                                    // Read the status register, (out) → BlockError
    flash.protection(&bp);                                                     // Read BP3:0, (out) → BlockError
    flash.read_security(&scur);                                                // Read the security register, (out) → BlockError
    PF("status 0x%02X, protection BP=%u, security 0x%02X\n", sr, bp, scur);

    uint8_t esn[16];
    flash.serial_number(esn);                                                  // Read the factory serial number, (out[16]) → BlockError
                                                                               // 128-bit ESN from the secured OTP area
    PF("ESN: "); print_hex(esn, 16);
    uint8_t sfdp[16];
    flash.read_sfdp(0, sfdp, 16);                                              // Read the SFDP table, (address, buf, len) → BlockError
                                                                               // starts with the signature "SFDP"
    PF("SFDP: "); print_hex(sfdp, 16);

    flash.erase_sector(SCRATCH);                                               // Erase one 4 KiB sector, (address) → BlockError
                                                                               // takes 60 ms typ, 300 ms max
    uint8_t page[256], back[256];
    for (int i = 0; i < 256; i++) page[i] = static_cast<uint8_t>(i);
    flash.page_program(SCRATCH, page, 256);                                    // Program one page (must not cross a page boundary), (address, data, len) → BlockError
                                                                               // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
    flash.fast_read(SCRATCH, back, 256);                                       // FAST_READ, (address, buf, len) → BlockError
                                                                               // same data as read_bytes, for clocks above 50 MHz
    PF("page program + fast_read: %s\n", memcmp(page, back, 256) == 0 ? "ok" : "MISMATCH");

    flash.erase_block_32k(SCRATCH);                                            // Erase a 32 KiB block, (address) → BlockError
                                                                               // 0.5 s typ
    flash.erase_block_64k(SCRATCH);                                            // Erase a 64 KiB block, (address) → BlockError
                                                                               // 0.7 s typ
    uint8_t blank[16];
    flash.read_bytes(SCRATCH, blank, 16);                                      // Read bytes, (address, buf, len) → BlockError
    PF("after block erase: "); print_hex(blank, 16);
    // chip_erase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

    uint8_t text[] = "erase-aware write";
    flash.write_bytes(SCRATCH + 100, text, sizeof(text));                      // Write bytes, erasing the sector only when needed, (address, data, len) → BlockError
    flash.write_bytes(SCRATCH + 100, text, sizeof(text));                      // the same bytes again: nothing is programmed or erased
    flash.read_bytes(SCRATCH + 100, back, sizeof(text));
    PF("text: %s\n", back);

    flash.deep_power_down();                                                   // Enter deep power-down, () → BlockError
                                                                               // only RDP / RES are accepted afterwards
    flash.release_deep_power_down();                                           // Leave deep power-down (RDP, waits tRES2), () → BlockError
    PF("busy: %d\n", flash.is_busy());                                  // WIP bit, () → bool
    flash.wait_ready(1000);                                                    // Wait for WIP = 0, (timeout_ms) → BlockError

    BlockInfo info = flash.info();                                             // Geometry and capabilities, () → BlockInfo
                                                                               // 512-byte blocks, 4 KiB erase unit, needs_erase
    PF("block device: %lu blocks of %lu bytes, erase unit %lu, needs_erase=%d\n",
           static_cast<unsigned long>(info.block_count), static_cast<unsigned long>(info.block_size),
           static_cast<unsigned long>(info.erase_size), info.needs_erase);
    // set_protection(), write_status(), program_otp() and lock_otp() change non-volatile state; see the spec before using them.

    flash.erase_sector(SCRATCH);
    PF("done\n");
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
