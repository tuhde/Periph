#ifdef __linux__
#include "UARTTransportLinux.h"
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <linux/serial.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

static speed_t baud_to_speed(int baud) {
    switch (baud) {
        case 50:      return B50;
        case 75:      return B75;
        case 110:     return B110;
        case 134:     return B134;
        case 150:     return B150;
        case 200:     return B200;
        case 300:     return B300;
        case 600:     return B600;
        case 1200:    return B1200;
        case 1800:    return B1800;
        case 2400:    return B2400;
        case 4800:    return B4800;
        case 9600:    return B9600;
        case 19200:   return B19200;
        case 38400:   return B38400;
        case 57600:   return B57600;
        case 115200:  return B115200;
        case 230400:  return B230400;
        case 460800:  return B460800;
        case 921600:  return B921600;
        default:
            throw std::runtime_error("Unsupported baud rate: " + std::to_string(baud));
    }
}

UARTTransportLinux::UARTTransportLinux(const char* path,
                                       int baudrate, int data_bits, int stop_bits,
                                       char parity, int timeout_ms, int de_pin_num)
    : _fd(-1), _de_pin_num(de_pin_num), _rs485_kernel(false)
{
    _fd = open(path, O_RDWR | O_NOCTTY);
    if (_fd < 0)
        throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));

    struct termios tty;
    if (tcgetattr(_fd, &tty) < 0) {
        ::close(_fd);
        throw std::runtime_error(std::string("tcgetattr: ") + strerror(errno));
    }

    speed_t speed = baud_to_speed(baudrate);
    cfsetispeed(&tty, speed);
    cfsetospeed(&tty, speed);
    cfmakeraw(&tty);

    tty.c_cflag &= ~CSIZE;
    switch (data_bits) {
        case 5: tty.c_cflag |= CS5; break;
        case 6: tty.c_cflag |= CS6; break;
        case 7: tty.c_cflag |= CS7; break;
        default: tty.c_cflag |= CS8; break;
    }

    if (stop_bits == 2) tty.c_cflag |= CSTOPB;
    else                tty.c_cflag &= ~CSTOPB;

    tty.c_cflag &= ~(PARENB | PARODD);
    if (parity == 'E') { tty.c_cflag |= PARENB; }
    else if (parity == 'O') { tty.c_cflag |= PARENB | PARODD; }

    tty.c_cflag |= CREAD | CLOCAL;

    // VTIME is in units of 0.1 s; VMIN=0 means pure timeout-based reads.
    tty.c_cc[VMIN]  = 0;
    tty.c_cc[VTIME] = static_cast<cc_t>((timeout_ms + 99) / 100);

    if (tcsetattr(_fd, TCSANOW, &tty) < 0) {
        ::close(_fd);
        throw std::runtime_error(std::string("tcsetattr: ") + strerror(errno));
    }

    if (de_pin_num >= 0) {
        struct serial_rs485 rs485 = {};
        rs485.flags = SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND;
        if (ioctl(_fd, TIOCSRS485, &rs485) == 0) {
            _rs485_kernel = true;
        } else {
            // libgpiod fallback
            try {
#ifdef HAVE_LIBGPIOD
                auto* chip = gpiod_chip_open_by_number(0);
                if (!chip) throw std::runtime_error("gpiod_chip_open");
                auto* line = gpiod_chip_get_line(chip, de_pin_num);
                if (!line) { gpiod_chip_close(chip); throw std::runtime_error("gpiod_chip_get_line"); }
                if (gpiod_line_request_output(line, "uart_de", 0) < 0) {
                    gpiod_chip_close(chip);
                    throw std::runtime_error("gpiod_line_request_output");
                }
                _gpiod_chip = chip;
                _gpiod_line = line;
#endif
            } catch (...) {
                // If GPIO is unavailable ignore RS-485 DE toggling.
            }
        }
    }
}

UARTTransportLinux::~UARTTransportLinux() {
    close();
}

void UARTTransportLinux::_de_set(int value) {
#ifdef HAVE_LIBGPIOD
    if (_gpiod_line)
        gpiod_line_set_value(static_cast<struct gpiod_line*>(_gpiod_line), value);
#endif
}

void UARTTransportLinux::write(const uint8_t* data, size_t len) {
    if (!_rs485_kernel)
        _de_set(1);
    ssize_t n = ::write(_fd, data, len);
    if (n < 0 || static_cast<size_t>(n) != len)
        throw std::runtime_error(std::string("UART write: ") + strerror(errno));
    tcdrain(_fd);
    if (!_rs485_kernel)
        _de_set(0);
}

void UARTTransportLinux::read(uint8_t* buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = ::read(_fd, buf + got, len - got);
        if (n <= 0)
            throw std::runtime_error("UART read timeout");
        got += static_cast<size_t>(n);
    }
}

size_t UARTTransportLinux::available() const {
    int n = 0;
    if (ioctl(_fd, FIONREAD, &n) < 0 || n < 0)
        return 0;
    return static_cast<size_t>(n);
}

void UARTTransportLinux::write_read(const uint8_t* data, size_t data_len,
                                     uint8_t* buf, size_t buf_len) {
    write(data, data_len);
    read(buf, buf_len);
}

void UARTTransportLinux::close() {
    if (_fd >= 0) {
        ::close(_fd);
        _fd = -1;
    }
#ifdef HAVE_LIBGPIOD
    if (_gpiod_line) {
        gpiod_line_release(static_cast<struct gpiod_line*>(_gpiod_line));
        _gpiod_line = nullptr;
    }
    if (_gpiod_chip) {
        gpiod_chip_close(static_cast<struct gpiod_chip*>(_gpiod_chip));
        _gpiod_chip = nullptr;
    }
#endif
}
#endif // __linux__
