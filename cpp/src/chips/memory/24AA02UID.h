#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"

/** @brief 24AA02UID 2K I2C EEPROM with 32-bit unique serial number — minimal interface.
 *
 * Provides read access to the chip's unique 32-bit serial number and basic
 * single-byte read/write access to the user EEPROM. The chip has no
 * configuration registers; it is ready for use immediately after power-on.
 *
 * Memory layout:
 * - 0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)
 * - 0x80-0xF9 — reserved, read-only
 * - 0xFA      — manufacturer code (always 0x29, Microchip)
 * - 0xFB      — device code (always 0x41)
 * - 0xFC-0xFF — 32-bit unique serial number, MSB first
 *
 * Default configuration (no registers to configure):
 * - User EEPROM is read/written as raw bytes (no interpretation)
 * - write_byte() waits for the internal write cycle to finish via
 *   ACK polling before returning (max 5 ms)
 * - All addresses 0x80-0xFF are write-protected; writes are silently
 *   ignored by the chip. Drivers accept the call but the data is not
 *   retained.
 *
 * @param transport Configured I2C transport pointing at the device (address 0x50).
 */
class EEPROM24AA02UIDMinimal {
public:
    EEPROM24AA02UIDMinimal(Transport& transport);

    /** @brief Read the chip's factory-programmed 32-bit unique serial number.
     *  @param buf Destination buffer (4 bytes).
     */
    void read_uid(uint8_t* buf);

    /** @brief Read a single byte from user EEPROM at 0x00-0x7F.
     *  @param address Memory address 0-127.
     *  @return Byte value 0-255.
     */
    uint8_t read_byte(uint8_t address);

    /** @brief Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.
     *
     *  Sends the byte, then ACK-polls the chip until the internal write
     *  cycle completes (max 5 ms). Writes to 0x80-0xFF are accepted by
     *  the device but silently ignored (write-protected region).
     *
     *  @param address Memory address 0-255.
     *  @param value   Byte value 0-255.
     */
    void write_byte(uint8_t address, uint8_t value);

protected:
    static constexpr uint8_t ADDR_UID_BASE   = 0xFC;
    static constexpr uint8_t ADDR_MFR_CODE   = 0xFA;
    static constexpr uint8_t ADDR_DEV_CODE   = 0xFB;
    static constexpr uint8_t WRITE_CYCLE_MS  = 5;

    Transport& _transport;

    void _ack_poll();
};

/** @brief 24AA02UID full interface — extends minimal with multi-byte read/write.
 *
 *  Adds sequential read, raw page write (8-byte page), arbitrary-length
 *  write that automatically crosses page boundaries, and accessors for
 *  the manufacturer and device codes in the upper (read-only) block.
 *
 *  @param transport Configured I2C transport pointing at the device (address 0x50).
 */
class EEPROM24AA02UIDFull : public EEPROM24AA02UIDMinimal {
public:
    EEPROM24AA02UIDFull(Transport& transport);

    /** @brief Sequential read of @p length bytes starting at @p address.
     *
     *  The internal address pointer auto-increments; reads may cross any
     *  boundary and wrap at the end of the 256-byte address space.
     *
     *  @param address Starting address 0-255.
     *  @param buf     Destination buffer (at least @p length bytes).
     *  @param length  Number of bytes to read.
     */
    void read(uint8_t address, uint8_t* buf, uint8_t length);

    /** @brief Write up to 8 bytes within a single 8-byte page.
     *
     *  The caller is responsible for ensuring all bytes lie within the
     *  same page. Bytes that would overflow the page boundary wrap to the
     *  start of the same page (FIFO overwrite) — use write() to handle
     *  boundaries automatically.
     *
     *  @param address Start address within an 8-byte page (0, 8, 16, …).
     *  @param data    Bytes to write (1 to 8 bytes).
     *  @param length  Number of bytes from @p data to write.
     */
    void write_page(uint8_t address, const uint8_t* data, uint8_t length);

    /** @brief Write an arbitrary-length buffer, splitting at 8-byte page boundaries.
     *
     *  Automatically splits the write into page-aligned chunks and waits
     *  for the write cycle of each chunk before continuing.
     *
     *  @param address Starting address 0-255.
     *  @param data    Bytes to write.
     *  @param length  Number of bytes from @p data to write.
     */
    void write(uint8_t address, const uint8_t* data, uint8_t length);

    /** @brief Read the manufacturer code at 0xFA.
     *  @return Manufacturer code; expect 0x29 (Microchip).
     */
    uint8_t read_manufacturer_code();

    /** @brief Read the device code at 0xFB.
     *  @return Device code; expect 0x41 (I2C 2-Kbit EEPROM).
     */
    uint8_t read_device_code();

private:
    static constexpr uint8_t PAGE_SIZE = 8;
};
