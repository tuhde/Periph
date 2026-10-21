#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../storage/BlockDevice.h"

/** @brief Driver options for MX25L12845EMinimal / MX25L12845EFull. */
struct MX25L12845EOptions {
    uint32_t (*now_ms)() = nullptr;      ///< millisecond clock for timeouts; nullptr = bounded poll counts
    void (*delay_ms)(uint32_t) = nullptr; ///< sleep used while polling long operations and after RDP; nullptr = busy poll
    bool auto_erase = false;             ///< BlockDevice::write() erases sectors itself (Full class)
};

/** @brief Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash — Minimal driver.
 *
 * Byte-addressable access with transparent erase: `read_bytes()`, an erase-aware
 * `write_bytes()` (read-modify-erase-write per 4 KiB sector, programming only what
 * changed), `erase_sector()`. See `specs/memory/mx25l12845e.md`.
 *
 * Plain single-I/O SPI Mode 0 over a bare `Connection` (CS held low per frame).
 * The driver owns a 4 KiB sector scratch buffer. Check `status()` after
 * construction; the driver never throws or allocates.
 */
class MX25L12845EMinimal {
public:
    static constexpr uint32_t SIZE        = 16u * 1024u * 1024u;  ///< capacity in bytes
    static constexpr uint32_t PAGE_SIZE   = 256;                  ///< program granularity
    static constexpr uint32_t SECTOR_SIZE = 4096;                 ///< smallest erase unit

    /** @brief Run the init sequence: RDP, RDID check (C2 20 18), wait until not busy. */
    explicit MX25L12845EMinimal(Connection& connection, const MX25L12845EOptions& opt = MX25L12845EOptions());
    virtual ~MX25L12845EMinimal() = default;

    /** @brief `Ok`, or `NotReady` if the JEDEC ID did not match, or `Timeout`. */
    BlockError status() const { return _status; }

    /** @brief Read @p len bytes starting at @p address (`READ`, 03h), any length and alignment. */
    BlockError read_bytes(uint32_t address, uint8_t* buf, uint32_t len);

    /** @brief Erase-aware write of @p len bytes at @p address; blocks until the chip is ready.
     *
     * Per 4 KiB sector: if only 1→0 transitions are needed just the changed
     * pages are programmed, otherwise the sector is erased and rewritten with
     * the merged contents. Returns `WriteProtected` if the chip reports a
     * program/erase failure while block-protect bits are set.
     */
    BlockError write_bytes(uint32_t address, const uint8_t* data, uint32_t len);

    /** @brief Erase the 4 KiB sector containing @p address (`SE`, 20h). */
    BlockError erase_sector(uint32_t address);

    /** @brief Capacity in bytes (16 777 216). */
    uint32_t size() const { return SIZE; }

    /** @brief The three RDID bytes (manufacturer, memory type, density). */
    BlockError jedec_id(uint8_t id[3]);

protected:
    // Opcodes (datasheet Table 5).
    static constexpr uint8_t CMD_WREN = 0x06, CMD_WRDI = 0x04, CMD_RDID = 0x9F, CMD_RES = 0xAB, CMD_REMS = 0x90;
    static constexpr uint8_t CMD_RDSR = 0x05, CMD_WRSR = 0x01, CMD_READ = 0x03, CMD_FAST_READ = 0x0B;
    static constexpr uint8_t CMD_PP = 0x02, CMD_SE = 0x20, CMD_BE32K = 0x52, CMD_BE64K = 0xD8, CMD_CE = 0xC7;
    static constexpr uint8_t CMD_DP = 0xB9, CMD_RDP = 0xAB, CMD_RDSFDP = 0x5A, CMD_ENSO = 0xB1, CMD_EXSO = 0xC1;
    static constexpr uint8_t CMD_RDSCUR = 0x2B, CMD_WRSCUR = 0x2F, CMD_CLSR = 0x30;

    static constexpr uint8_t SR_WIP = 0x01, SR_WEL = 0x02, SR_BP_MASK = 0x3C, SR_BP_SHIFT = 2, SR_QE = 0x40, SR_SRWD = 0x80;
    static constexpr uint8_t SCUR_LDSO = 0x02, SCUR_P_FAIL = 0x20, SCUR_E_FAIL = 0x40;

    // Timeouts (ms): max of the datasheet cycle times with some margin.
    static constexpr uint32_t T_WRSR_MS = 150, T_PP_MS = 10, T_SE_MS = 400, T_BE_MS = 3000, T_CE_MS = 250000;
    static constexpr uint32_t READ_CHUNK = 128;   // keeps frames within the 256-byte buffers of some connections

