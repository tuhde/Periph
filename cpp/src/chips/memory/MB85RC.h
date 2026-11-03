#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief MB85RC256V 256 Kbit (32 KiB) I2C FRAM — minimal interface.
 *
 * Ferroelectric RAM: non-volatile like EEPROM, but every write completes at
 * bus speed — no write cycle, no page buffer, no ACK polling, and at least
 * 10^12 write cycles per byte. The chip has no configuration registers; it
 * is ready for use immediately after power-on.
 *
 * Memory layout:
 * - 0x0000-0x7FFF — 32,768 bytes of FRAM, 15-bit address, big-endian on the wire
 *
 * Default configuration (nothing to configure):
 * - The full 32 KiB is addressable as raw bytes
 * - write_byte() returns as soon as the transfer completes
 * - Writes are ignored by the chip while the WP pin is high (hardware only;
 *   the driver cannot detect it)
 *
 * Out-of-range addresses are rejected without any bus traffic (C++ builds
 * without exceptions): read_byte() returns 0, write_byte() returns false.
 *
 * @param connection RegisterConnection (I²C or SMBus) constructed with
 *        regBytes = 2, pointing at the device (address 0x50-0x57, set by A2:A0).
 */
class MB85RCMinimal {
public:
    MB85RCMinimal(RegisterConnection& connection);

    /** @brief Read a single byte (random read).
     *  @param address Memory address 0-32767.
     *  @return Byte value 0-255; 0 if the address is outside 0-32767.
     */
    uint8_t read_byte(uint16_t address);

    /** @brief Write a single byte (byte write); returns when the transfer completes.
     *
     *  There is no write cycle and no polling — data is committed right
     *  after the byte is acknowledged.
     *
     *  @param address Memory address 0-32767.
     *  @param value   Byte value 0-255.
     *  @return true on success, false if the address is outside 0-32767.
     */
    bool write_byte(uint16_t address, uint8_t value);

protected:
    static constexpr uint16_t SIZE = 0x8000;

    RegisterConnection& _connection;
};

/** @brief MB85RC256V full interface — extends minimal with block access and Device ID.
 *
 *  Adds sequential read/write split into bus-sized chunks, range fill,
 *  the array size, and the 3-byte Device ID read through the reserved
 *  slave address 0x7C. Ranges that would run past 0x7FFF are rejected
 *  (return false) instead of silently wrapping to 0x0000 as the hardware would.
 *
 *  @param connection RegisterConnection (I²C or SMBus) constructed with
 *         regBytes = 2, pointing at the device (address 0x50-0x57).
 */
class MB85RCFull : public MB85RCMinimal {
public:
    MB85RCFull(RegisterConnection& connection);

    /** @brief Array size in bytes.
     *  @return 32768.
     */
    uint16_t size() const { return SIZE; }

    /** @brief Maximum data bytes per bus transaction (default 30).
     *
     *  Arduino Wire limits a transfer to 32 bytes including the 2 address bytes.
     */
    uint8_t max_chunk() const { return _max_chunk; }

    /** @brief Set the maximum data bytes per bus transaction.
     *  @param value 1-32; values outside the range are clamped.
     */
    void set_max_chunk(uint8_t value);

    /** @brief Sequential read of @p length bytes starting at @p address.
     *
     *  Split into transactions of at most max_chunk() bytes.
     *
     *  @param address Starting address 0-32767.
     *  @param buf     Destination buffer (at least @p length bytes).
     *  @param length  Number of bytes to read.
     *  @return true on success, false if address…address+length is not inside 0x0000-0x7FFF.
     */
    bool read(uint16_t address, uint8_t* buf, uint16_t length);

    /** @brief Sequential write of @p data starting at @p address.
     *
     *  Split into transactions of at most max_chunk() bytes; each chunk is a
     *  fresh transaction with its own address bytes. No delay between chunks.
     *
     *  @param address Starting address 0-32767.
     *  @param data    Bytes to write.
     *  @param length  Number of bytes from @p data to write.
     *  @return true on success, false if address…address+length is not inside 0x0000-0x7FFF.
     */
    bool write(uint16_t address, const uint8_t* data, uint16_t length);

    /** @brief Write @p value over a range, in max_chunk()-sized transactions.
     *  @param address Starting address 0-32767.
     *  @param length  Number of bytes to fill.
     *  @param value   Byte value 0-255 (default 0).
     *  @return true on success, false if address…address+length is not inside 0x0000-0x7FFF.
     */
    bool fill(uint16_t address, uint16_t length, uint8_t value = 0);

    /** @brief Read the 3-byte Device ID via the reserved slave address 0x7C.
     *
     *  The write phase sends the device address word (0xA0 + A2A1A0 * 2),
     *  then a repeated START and a 3-byte read. Linux i2c-dev may refuse
     *  addresses 0x78-0x7F.
     *
     *  @param idConnection  RegisterConnection (regBytes = 1) bound to I2C address 0x7C.
     *  @param manufacturer  Out: manufacturer ID (0x00A for Fujitsu).
     *  @param density       Out: density code (0x5 for 256 Kbit).
     *  @param product       Out: product ID bits (0x10 for the MB85RC256V).
     *  @param address       7-bit address of the FRAM (0x50-0x57), used to build the
     *                       device address word (default 0x50).
     */
    void read_device_id(RegisterConnection& idConnection, uint16_t* manufacturer,
                        uint8_t* density, uint8_t* product, uint8_t address = 0x50);

private:
    static constexpr uint8_t DEVICE_ID_BASE = 0xA0;
    uint8_t _max_chunk = 30;

    static bool _in_range(uint16_t address, uint32_t length);
};
