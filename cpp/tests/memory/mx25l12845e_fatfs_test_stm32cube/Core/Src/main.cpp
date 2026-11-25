#include <stdio.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "MX25L12845E.h"
#include "TracingBlockDevice.h"
#include "ff.h"
#include "FatFsDiskIO.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11); CS = PB10 (D5).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}




// ---------------------------------------------------------------------------
// FatFs on top of the MX25L12845E NOR flash driver — console walk-through
//
// Connect a terminal to the ST-LINK virtual COM port (115200 8N1), e.g.
//     picocom -b 115200 /dev/ttyACM0        or        screen /dev/ttyACM0 115200
// and press a key (or the blue button) to run the whole sequence; press 'f' to
// force a fresh FAT format first. Everything the filesystem asks of the chip is
// counted twice per step: block I/O seen by the filesystem (TracingBlockDevice) and the
// flash commands that result (page programs, sector erases — FAT overwrites sectors in
// place, so the driver turns many 512-byte writes into erase-and-reprogram of a 4 KiB sector).
// ---------------------------------------------------------------------------

#define LOG(fmt, ...) printf(fmt "\r\n", ##__VA_ARGS__)

static FATFS fs;
static FIL file;
static DIR dir;
static FILINFO fno;
static uint8_t work[16384];          // f_mkfs working buffer (zero-fills FAT/root in 16 KiB chunks)
static uint8_t chunk[4096];
static uint8_t verify[4096];

static int passed = 0, failed = 0;

extern "C" DWORD get_fattime(void) {   // fixed timestamp: 2026-10-21 12:00:00 (the board has no RTC set)
    return (static_cast<DWORD>(2026 - 1980) << 25) | (10UL << 21) | (21UL << 16) | (12UL << 11);
}

static const char* fr_name(FRESULT r) {
    static const char* const n[] = {
        "FR_OK", "FR_DISK_ERR", "FR_INT_ERR", "FR_NOT_READY", "FR_NO_FILE", "FR_NO_PATH", "FR_INVALID_NAME",
        "FR_DENIED", "FR_EXIST", "FR_INVALID_OBJECT", "FR_WRITE_PROTECTED", "FR_INVALID_DRIVE", "FR_NOT_ENABLED",
        "FR_NO_FILESYSTEM", "FR_MKFS_ABORTED", "FR_TIMEOUT", "FR_LOCKED", "FR_NOT_ENOUGH_CORE",
        "FR_TOO_MANY_OPEN_FILES", "FR_INVALID_PARAMETER"};
    return static_cast<unsigned>(r) < sizeof(n) / sizeof(n[0]) ? n[r] : "FR_?";
}

static const char* block_error_name(BlockError e) {
    static const char* const n[] = {"Ok", "NotReady", "OutOfRange", "NotErased", "WriteProtected", "BadBlock",
                                    "Ecc", "Crc", "Timeout", "Io"};
    return static_cast<unsigned>(e) < sizeof(n) / sizeof(n[0]) ? n[static_cast<unsigned>(e)] : "?";
}

static void check(bool ok, const char* label) {
    if (ok) { passed++; LOG("      PASS  %s", label); }
    else    { failed++; LOG("      FAIL  %s", label); }
}

// ---- block I/O tracing -------------------------------------------------------

static TracingBlockDevice* tracer = nullptr;
static int trace_budget = 0;

static void trace_io(char op, uint32_t block, uint32_t count, BlockError e) {
    if (trace_budget <= 0) return;
    trace_budget--;
    const char* what = op == 'R' ? "read " : op == 'W' ? "write" : op == 'E' ? "erase" : "sync ";
    if (op == 'S') LOG("      io  %s", what);
    else           LOG("      io  %s block %-8lu x%-4lu %s", what, static_cast<unsigned long>(block),
                       static_cast<unsigned long>(count), e == BlockError::Ok ? "ok" : block_error_name(e));
    if (trace_budget == 0) LOG("      io  ... (further block I/O of this step not listed)");
}


// ---- flash command counter (sits between the driver and the SPI connection) --------------

