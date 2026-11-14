#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief AT24C64 64 Kbit (8 KiB) I2C EEPROM — minimal interface.
 *
 * Self-timed writes (10 ms max (20 ms at 1.8 V)) are completed by ACK polling: after every
 * write the driver polls the device address until the chip answers again, so
 * a returned write is always committed. The chip has no configuration
 * registers; it is ready after power-up.
 *
 * Memory layout:
 * - 0x0000-0x1FFF — 8,192 bytes, 13-bit address, big-endian on the wire,
 *   256 pages of 32 bytes
 *
 * Default configuration (nothing to configure):
 * - The full 8 KiB is addressable as raw bytes
 * - write_byte() returns after the write cycle has finished
 * - Writes are ignored while the WP pin is high (hardware only; the driver
 *   cannot detect it — verify with a read-back if WP is wired)
 *
 * C++ builds without exceptions: read_byte() returns 0 and write_byte()
 * returns false if the write cycle does not complete within the timeout.
 *
 * @param connection RegisterConnection (I²C or SMBus) constructed with
 *        regBytes = 2, pointing at the device (address 0x50-0x57, set by A2:A0).
 */
class AT24C64Minimal {
public:
    AT24C64Minimal(RegisterConnection& connection);

    /** @brief Read a single byte (random read).
     *  @param address Memory address 0-8191.
     *  @return Byte value 0-255.
     */
    uint8_t read_byte(uint16_t address);

    /** @brief Write a single byte, then ACK-poll until the write cycle completes.
     *  @param address Memory address 0-8191.
     *  @param value   Byte value 0-255.
     *  @return true if the write cycle completed within the timeout.
     */
    bool write_byte(uint16_t address, uint8_t value);

protected:
    static constexpr uint32_t SIZE = 0x2000;
    static constexpr uint16_t PAGE = 32;
    static constexpr uint16_t WRITE_TIMEOUT_MS = 25;  // tWR = 10 ms max (20 ms at 1.8 V), plus margin

    RegisterConnection& _connection;

    /** @brief ACK-poll: an address-only write is acknowledged once the write cycle has ended. */
    bool _wait_ready(uint16_t timeout_ms);
};

/** @brief AT24C64 full interface — extends minimal with block access and page writes.
 *
 *  Adds sequential read, page-boundary-aware write, page write, range fill,
 *  array and page size and explicit ACK polling. Writes never cross a
 *  32-byte page boundary (that would wrap inside the page and corrupt data)
 *  and each chunk is ACK-polled. Ranges that would run past 0x1FFF are
 *  rejected (return false) instead of wrapping to 0x0000 as the hardware
 *  would.
 *
 *  @param connection RegisterConnection (I²C or SMBus) constructed with
 *         regBytes = 2, pointing at the device (address 0x50-0x57).
 */
class AT24C64Full : public AT24C64Minimal {
public:
    AT24C64Full(RegisterConnection& connection);

    /** @brief Array size in bytes.
     *  @return 8192.
     */
    uint32_t size() const { return SIZE; }

    /** @brief Write page size in bytes.
     *  @return 32.
     */
    uint16_t page_size() const { return PAGE; }

    /** @brief Maximum data bytes per bus transaction (default 30).
     *
     *  Arduino Wire limits a transfer to 32 bytes including the 2 address bytes.
     */
    uint8_t max_chunk() const { return _max_chunk; }

    /** @brief Set the maximum data bytes per bus transaction.
     *  @param value 1-32; values outside the range are clamped.
     */
    void set_max_chunk(uint8_t value);

    /** @brief ACK-poll until the chip answers its device address again.
     *  @param timeout_ms Maximum time to poll in milliseconds.
     *  @return true if the chip responded within the timeout.
     */
    bool wait_ready(uint16_t timeout_ms);

    /** @brief Sequential read of @p length bytes starting at @p address.
     *
     *  Split into transactions of at most max_chunk() bytes.
     *
     *  @param address Starting address 0-8191.
     *  @param buf     Destination buffer (at least @p length bytes).
     *  @param length  Number of bytes to read.
     *  @return true on success, false if address…address+length is not inside 0x0000-0x1FFF.
     */
    bool read(uint16_t address, uint8_t* buf, uint32_t length);

    /** @brief Write @p data starting at @p address, split at page boundaries.
     *
     *  Each chunk is at most max_chunk() bytes and never crosses a 32-byte
     *  page boundary; the write cycle is ACK-polled after every chunk.
     *
     *  @param address Starting address 0-8191.
     *  @param data    Bytes to write.
     *  @param length  Number of bytes from @p data to write.
     *  @return true on success, false on a range error or if a write cycle does not complete.
     */
    bool write(uint16_t address, const uint8_t* data, uint32_t length);

    /** @brief Write within a single page (sent as one or more max_chunk()-sized transactions), ACK-polled.
     *  @param address Starting address 0-8191.
     *  @param data    1-32 bytes, all inside the same 32-byte page.
     *  @param length  Number of bytes.
     *  @return true on success, false if the data crosses a page boundary, runs outside the array,
     *          or a write cycle does not complete.
     */
    bool write_page(uint16_t address, const uint8_t* data, uint16_t length);

    /** @brief Write @p value over a range, page by page.
     *  @param address Starting address 0-8191.
     *  @param length  Number of bytes to fill.
     *  @param value   Byte value 0-255 (default 0).
     *  @return true on success, false on a range error or if a write cycle does not complete.
     */
    bool fill(uint16_t address, uint32_t length, uint8_t value = 0);

private:
    uint8_t _max_chunk = 30;

    static bool _in_range(uint32_t address, uint32_t length);
    uint8_t _chunk_len(uint32_t address, uint32_t remaining) const;
};
