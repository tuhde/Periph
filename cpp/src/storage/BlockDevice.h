#pragma once
#include <stdint.h>
#include <stddef.h>

/** @brief Status returned by every BlockDevice operation (`Ok == 0`).
 *
 * C++ drivers in this library do not throw; see `specs/feature_block_device.md`
 * for the meaning of each value.
 */
enum class BlockError : uint8_t {
    Ok = 0,
    NotReady,       ///< device absent or initialisation failed
    OutOfRange,     ///< block index/count beyond block_count(), or unaligned erase()
    NotErased,      ///< program to an un-erased block while auto_erase is off
    WriteProtected, ///< card WP / status-register block protection
    BadBlock,       ///< target erase unit is marked bad (NAND)
    Ecc,            ///< uncorrectable ECC error on read (NAND)
    Crc,            ///< SD command/data CRC mismatch
    Timeout,        ///< busy never cleared
    Io,             ///< transport failure
};

/** @brief Geometry and capabilities of a BlockDevice. */
struct BlockInfo {
    uint32_t block_size  = 0;      ///< read/program unit in bytes
    uint32_t block_count = 0;      ///< number of addressable blocks
    uint32_t erase_size  = 0;      ///< erase unit in bytes (multiple of block_size)
    bool     needs_erase = false;  ///< true if a block must be erased before it is programmed
    bool     read_only   = false;  ///< write protected
    bool     bad_blocks  = false;  ///< device may contain bad erase units
    char     name[16]    = {0};    ///< short device name (e.g. SD product name), NUL-terminated
};

/** @brief Common contract for block-storage chips (SD, SD NAND, SPI NOR, SPI NAND).
 *
 * Only the Full chip classes implement it. Units are bytes and block indices;
 * a block is the read/program unit exposed to a filesystem. See
 * `specs/feature_block_device.md`.
 */
class BlockDevice {
public:
    virtual ~BlockDevice() = default;

    /** @brief Logical block size in bytes (read/program unit). */
    virtual uint32_t block_size() const = 0;
    /** @brief Number of addressable logical blocks. */
    virtual uint32_t block_count() const = 0;
    /** @brief Erase unit in bytes; equals block_size() when !needs_erase(). */
    virtual uint32_t erase_size() const = 0;
    /** @brief True if blocks must be erased before they are programmed. */
    virtual bool needs_erase() const = 0;

    /** @brief Read @p count consecutive blocks into @p buf (count * block_size() bytes). */
    virtual BlockError read(uint32_t block, uint8_t* buf, uint32_t count) = 0;
    /** @brief Program @p count consecutive blocks from @p data. */
    virtual BlockError write(uint32_t block, const uint8_t* data, uint32_t count) = 0;
    /** @brief Erase the erase units covering [block, block+count). No-op on SD. */
    virtual BlockError erase(uint32_t block, uint32_t count) = 0;
    /** @brief Block until all pending program/erase work has finished. */
    virtual BlockError sync() = 0;
    /** @brief Geometry and capabilities. */
    virtual BlockInfo info() = 0;

    /** @brief Optional (`info().bad_blocks`): whether erase unit @p unit is bad. */
    virtual bool is_bad(uint32_t unit) { (void)unit; return false; }
    /** @brief Optional: persist a bad-block mark for erase unit @p unit. */
    virtual BlockError mark_bad(uint32_t unit) { (void)unit; return BlockError::Ok; }
};