class OpCounter : public Connection {
public:
    explicit OpCounter(Connection& inner) : _c(inner) {}
    uint32_t pageProgram = 0, erase4k = 0, erase32k = 0, erase64k = 0, chipErase = 0, reads = 0, statusPolls = 0;
    void reset() { pageProgram = erase4k = erase32k = erase64k = chipErase = reads = statusPolls = 0; }

protected:
    void _write(const uint8_t* d, size_t n) override { note(d[0]); _c.write(d, n); _setOk(_c.ok()); }
    void _read(uint8_t* b, size_t n) override { _c.read(b, n); _setOk(_c.ok()); }
    void _write_read(const uint8_t* d, size_t dn, uint8_t* b, size_t bn) override {
        note(d[0]); _c.write_read(d, dn, b, bn); _setOk(_c.ok());
    }

private:
    void note(uint8_t op) {
        switch (op) {
            case 0x02: pageProgram++; break;
            case 0x20: erase4k++; break;
            case 0x52: erase32k++; break;
            case 0xD8: erase64k++; break;
            case 0xC7: case 0x60: chipErase++; break;
            case 0x03: case 0x0B: reads++; break;
            case 0x05: statusPolls++; break;
            default: break;
        }
    }
    Connection& _c;
};

static OpCounter* ops = nullptr;


// Starts a step: prints its title, resets the I/O counters, lists the first `show` operations.
static uint32_t step_t0;
static void step(int n, int total, const char* title, int show) {
    LOG("");
    LOG("[%d/%d] %s", n, total, title);
    tracer->reset();
    ops->reset();
    trace_budget = show;
    step_t0 = HAL_GetTick();
}

static void step_end(const char* result) {
    const TracingBlockDevice::Stats& s = tracer->stats();
    LOG("      -> %s  (%lu ms)  block I/O: %lu reads / %lu blocks, %lu writes / %lu blocks, %lu syncs, %lu errors",
        result, static_cast<unsigned long>(HAL_GetTick() - step_t0),
        static_cast<unsigned long>(s.readCalls), static_cast<unsigned long>(s.readBlocks),
        static_cast<unsigned long>(s.writeCalls), static_cast<unsigned long>(s.writeBlocks),
        static_cast<unsigned long>(s.syncCalls), static_cast<unsigned long>(s.errors));
    LOG("         flash commands: %lu page programs, %lu sector erases (4 KiB), %lu block erases (32/64 KiB), %lu read frames, %lu status polls",
        static_cast<unsigned long>(ops->pageProgram), static_cast<unsigned long>(ops->erase4k),
        static_cast<unsigned long>(ops->erase32k + ops->erase64k), static_cast<unsigned long>(ops->reads),
        static_cast<unsigned long>(ops->statusPolls));
    trace_budget = 0;
}

static bool fr_ok(FRESULT r, const char* what) {
    if (r == FR_OK) return true;
    LOG("      %s failed: %s (%d)", what, fr_name(r), static_cast<int>(r));
    return false;
}

static void pattern_fill(uint8_t* p, uint32_t offset, uint32_t n) {
    for (uint32_t i = 0; i < n; i++) p[i] = static_cast<uint8_t>((offset + i) * 31 + ((offset + i) >> 8));
}

static bool key_pressed(char* out) {
    uint8_t c;
    if (HAL_UART_Receive(&huart2, &c, 1, 0) == HAL_OK) { *out = static_cast<char>(c); return true; }
    if (HAL_GPIO_ReadPin(GPIOC, GPIO_PIN_13) == GPIO_PIN_RESET) {   // blue user button B1 (active low)
        while (HAL_GPIO_ReadPin(GPIOC, GPIO_PIN_13) == GPIO_PIN_RESET) HAL_Delay(10);
        *out = '\r';
        return true;
    }
    return false;
}

static void print_fs_summary() {
    DWORD nclst = 0;
    FATFS* pfs = nullptr;
    if (f_getfree("0:", &nclst, &pfs) != FR_OK) return;
    const char* type = pfs->fs_type == FS_FAT32 ? "FAT32" : pfs->fs_type == FS_FAT16 ? "FAT16" : "FAT12";
    uint32_t cluster_kib = pfs->csize * 512 / 1024;
    uint32_t total_mib = static_cast<uint32_t>((static_cast<uint64_t>(pfs->n_fatent - 2) * pfs->csize) / 2048);
    uint32_t free_mib = static_cast<uint32_t>((static_cast<uint64_t>(nclst) * pfs->csize) / 2048);
    LOG("      %s, cluster %lu KiB, %lu MiB total, %lu MiB free", type, static_cast<unsigned long>(cluster_kib),
        static_cast<unsigned long>(total_mib), static_cast<unsigned long>(free_mib));
}

