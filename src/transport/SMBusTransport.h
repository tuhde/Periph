#pragma once
#include <Wire.h>
#include "Transport.h"

/** @brief SMBus transport for Arduino (wraps TwoWire with address validation and PEC).
 *
 * Enforces the valid 7-bit SMBus address range and, when pec=true, appends a
 * CRC-8 byte to writes and verifies it on reads. Use valid() after each read
 * or write_read to check whether PEC matched.
 *
 * @param bus  TwoWire instance to use (typically the global ::Wire).
 * @param addr 7-bit device address (0x08–0x77); sets valid() = false if out of range.
 * @param pec  Enable Packet Error Code (CRC-8) checking (default false).
 */
class SMBusTransport : public Transport {
public:
    SMBusTransport(TwoWire& bus, uint8_t addr, bool pec = false);

    /** @brief Send bytes to the device, appending a PEC byte if enabled. */
    void write(const uint8_t* data, size_t len) override;

    /** @brief Read bytes from the device, verifying the PEC byte if enabled.
     *
     *  Reads len+1 bytes when PEC is enabled; the trailing byte is the CRC.
     *  Call valid() after to check whether PEC matched.
     */
    void read(uint8_t* buf, size_t len) override;

    /** @brief Write then read with PEC on the read phase.
     *
     *  Uses endTransmission(false) for a repeated start. PEC covers the full
     *  transaction (write address + data + read address + data).
     *  Call valid() after to check whether PEC matched.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

    /** @brief Returns false if the last read or write_read produced a PEC mismatch. */
    bool valid() const { return _valid; }

private:
    TwoWire& _bus;
    uint8_t  _addr;
    bool     _pec;
    bool     _valid = true;

    static uint8_t _crc8(const uint8_t* data, size_t len, uint8_t crc = 0);
};
