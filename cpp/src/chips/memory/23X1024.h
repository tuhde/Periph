#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief 23LC1024 / 23A1024 1-Mbit SPI serial SRAM — minimal interface.
 *
 * Byte read/write access to the 131,072-byte array over plain SPI (Mode 0).
 * The chip is volatile but has unlimited cycles and zero write time: a
 * write is complete when CS is released, with no polling.
 *
 * Default configuration:
 * - Sequential mode (MODE register = 0x40), written and read back at
 *   construction; a mismatch (floating/missing MISO, wrong SPI mode, chip
 *   stuck in SDI/SQI mode) aborts.
 * - Full 128 KiB addressable; SDI/SQI bus modes are never used.
 *
 * Every transaction is one instruction byte, a 24-bit big-endian address and
 * data, with CS held low for the whole frame, so the driver takes the bare
 * Connection and uses write()/write_read() directly (the instruction is not a
 * register address, so RegisterConnection's read-bit logic does not apply).
 *
 * @param connection SPI Connection (Mode 0, MSB first, CS managed by the connection).
 */
class SRAM23X1024Minimal {
public:
    static constexpr uint32_t SIZE = 131072;

    explicit SRAM23X1024Minimal(Connection& connection);

    /** @brief Read one byte (READ, 0x03).
     *  @param address 0-131071; out of range aborts.
     *  @return Byte value 0-255.
     */
    uint8_t read_byte(uint32_t address);

    /** @brief Write one byte (WRITE, 0x02); complete when CS is released.
     *  @param address 0-131071; out of range aborts.
     *  @param value   Byte value 0-255.
     */
    void write_byte(uint32_t address, uint8_t value);

protected:
    static constexpr uint8_t CMD_READ  = 0x03;
    static constexpr uint8_t CMD_WRITE = 0x02;
    static constexpr uint8_t CMD_RDMR  = 0x05;
    static constexpr uint8_t CMD_WRMR  = 0x01;
    static constexpr uint8_t CMD_RSTIO = 0xFF;
    static constexpr uint8_t MODE_MASK = 0xC0;

    Connection& _connection;

    void _init();
    uint8_t _read_mode();
    void _write_mode(uint8_t mode);
    void _read_raw(uint32_t address, uint8_t* buf, size_t length);
    void _write_raw(uint32_t address, const uint8_t* data, size_t length);
};

/** @brief 23X1024 full interface — extends minimal with block access, operating modes and I/O reset.
 *
 * `read()`/`write()`/`fill()` have linear-address semantics in every mode:
 * the driver splits transfers into transactions of at most max_chunk data
 * bytes and, in Page/Byte mode, at the 32-byte page / single-byte boundaries
 * the hardware would otherwise wrap or truncate at. Ranges that would leave
 * the array abort instead of silently wrapping around.
 *
 * @param connection SPI Connection (Mode 0, MSB first, CS managed by the connection).
 */
class SRAM23X1024Full : public SRAM23X1024Minimal {
public:
    static constexpr uint8_t MODE_BYTE       = 0x00;
    static constexpr uint8_t MODE_PAGE       = 0x80;
    static constexpr uint8_t MODE_SEQUENTIAL = 0x40;

    explicit SRAM23X1024Full(Connection& connection);

    /** @brief Block read of @p length bytes starting at @p address.
     *  @param address Start address; address + length must be <= 131072.
     *  @param buf     Destination buffer (at least @p length bytes).
     *  @param length  Number of bytes.
     */
    void read(uint32_t address, uint8_t* buf, size_t length);

    /** @brief Block write of @p length bytes starting at @p address (same range rule as read()). */
    void write(uint32_t address, const uint8_t* data, size_t length);

    /** @brief Write @p value over @p length bytes starting at @p address (chunked). */
    void fill(uint32_t address, size_t length, uint8_t value = 0);

    /** @brief Array size in bytes (131072). */
    uint32_t size() const { return SIZE; }

    /** @brief Select the operating mode (WRMR) and verify it with RDMR.
     *  @param mode MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL; any other value aborts.
     */
    void set_mode(uint8_t mode);

    /** @brief Read the operating mode (RDMR bits 7:6) as one of the MODE_* constants. */
    uint8_t get_mode();

    /** @brief Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO.
     *
     *  Resynchronises the cached mode with the chip (re-reads RDMR).
     */
    void reset_io();

    /** @brief Maximum data bytes per bus transaction (default 256). */
    size_t max_chunk() const { return _max_chunk; }

    /** @brief Set the maximum data bytes per bus transaction (1-256; others abort). */
    void set_max_chunk(size_t n);

private:
    static constexpr size_t PAGE_SIZE = 32;

    uint8_t _mode = MODE_SEQUENTIAL;
    size_t  _max_chunk = 256;

    size_t _chunk_len(uint32_t address, size_t remaining) const;
};
