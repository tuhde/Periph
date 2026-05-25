#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

/** @brief UART transport for Linux GCC (wraps POSIX termios / tcdrain).
 *
 * Opens and configures the serial port at construction via open() +
 * tcgetattr/tcsetattr in raw mode. Call close() or let the destructor release
 * the file descriptor when done.
 *
 * For RS-485, the transport first tries kernel RS-485 mode via
 * TIOCSRS485 (SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND). If the driver
 * does not support it and de_pin_num != -1, it falls back to manual GPIO
 * toggling via libgpiod.
 *
 * stop_bits accepts 1 or 2; termios does not support 1.5 stop bits.
 *
 * @param path       Serial device path (e.g. "/dev/ttyS0").
 * @param baudrate   Baud rate; default 9600.
 * @param data_bits  Data bits (5–8); default 8.
 * @param stop_bits  Stop bits (1 or 2); default 1.
 * @param parity     Parity: 'N' none, 'E' even, 'O' odd; default 'N'.
 * @param timeout_ms Read timeout in milliseconds; default 1000.
 * @param de_pin_num GPIO line number for RS-485 DE (active high); -1 disables.
 */
class UARTTransportLinux : public Transport {
public:
    UARTTransportLinux(const char* path,
                       int baudrate    = 9600,
                       int data_bits   = 8,
                       int stop_bits   = 1,
                       char parity     = 'N',
                       int timeout_ms  = 1000,
                       int de_pin_num  = -1);
    ~UARTTransportLinux();

    /** @brief Transmit bytes; in RS-485 mode asserts DE via kernel or GPIO,
     *         transmits, drains the kernel TX buffer via tcdrain(), then
     *         deasserts DE.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override;

    /** @brief Receive @p len bytes; blocks until all bytes arrive or
     *         VTIME-based timeout expires.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override;

    /** @brief Transmit then receive; DE is asserted only during transmit.
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

    /** @brief Release the serial port file descriptor. */
    void close();

private:
    int  _fd;
    int  _de_pin_num;
    bool _rs485_kernel;

    // libgpiod handle (used only in GPIO-fallback RS-485 mode)
    void* _gpiod_chip = nullptr;
    void* _gpiod_line = nullptr;

    void _de_set(int value);
};
#endif // __linux__
