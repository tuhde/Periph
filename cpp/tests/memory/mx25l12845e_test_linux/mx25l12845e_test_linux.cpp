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

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { PF("PASS %s\n", label); passed++; }
    else       { PF("FAIL %s\n", label); failed++; }
}
static void finish() {
    PF("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) DELAY_MS(1000);
}

static void app(Connection& connection, MX25L12845EOptions& opt) {
    static MX25L12845EFull flash(connection, opt);
    check_true(flash.status() == BlockError::Ok, "init (JEDEC C2 20 18)");
    if (flash.status() != BlockError::Ok) finish();

    uint8_t id[3], res = 0, man = 0, dev = 0;
    flash.jedec_id(id);
    check_true(id[0] == 0xC2 && id[1] == 0x20 && id[2] == 0x18, "jedec_id");
    check_true(flash.electronic_id(&res) == BlockError::Ok && res == 0x17, "electronic_id (RES) = 17h");
    check_true(flash.device_ids(&man, &dev) == BlockError::Ok && man == 0xC2 && dev == 0x17, "device_ids (REMS) = C2 17");

    uint8_t sr = 0xFF, bp = 0xFF, scur = 0xFF;
    check_true(flash.read_status(&sr) == BlockError::Ok && !(sr & 0x03), "status: not busy, WEL clear");
    check_true(flash.protection(&bp) == BlockError::Ok, "protection readable");
    PF("INFO status 0x%02X, BP=%u\n", sr, bp);
    check_true(flash.read_security(&scur) == BlockError::Ok, "security register readable");
    PF("INFO security 0x%02X (bit0 = OTP factory locked)\n", scur);

    uint8_t esn[16], esn2[16];
    check_true(flash.serial_number(esn) == BlockError::Ok, "serial number");
    PF("INFO ESN: "); print_hex(esn, 16);
    check_true(flash.serial_number(esn2) == BlockError::Ok && memcmp(esn, esn2, 16) == 0, "ESN stable");
    uint8_t sfdp[8];
    check_true(flash.read_sfdp(0, sfdp, 8) == BlockError::Ok && memcmp(sfdp, "SFDP", 4) == 0, "SFDP signature");

    if (bp != 0) { PF("INFO protection was BP=%u; clearing for the test\n", bp); flash.set_protection(0); }

    // erase / blank check
    static uint8_t buf[4096], back[4096];
    uint32_t t = now_ms();
    check_true(flash.erase_sector(SCRATCH) == BlockError::Ok, "erase_sector");
    PF("INFO sector erase %lu ms\n", static_cast<unsigned long>(now_ms() - t));
    flash.read_bytes(SCRATCH, back, 4096);
    bool blank = true;
    for (int i = 0; i < 4096; i++) if (back[i] != 0xFF) { blank = false; break; }
    check_true(blank, "sector blank (all FF) after erase");

    // page program + readback + the AND property of programming
    for (int i = 0; i < 256; i++) buf[i] = static_cast<uint8_t>(i * 3 + 1);
    t = now_ms();
    check_true(flash.page_program(SCRATCH, buf, 256) == BlockError::Ok, "page_program 256 bytes");
    PF("INFO page program %lu ms\n", static_cast<unsigned long>(now_ms() - t));
    flash.read_bytes(SCRATCH, back, 256);
    check_true(memcmp(buf, back, 256) == 0, "read_bytes matches");
    flash.fast_read(SCRATCH, back, 256);
    check_true(memcmp(buf, back, 256) == 0, "fast_read matches");
    uint8_t zeros[4] = {0x0F, 0xF0, 0x00, 0xFF};
    flash.page_program(SCRATCH, zeros, 4);
    flash.read_bytes(SCRATCH, back, 4);
    check_true(back[0] == (buf[0] & 0x0F) && back[1] == (buf[1] & 0xF0) && back[2] == 0 && back[3] == buf[3], "programming only clears bits (AND)");
    check_true(flash.page_program(SCRATCH + 250, buf, 10) == BlockError::OutOfRange, "page-crossing program refused");

    // erase-aware write
    for (int i = 0; i < 4096; i++) buf[i] = static_cast<uint8_t>(i ^ (i >> 7));
    t = now_ms();
    check_true(flash.write_bytes(SCRATCH + 4096 - 100, buf, 3000) == BlockError::Ok, "write_bytes across two sectors");
    PF("INFO erase-aware write of 3000 bytes %lu ms\n", static_cast<unsigned long>(now_ms() - t));
    flash.read_bytes(SCRATCH + 4096 - 100, back, 3000);
    check_true(memcmp(buf, back, 3000) == 0, "read back across sectors");
    t = now_ms();
    check_true(flash.write_bytes(SCRATCH + 4096 - 100, buf, 3000) == BlockError::Ok, "identical rewrite");
    PF("INFO identical rewrite %lu ms (no program, no erase)\n", static_cast<unsigned long>(now_ms() - t));
    check_true(flash.write_bytes(MX25L12845EMinimal::SIZE - 2, buf, 5) == BlockError::OutOfRange, "write past the end refused");

    // block erases
    t = now_ms();
    check_true(flash.erase_block_32k(SCRATCH) == BlockError::Ok, "erase_block_32k");
    PF("INFO 32 KiB block erase %lu ms\n", static_cast<unsigned long>(now_ms() - t));
    t = now_ms();
    check_true(flash.erase_block_64k(SCRATCH) == BlockError::Ok, "erase_block_64k");
    PF("INFO 64 KiB block erase %lu ms\n", static_cast<unsigned long>(now_ms() - t));
    flash.read_bytes(SCRATCH + 0xF000, back, 16);
    check_true(back[0] == 0xFF && back[15] == 0xFF, "64 KiB block blank");

    // BlockDevice
    check_true(flash.block_size() == 512 && flash.block_count() == 32768 && flash.erase_size() == 4096 && flash.needs_erase(), "BlockDevice geometry");
    uint32_t blk = SCRATCH / 512;
    for (int i = 0; i < 1024; i++) buf[i] = static_cast<uint8_t>(i * 5 + 2);
    check_true(flash.write(blk, buf, 2) == BlockError::Ok, "BlockDevice write 2 blocks");
    check_true(flash.read(blk, back, 2) == BlockError::Ok && memcmp(buf, back, 1024) == 0, "BlockDevice read back");
    uint8_t ones[512]; memset(ones, 0xFF, sizeof(ones));
    check_true(flash.write(blk, ones, 1) == BlockError::NotErased, "0->1 without erase -> NotErased");
    check_true(flash.erase(blk, 8) == BlockError::Ok, "BlockDevice erase (4 KiB)");
    check_true(flash.erase(blk + 1, 8) == BlockError::OutOfRange, "unaligned erase refused");
    check_true(flash.sync() == BlockError::Ok, "sync");

    // protection: top 2 x 64 KiB blocks are protected with BP = 1
    check_true(flash.set_protection(1) == BlockError::Ok, "set_protection(1)");
    uint8_t d = 0x00;
    check_true(flash.page_program(SCRATCH + 0x8000, &d, 1) == BlockError::WriteProtected, "program into protected area -> WriteProtected");
    check_true(flash.erase_sector(SCRATCH + 0x8000) == BlockError::WriteProtected, "erase of protected sector -> WriteProtected");
    check_true(flash.set_protection(0) == BlockError::Ok, "set_protection(0)");
    check_true(flash.page_program(SCRATCH + 0x8000, &d, 1) == BlockError::Ok, "program after unprotect");

    // power
    check_true(flash.deep_power_down() == BlockError::Ok, "deep_power_down");
    DELAY_MS(1);
    check_true(flash.release_deep_power_down() == BlockError::Ok && flash.electronic_id(&res) == BlockError::Ok && res == 0x17, "release_deep_power_down, RES again");

    // leave the scratch area blank
    flash.erase_block_64k(SCRATCH);
    flash.wait_ready(1000);
    finish();
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);  // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = now_ms;
    opt.delay_ms = delay_ms;
    app(connection, opt);
    return 0;
}