    BlockError _readStatus(uint8_t* sr);
    BlockError _wren();
    BlockError _waitReady(uint32_t timeoutMs);
    BlockError _checkFail();                                      // RDSCUR: P_FAIL/E_FAIL -> CLSR + error
    BlockError _command(uint8_t cmd);                             // single-byte command frame
    BlockError _eraseCmd(uint8_t cmd, uint32_t address, uint32_t timeoutMs);
    BlockError _program(uint32_t address, const uint8_t* data, uint32_t len);   // one PP, within one page
    BlockError _readRaw(uint8_t cmd, uint32_t address, uint8_t dummy, uint8_t* buf, uint32_t len);
    BlockError _doRead(uint32_t address, uint8_t* buf, uint32_t len);
    void _sleep(uint32_t ms);

    Connection& _c;
    MX25L12845EOptions _opt;
    BlockError _status = BlockError::NotReady;
    uint8_t _sector[SECTOR_SIZE];
    uint8_t _frame[4 + PAGE_SIZE];
    uint32_t _fakeTick = 0;
};

/** @brief MX25L12845E — Full driver: raw commands, protection, power, OTP, SFDP; implements BlockDevice.
 *
 * Block device geometry: 512-byte blocks, 32 768 blocks, 4 KiB erase units,
 * `needs_erase() == true`. With `options.auto_erase == false` (default) a
 * `write()` that would need a 0→1 transition fails with `NotErased`.
 */
class MX25L12845EFull : public MX25L12845EMinimal, public BlockDevice {
public:
    explicit MX25L12845EFull(Connection& connection, const MX25L12845EOptions& opt = MX25L12845EOptions())
        : MX25L12845EMinimal(connection, opt) {}

    // --- raw operations ---
    /** @brief `FAST_READ` (0Bh) of @p len bytes. */
    BlockError fast_read(uint32_t address, uint8_t* buf, uint32_t len);
    /** @brief One `PP` of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased. */
    BlockError page_program(uint32_t address, const uint8_t* data, uint32_t len);
    /** @brief Erase the 32 KiB block containing @p address (`BE32K`, 52h). */
    BlockError erase_block_32k(uint32_t address);
    /** @brief Erase the 64 KiB block containing @p address (`BE`, D8h). */
    BlockError erase_block_64k(uint32_t address);
    /** @brief Erase the whole chip (`CE`, C7h); 80 s typ, 200 s max; needs BP3:0 == 0. */
    BlockError chip_erase();

    // --- status / protection ---
    BlockError read_status(uint8_t* sr);
    BlockError write_status(uint8_t value);
    /** @brief BP3:0 (0..15) from the status register. */
    BlockError protection(uint8_t* bp);
    /** @brief Set BP3:0; the bits are non-volatile. QE and SRWD are preserved. */
    BlockError set_protection(uint8_t bp);
    bool is_busy();
    BlockError wait_ready(uint32_t timeoutMs) { return _waitReady(timeoutMs); }
    BlockError read_security(uint8_t* scur);
    BlockError clear_fail_flags();

    // --- power / identification ---
    BlockError deep_power_down();
    BlockError release_deep_power_down();
    /** @brief `RES` electronic ID (17h). */
    BlockError electronic_id(uint8_t* id);
    /** @brief `REMS` manufacturer (C2h) and device ID (17h). */
    BlockError device_ids(uint8_t* manufacturer, uint8_t* device);
    /** @brief `RDSFDP` (5Ah): read @p len bytes of the SFDP table from @p address. */
    BlockError read_sfdp(uint32_t address, uint8_t* buf, uint32_t len);

    // --- secured OTP (4 Kbit) ---
    /** @brief Read @p len bytes at OTP offset @p offset (0..511). */
    BlockError read_otp(uint32_t offset, uint8_t* buf, uint32_t len);
    /** @brief Program OTP bytes at @p offset (16..511 only: 0..15 hold the factory ESN); one-time programmable. */
    BlockError program_otp(uint32_t offset, const uint8_t* data, uint32_t len);
    /** @brief Lock the customer OTP area for good (`WRSCUR`, sets LDSO) — IRREVERSIBLE. */
    BlockError lock_otp();
    /** @brief The 16-byte factory electronic serial number. */
    BlockError serial_number(uint8_t out[16]);

    // --- BlockDevice ---
    uint32_t block_size() const override { return 512; }
    uint32_t block_count() const override { return SIZE / 512; }
    uint32_t erase_size() const override { return SECTOR_SIZE; }
    bool needs_erase() const override { return true; }
    BlockError read(uint32_t block, uint8_t* buf, uint32_t count) override;
    BlockError write(uint32_t block, const uint8_t* data, uint32_t count) override;
    BlockError erase(uint32_t block, uint32_t count) override;
    BlockError sync() override { return _waitReady(T_CE_MS); }
    BlockInfo info() override;

private:
    BlockError _otpEnter();
    BlockError _otpExit();
};