static void finish(uint32_t t_all) {
    LOG("");
    LOG("Total: %lu ms", static_cast<unsigned long>(HAL_GetTick() - t_all));
    LOG("===DONE: %d passed, %d failed===", passed, failed);
}

static void run_tests(MX25L12845EFull& flash, bool force_format) {
    passed = failed = 0;
    const int N = 11;
    uint32_t t_all = HAL_GetTick();
    LOG("");
    LOG("================ FatFs on MX25L12845E (SPI NOR) — run ================");

    // 1 ------------------------------------------------------------------------
    step(1, N, "Flash chip", 0);
    BlockInfo info = flash.info();
    uint8_t jid[3] = {0, 0, 0};
    flash.jedec_id(jid);
    uint8_t bp = 0;
    flash.protection(&bp);
    LOG("      %s, JEDEC %02X %02X %02X, %lu MiB = %lu blocks of %lu bytes, erase unit %lu bytes, block protect BP=%u",
        info.name, jid[0], jid[1], jid[2], static_cast<unsigned long>(info.block_count / 2048),
        static_cast<unsigned long>(info.block_count), static_cast<unsigned long>(info.block_size),
        static_cast<unsigned long>(info.erase_size), bp);
    check(flash.status() == BlockError::Ok && info.block_count > 0, "flash initialised");
    check(bp == 0, "no block protection (FatFs needs the whole chip writable)");
    step_end("ok");

    // 2 ------------------------------------------------------------------------
    step(2, N, "Mount (looking for an existing FAT volume)", 6);
    f_mount(nullptr, "0:", 0);
    FRESULT r = force_format ? FR_NO_FILESYSTEM : f_mount(&fs, "0:", 1);
    if (r == FR_OK) { LOG("      found an existing filesystem"); step_end("mounted"); }
    else            { LOG("      %s -> will format", force_format ? "format requested" : fr_name(r)); step_end("needs format"); }

    if (r != FR_OK) {
        // 3 --------------------------------------------------------------------
        step(3, N, "Format (f_mkfs) — this rewrites the chip's filesystem metadata", 8);
        uint32_t t = HAL_GetTick();
        r = f_mkfs("0:", FM_ANY, 0, work, sizeof(work));
        LOG("      f_mkfs: %s after %lu ms", fr_name(r), static_cast<unsigned long>(HAL_GetTick() - t));
        check(r == FR_OK, "f_mkfs");
        step_end(fr_name(r));
        if (r != FR_OK) { finish(t_all); return; }
        r = f_mount(&fs, "0:", 1);
        check(r == FR_OK, "mount after format");
        if (r != FR_OK) { fr_ok(r, "f_mount"); finish(t_all); return; }
    } else {
        LOG("");
        LOG("[3/%d] Format skipped (press 'f' at the prompt to force a fresh format)", N);
    }

    // 4 ------------------------------------------------------------------------
    step(4, N, "Volume info, label", 8);
    print_fs_summary();
    r = f_setlabel("0:PERIPH");
    check(r == FR_OK, "set volume label PERIPH");
    char label[16] = {0};
    DWORD vsn = 0;
    f_getlabel("0:", label, &vsn);
    LOG("      label '%s', serial %08lX", label, static_cast<unsigned long>(vsn));
    check(strcmp(label, "PERIPH") == 0, "label reads back");
    step_end("ok");

    // 5 ------------------------------------------------------------------------
    step(5, N, "Directory + small file (PERIPH/HELLO.TXT)", 14);
    r = f_mkdir("PERIPH");
    check(r == FR_OK || r == FR_EXIST, "mkdir PERIPH");
    char text[96];
    int tlen = snprintf(text, sizeof(text), "Hello from Periph! NOR flash over SPI, written at tick %lu ms.\r\n",
                        static_cast<unsigned long>(HAL_GetTick()));
    UINT bw = 0;
    r = f_open(&file, "PERIPH/HELLO.TXT", FA_WRITE | FA_CREATE_ALWAYS);
    check(r == FR_OK, "create HELLO.TXT");
    if (r == FR_OK) {
        r = f_write(&file, text, tlen, &bw);
        check(r == FR_OK && bw == static_cast<UINT>(tlen), "write HELLO.TXT");
        check(f_close(&file) == FR_OK, "close (flushes FAT + directory entry)");
    }
    step_end("ok");

    // 6 ------------------------------------------------------------------------
    step(6, N, "Read it back", 6);
    char back[96] = {0};
    UINT br = 0;
    r = f_open(&file, "PERIPH/HELLO.TXT", FA_READ);
    if (r == FR_OK) { f_read(&file, back, sizeof(back) - 1, &br); f_close(&file); }
    LOG("      read %u bytes: %s", static_cast<unsigned>(br), back);
    check(br == static_cast<UINT>(tlen) && memcmp(back, text, tlen) == 0, "content matches");
    step_end("ok");

    // 7 ------------------------------------------------------------------------
    {
        const uint32_t BIG = 256 * 1024;
        step(7, N, "Large file (PERIPH/BIG.BIN, 256 KiB pattern, 4 KiB writes)", 6);
        uint32_t t = HAL_GetTick();
        r = f_open(&file, "PERIPH/BIG.BIN", FA_WRITE | FA_CREATE_ALWAYS);
        bool ok = (r == FR_OK);
        for (uint32_t off = 0; ok && off < BIG; off += sizeof(chunk)) {
            pattern_fill(chunk, off, sizeof(chunk));
            ok = f_write(&file, chunk, sizeof(chunk), &bw) == FR_OK && bw == sizeof(chunk);
        }
        ok = (f_close(&file) == FR_OK) && ok;
        uint32_t wms = HAL_GetTick() - t;
        check(ok, "write 256 KiB");
        LOG("      wrote 256 KiB in %lu ms = %lu KiB/s", static_cast<unsigned long>(wms),
            static_cast<unsigned long>(wms ? (BIG / 1024) * 1000 / wms : 0));
        t = HAL_GetTick();
        bool same = (f_open(&file, "PERIPH/BIG.BIN", FA_READ) == FR_OK);
        for (uint32_t off = 0; same && off < BIG; off += sizeof(chunk)) {
            pattern_fill(chunk, off, sizeof(chunk));
            same = f_read(&file, verify, sizeof(verify), &br) == FR_OK && br == sizeof(verify) &&
                   memcmp(chunk, verify, sizeof(chunk)) == 0;
        }
        f_close(&file);
        uint32_t rms = HAL_GetTick() - t;
        LOG("      read + verified 256 KiB in %lu ms = %lu KiB/s", static_cast<unsigned long>(rms),
            static_cast<unsigned long>(rms ? (BIG / 1024) * 1000 / rms : 0));
        check(same, "read-back matches the pattern");
        step_end("ok");
    }

    // 8 ------------------------------------------------------------------------
    step(8, N, "Long file name (PERIPH/A rather long file name.txt)", 8);
    r = f_open(&file, "PERIPH/A rather long file name.txt", FA_WRITE | FA_CREATE_ALWAYS);
    check(r == FR_OK, "create LFN file");
    if (r == FR_OK) { f_write(&file, "LFN works\r\n", 11, &bw); f_close(&file); }
    step_end("ok");

    // 9 ------------------------------------------------------------------------
    step(9, N, "Directory listing", 8);
    int entries = 0;
    if (f_opendir(&dir, "PERIPH") == FR_OK) {
        LOG("      PERIPH/");
        while (f_readdir(&dir, &fno) == FR_OK && fno.fname[0]) {
            LOG("        %-8s %9lu  %04u-%02u-%02u  %s", (fno.fattrib & AM_DIR) ? "<DIR>" : "file",
                static_cast<unsigned long>(fno.fsize), 1980 + (fno.fdate >> 9), (fno.fdate >> 5) & 15, fno.fdate & 31,
                fno.fname);
            entries++;
        }
        f_closedir(&dir);
    }
    check(entries == 3, "3 entries (HELLO.TXT, BIG.BIN, long name)");
    step_end("ok");

    // 10 -----------------------------------------------------------------------
    step(10, N, "Unmount, remount, read HELLO.TXT again (data must have reached the chip)", 10);
    f_mount(nullptr, "0:", 0);
    r = f_mount(&fs, "0:", 1);
    check(r == FR_OK, "remount");
    memset(back, 0, sizeof(back));
    br = 0;
    if (f_open(&file, "PERIPH/HELLO.TXT", FA_READ) == FR_OK) { f_read(&file, back, sizeof(back) - 1, &br); f_close(&file); }
    check(br == static_cast<UINT>(tlen) && memcmp(back, text, tlen) == 0, "HELLO.TXT unchanged after remount");
    step_end("ok");

    // 11 -----------------------------------------------------------------------
    step(11, N, "Delete BIG.BIN, space is returned", 8);
    print_fs_summary();
    r = f_unlink("PERIPH/BIG.BIN");
    check(r == FR_OK, "delete BIG.BIN");
    print_fs_summary();
    step_end("ok");

    finish(t_all);
}

