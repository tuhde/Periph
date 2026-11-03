#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/SPIExtensions.h"
#include "../../storage/BlockDevice.h"

/** @brief Driver options for SDSPIMinimal / SDSPIFull. */
struct SDSPIOptions {
    uint32_t speed_hz = 12000000;      ///< data-phase clock after init (init always runs at 400 kHz)
    uint32_t (*now_ms)() = nullptr;    ///< millisecond clock for timeouts; nullptr = bounded poll counts
    bool     crc = false;              ///< enable CMD59 and verify/send CRC16 on data blocks
};

/** @brief SD / SDHC / SDXC / microSD card in SPI mode — Minimal driver.
 *
 * Runs the SPI-mode initialisation sequence (CMD0, CMD8, ACMD41, CMD58, ...)
 * in the constructor, then offers single 512-byte block reads and writes.
 * See `specs/memory/sd_spi.md`.
 *
 * The driver needs the SPI optional extensions (`idle_clocks`, `set_speed`,
 * `select`/`deselect`, `specs/transport_spi.md`) and a 3.3 V card. Check
 * `status()` after construction; the driver never throws or allocates.
 */
class SDSPIMinimal {
public:
    using Options = SDSPIOptions;

    static constexpr uint32_t BLOCK_SIZE = 512;

    /** @brief Card generation detected during init. */
    enum class CardType : uint8_t { None, SDv1, SDv2, SDHC };

    /** @brief Run the init sequence on @p spi. */
    explicit SDSPIMinimal(SPIExtensions& spi, const Options& opt = Options());
    virtual ~SDSPIMinimal() = default;

    /** @brief Result of the last init: `Ok`, `NotReady` (no/unsupported card) or `Timeout`. */
    BlockError status() const { return _status; }

    /** @brief Re-run the init sequence (e.g. after the card was re-inserted). */
    BlockError begin();

    /** @brief Read block @p block (512 bytes) into @p buf, `CMD17`. */
    BlockError read_block(uint32_t block, uint8_t* buf);

    /** @brief Write block @p block from @p data (512 bytes), `CMD24`. */
    BlockError write_block(uint32_t block, const uint8_t* data);

    /** @brief Capacity in 512-byte blocks (from the CSD), 0 if init failed. */
    uint32_t block_count() const { return _blockCount; }
    /** @brief Alias of block_count() (FatFs terminology). */
    uint32_t sector_count() const { return _blockCount; }

    /** @brief Card generation. */
    CardType card_type() const { return _type; }

protected:
    // Timeouts (ms), see "Timing Constraints" in the spec.
    static constexpr uint32_t INIT_TIMEOUT_MS = 1000;
    static constexpr uint32_t TOKEN_TIMEOUT_MS = 200;
    static constexpr uint32_t BUSY_TIMEOUT_MS = 500;
    static constexpr uint32_t ERASE_TIMEOUT_MS = 30000;

    struct Deadline { uint32_t start; uint32_t ms; uint32_t polls; };

    Deadline _deadline(uint32_t ms);
    bool _expired(Deadline& d);

    uint8_t _rx();
    void _tx(const uint8_t* data, size_t len);
    void _release();                         // deselect + 8 idle clocks so the card frees MISO
    BlockError _waitReady(uint32_t ms);      // CS must be selected
    uint8_t _command(uint8_t cmd, uint32_t arg);  // CS must be selected; returns R1 or 0xFF
    uint8_t _appCommand(uint8_t cmd, uint32_t arg);
    BlockError _readData(uint8_t* buf, size_t len);
    BlockError _writeData(uint8_t token, const uint8_t* data);
    BlockError _readCsd(uint8_t csd[16]);
    BlockError _readRegister(uint8_t cmd, uint8_t out[16]);
    uint32_t _address(uint32_t block) const { return _blockAddressing ? block : block * BLOCK_SIZE; }

    static uint8_t crc7(const uint8_t* data, size_t len);
    static uint16_t crc16(const uint8_t* data, size_t len);

    SPIExtensions& _spi;
    Options _opt;
    BlockError _status = BlockError::NotReady;
    CardType _type = CardType::None;
    bool _blockAddressing = false;
    bool _crcOn = false;
    uint32_t _blockCount = 0;
    uint8_t _csd[16] = {0};
    uint32_t _fakeTick = 0;
};

/** @brief SD card in SPI mode — Full driver, implements BlockDevice.
 *
 * Adds multi-block transfers (`CMD18`/`CMD25`), erase, CID/CSD/OCR access and
 * CRC checking (on by default).
 */
class SDSPIFull : public SDSPIMinimal, public BlockDevice {
public:
    /** @brief Options with CRC16 checking enabled (the Full default). */
    static Options defaults() { Options o; o.crc = true; return o; }

    explicit SDSPIFull(SPIExtensions& spi, const Options& opt = defaults());

    uint32_t block_size() const override { return BLOCK_SIZE; }
    uint32_t block_count() const override { return _blockCount; }
    uint32_t erase_size() const override { return BLOCK_SIZE; }
    bool needs_erase() const override { return false; }

    BlockError read(uint32_t block, uint8_t* buf, uint32_t count) override;
    BlockError write(uint32_t block, const uint8_t* data, uint32_t count) override;
    BlockError erase(uint32_t block, uint32_t count) override;
    BlockError sync() override;
    BlockInfo info() override;

    /** @brief 16-byte CID register (manufacturer, name, serial, date). */
    BlockError cid(uint8_t out[16]);
    /** @brief 16-byte CSD register (as read during init). */
    const uint8_t* csd() const { return _csd; }
    /** @brief OCR word (CMD58). */
    BlockError ocr(uint32_t* out);

    /** @brief Enable/disable CRC (`CMD59`) and CRC16 verification. */
    BlockError set_crc(bool enabled);
    /** @brief Change the SCK frequency after init; false if the platform cannot. */
    bool set_speed(uint32_t hz) { return _spi.set_speed(hz); }
    /** @brief Whether a card responds (CMD13). */
    bool is_present();

private:
    char _name[6] = {0};
};
