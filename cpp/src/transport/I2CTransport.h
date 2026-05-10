#pragma once
#include <Wire.h>
#include "Transport.h"

/** @brief I²C transport for Arduino (wraps TwoWire / Wire).
 *
 * One instance represents one device; the 7-bit address is fixed at
 * construction time.
 *
 * @param bus  TwoWire instance to use (typically the global ::Wire).
 * @param addr 7-bit I²C device address.
 */
class I2CTransport : public Transport {
public:
    I2CTransport(TwoWire& bus, uint8_t addr) : _bus(bus), _addr(addr) {}

    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override;

    /** @brief Read bytes from the device.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override;

    /** @brief Write then read using a repeated start (no STOP between phases).
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    TwoWire& _bus;
    uint8_t  _addr;
};