// Read-only helpers for looking at what is on the chip before anything is written.
static void hexdump_sector0(MX25L12845EFull& flash) {
    static uint8_t b[512];
    flash.read(0, b, 1);
    LOG("First 256 bytes of the chip:");
    for (int row = 0; row < 16; row++) {
        printf("  %04X: ", row * 16);
        for (int i = 0; i < 16; i++) printf("%02X ", b[row * 16 + i]);
        printf(" ");
        for (int i = 0; i < 16; i++) printf("%c", (b[row * 16 + i] >= 32 && b[row * 16 + i] < 127) ? b[row * 16 + i] : '.');
        LOG("");
    }
}

// One character per 64 KiB block: '.' = all FF (blank), '#' = holds data. Reads the whole chip (about 25 s).
static void flash_map(MX25L12845EFull& flash) {
    static uint8_t buf[4096];
    LOG("Flash map (read-only; '.' = blank 64 KiB block, '#' = contains data), 16 blocks (1 MiB) per row:");
    uint32_t t = HAL_GetTick(), dataSectors = 0, dataBlocks = 0;
    for (uint32_t blk = 0; blk < 256; blk++) {
        bool any = false;
        for (uint32_t sec = 0; sec < 16; sec++) {
            flash.read(blk * 128 + sec * 8, buf, 8);
            for (int i = 0; i < 4096; i++) if (buf[i] != 0xFF) { dataSectors++; any = true; break; }
        }
        if (any) dataBlocks++;
        if (blk % 16 == 0) printf("  0x%06lX  ", static_cast<unsigned long>(blk * 65536));
        printf("%c", any ? '#' : '.');
        if (blk % 16 == 15) LOG("");
    }
    LOG("%lu of 4096 sectors (4 KiB) hold data, in %lu of 256 64 KiB blocks  (%lu ms)", static_cast<unsigned long>(dataSectors),
        static_cast<unsigned long>(dataBlocks), static_cast<unsigned long>(HAL_GetTick() - t));
}

