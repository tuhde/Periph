#pragma once
#include <Arduino.h>
#include "Connection.h"

/** @brief UART connection for Arduino (wraps HardwareSerial).
 *
 * The caller constructs and configures the HardwareSerial instance before
 * passing it to this connection. When de_pin is not -1 the connection operates
 * in RS-485 mode: DE is driven high before each transmit, the hardware TX
 * shift register is drained via flush(), then DE is driven low.
 *
 * @param serial Reference to a configured HardwareSerial port (e.g. Serial1).
 * @param de_pin DE pin number for RS-485 direction control; -1 disables RS-485.
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class UARTConnection : public Connection {
public:
    UARTConnection(HardwareSerial& serial, int de_pin = -1,
                   InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _serial(serial), _de_pin(de_pin)
    {
        if (_de_pin >= 0) {
            pinMode(_de_pin, OUTPUT);
            digitalWrite(_de_pin, LOW);
        }
    }

    /** @brief Number of bytes currently buffered in the hardware/driver RX
     *         FIFO, available to read() without blocking.
     *  @return Byte count currently queued.
     */
    size_t available() const override { return _serial.available(); }

protected:
    /** @brief Transmit bytes; in RS-485 mode assert DE, transmit, drain TX
     *         shift register, then deassert DE.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override;

    /** @brief Receive @p len bytes; blocks until all bytes arrive or the
     *         hardware serial timeout expires.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override;

    /** @brief Transmit bytes then receive @p buf_len bytes.
     *
     *  In RS-485 mode DE is asserted only during the transmit phase.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override;

private:
    HardwareSerial& _serial;
    int             _de_pin;
};
