#pragma once
#include "InputPin.h"
#include "OutputPin.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>

/** @brief Abstract connection interface shared by all chip drivers.
 *
 * A connection instance represents one device on a bus, plus its optional
 * INT pin (edge notifications from the chip's INT line), EN pin (hardware
 * enable/power control), and a software enable gate. Subclasses wrap a
 * platform-specific bus (Wire, SPIClass, /dev/i2c-N, etc.) and a device
 * address or CS pin, implementing the protected _write/_read/_write_read
 * hooks; this base class gates every call through the enabled flag so chip
 * drivers never need to check it themselves.
 */
class Connection {
public:
    virtual ~Connection() = default;

    /** @brief Construct with optional INT pin and EN pin.
     *  @param intPin Optional InputPin for INT-line delivery.
     *  @param enPin  Optional OutputPin for hardware enable/power control.
     */
    explicit Connection(InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : _intPin(intPin), _enPin(enPin), _enabled(true) {}

    /** @brief Resume bus access; drives the hardware EN pin high if wired. */
    void enable() {
        _enabled = true;
        if (_enPin) _enPin->set(true);
    }

    /** @brief Gate all subsequent bus reads and writes; drives EN pin low if wired.
     *
     *  Reads silently return zero bytes; writes silently become no-ops.
     */
    void disable() {
        _enabled = false;
        if (_enPin) _enPin->set(false);
    }

    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief The INT-line InputPin, or nullptr if none is wired. */
    InputPin* intPin() const { return _intPin; }

    /** @brief The EN-pin OutputPin, or nullptr if none is wired. */
    OutputPin* enPin() const { return _enPin; }

    /** @brief Send bytes to the device, unless disabled.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        _write(data, len);
    }

    /** @brief Read bytes from the device, unless disabled.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) {
        if (!_enabled) { memset(buf, 0, len); return; }
        _read(buf, len);
    }

    /** @brief Write then read without releasing the bus between phases, unless disabled.
     *
     *  Sends @p data_len bytes, then reads @p buf_len bytes within one
     *  continuous bus transaction — the bus is not released between the
     *  write and read phases.
     *
     *  @param data     Command/address bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) {
        if (!_enabled) { memset(buf, 0, buf_len); return; }
        _write_read(data, data_len, buf, buf_len);
    }

    /** @brief Number of bytes available to read() without blocking.
     *
     *  Default implementation returns SIZE_MAX ("unknown / always assume
     *  data is ready"), appropriate for connections that never block
     *  indefinitely (I2C, SPI always return a byte immediately, real or
     *  idle filler) and for any connection that doesn't track this.
     *  Buffered/blocking connections such as UART override this to report
     *  the real RX buffer/FIFO fill level, so callers that need to avoid
     *  blocking on read() (e.g. a driver polling a streaming protocol)
     *  can check available() > 0 before calling read().
     *  @return Byte count currently queued, or SIZE_MAX if not tracked.
     */
    virtual size_t available() const { return SIZE_MAX; }

protected:
    /** @brief Subclass hook: send bytes to the device. */
    virtual void _write(const uint8_t* data, size_t len) = 0;
    /** @brief Subclass hook: read bytes from the device. */
    virtual void _read(uint8_t* buf, size_t len) = 0;
    /** @brief Subclass hook: write then read without releasing the bus. */
    virtual void _write_read(const uint8_t* data, size_t data_len,
                              uint8_t* buf, size_t buf_len) = 0;

private:
    InputPin*  _intPin;
    OutputPin* _enPin;
    bool       _enabled;
};