// While the chip erases (driver polls the status register and sleeps through delay_ms) print a dot every 5 s.
static bool heartbeat = false;
static uint32_t heartbeat_last = 0;
static void delay_with_heartbeat(uint32_t ms) {
    HAL_Delay(ms);
    if (heartbeat && HAL_GetTick() - heartbeat_last >= 5000) {
        heartbeat_last = HAL_GetTick();
        printf(".");
    }
}

// Whole-chip erase (CE): destroys ALL 16 MiB. Asks for a second key press to confirm.
static bool erase_whole_chip(MX25L12845EFull& flash) {
    LOG("");
    LOG("!!! ERASE THE WHOLE CHIP: all 16 MiB (including any data on it) will be set to FF and cannot be recovered.");
    LOG("!!! Press 'Y' within 10 seconds to confirm; any other key (or no key) cancels.");
    uint32_t t = HAL_GetTick();
    char k = 0;
    while (HAL_GetTick() - t < 10000) {
        if (key_pressed(&k)) break;
        HAL_Delay(20);
    }
    if (k != 'Y' && k != 'y') { LOG("Cancelled, the chip was not touched."); return false; }

    uint8_t bp = 0;
    flash.protection(&bp);
    if (bp != 0) {
        LOG("Block protection BP=%u is set; chip erase is refused while it is. Clearing BP (non-volatile) first.", bp);
        if (flash.set_protection(0) != BlockError::Ok) { LOG("Could not clear BP, aborting."); return false; }
    }
    LOG("Erasing the chip (80 s typical, up to 200 s); a dot is printed every 5 s ...");
    uint32_t t0 = HAL_GetTick();
    heartbeat_last = t0;
    heartbeat = true;
    BlockError e = flash.chip_erase();
    heartbeat = false;
    LOG("");
    LOG("Chip erase: %s after %lu ms", e == BlockError::Ok ? "done" : block_error_name(e),
        static_cast<unsigned long>(HAL_GetTick() - t0));
    return e == BlockError::Ok;
}

// What does the first sector hold? Used to decide whether the chip may be formatted unattended.
enum Sector0 { S0_BLANK, S0_FAT, S0_OTHER };
static Sector0 classify_sector0(MX25L12845EFull& flash, uint8_t* first16) {
    static uint8_t b[512];
    flash.read(0, b, 1);
    memcpy(first16, b, 16);
    bool blank = true;
    for (int i = 0; i < 512; i++) if (b[i] != 0xFF) { blank = false; break; }
    if (blank) return S0_BLANK;
    if ((b[0] == 0xEB || b[0] == 0xE9) && b[510] == 0x55 && b[511] == 0xAA) return S0_FAT;
    return S0_OTHER;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);   // SPI Mode 0, 100 MHz / 16 = 6.25 MHz

    GPIO_InitTypeDef b = {};   // blue user button B1 on PC13 (active low)
    b.Pin = GPIO_PIN_13;
    b.Mode = GPIO_MODE_INPUT;
    b.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOC, &b);

    HAL_Delay(500);
    LOG("");
    LOG("Periph — FatFs R0.12c on an MX25L12845E NOR flash (NUCLEO-F411RE, SPI1: SCK PA5, MISO PA6, MOSI PA7, CS PB6)");

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);
    OpCounter counter(connection);
    ops = &counter;
    MX25L12845EOptions opt;
    opt.now_ms = HAL_GetTick;
    opt.delay_ms = delay_with_heartbeat;
    opt.auto_erase = true;   // FatFs overwrites sectors in place: let the driver erase-and-reprogram 4 KiB sectors
    MX25L12845EFull flash(counter, opt);
    if (flash.status() != BlockError::Ok) {
        LOG("MX25L12845E did not answer (error %d). Check wiring (CS on PB6) and power, then press reset.", static_cast<int>(flash.status()));
        while (true) HAL_Delay(1000);
    }
    TracingBlockDevice traced(flash, trace_io);
    tracer = &traced;
    fatfs_attach(0, &traced);

    uint8_t first16[16];
    Sector0 s0 = classify_sector0(flash, first16);
    LOG("First sector of the chip: %s", s0 == S0_BLANK ? "blank (all FF) — a fresh format is safe"
                                    : s0 == S0_FAT ? "a FAT boot sector — will be mounted"
                                    : "NOT blank and NOT a FAT boot sector — will not be formatted unless you press 'f'");
    if (s0 == S0_OTHER) { LOG("   first bytes:"); for (int i = 0; i < 16; i++) printf("%02X ", first16[i]); LOG(""); }

    char key = 0;
    bool first = true;
    while (true) {
        LOG("");
        LOG("Keys: any = run the test, 'f' = format first, 'd' = dump first 256 bytes, 'm' = map used areas (read-only), 'E' = ERASE WHOLE CHIP (asks to confirm).");
        uint32_t t = HAL_GetTick();
        // On power-up run once by itself (only if that cannot destroy data), so the log is not empty when the terminal attaches late.
        while (!(first && s0 != S0_OTHER && HAL_GetTick() - t > 3000) && !key_pressed(&key)) {
            if (HAL_GetTick() - t > 5000) { LOG("  (waiting for a key ...)"); t = HAL_GetTick(); }
            HAL_Delay(20);
        }
        bool fmt = (key == 'f' || key == 'F');
        if (key == 'E') {   // capital E only, on purpose
            if (erase_whole_chip(flash)) {
                s0 = classify_sector0(flash, first16);
                LOG("First sector of the chip: %s", s0 == S0_BLANK ? "blank (all FF) — a fresh format is safe" : "still not blank!");
                LOG("Press 'm' to verify the whole chip is blank, or any key to format it and run the FatFs test.");
            }
            first = false; key = 0; continue;
        }
        if (key == 'm' || key == 'M') { flash_map(flash); first = false; key = 0; continue; }
        if (key == 'd' || key == 'D') { hexdump_sector0(flash); first = false; key = 0; continue; }
        if (s0 == S0_OTHER && !fmt) {
            LOG("Refusing to mount/format: the chip holds data that is not a FAT volume. Press 'f' to format it anyway.");
        } else {
            run_tests(flash, fmt);
        }
        first = false;
        key = 0;
    }
}
