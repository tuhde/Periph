#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <thread>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/spi/spidev.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for Linux GCC.
 *
 * Two ways to reach the controller:
 *
 * - **Hardware SPI:** `bus_num` / `device_num` open `/dev/spidevBUS.DEVICE`
 *   (spidev, mode 0). The kernel drives CS# and releases it after every
 *   transfer. kHalfDuplexHW sets the spidev `SPI_3WIRE` mode flag (the SPI
 *   controller must support it); kHalfDuplexPin reads full-duplex.
 * - **Software SPI:** a SoftSPI plus an OutputPin for CS# (driven here).
 *   kHalfDuplexHW is not available (valid() false); kHalfDuplexPin needs a
 *   SoftSPI with a MISO pin.
 *
 * D/C#, RES# and BUSY are OutputPin/InputPin objects (e.g. OutputPinLinux /
 * InputPinLinux over libgpiod v2). Errors opening or configuring spidev throw
 * std::runtime_error, like SPIConnectionLinux.
 */
class DisplayConnectionLinux : public DisplayConnectionBase {
public:
    /** @brief Hardware SPI constructor.
     *  @param bus_num      SPI bus number.
     *  @param device_num   Chip-select line on the bus (CS# owned by the kernel).
     *  @param dc           OutputPin for D/C#.
     *  @param res          OutputPin for RES#, or nullptr.
     *  @param busy         InputPin for BUSY, or nullptr.
     *  @param wireMode     A DisplayWireMode value (default kSimplex).
     *  @param max_speed_hz SPI clock in Hz (default 4 000 000).
     *  @param resetPulseUs RES# low time in microseconds (default 200).
     *  @param resetSettleMs Wait after RES# is released in milliseconds (default 10).
     *  @param enPin        Optional OutputPin for hardware enable/power control.
     *  @param enActiveHigh True if the EN pin is active-high (default).
     */
    DisplayConnectionLinux(int bus_num, int device_num, OutputPin* dc, OutputPin* res, InputPin* busy,
                           DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                           uint32_t max_speed_hz = 4000000,
                           uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                           OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(nullptr, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _soft(nullptr), _fd(-1), _speed_hz(max_speed_hz) {
        char path[40];
        snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
        _fd = open(path, O_RDWR);
        if (_fd < 0)
            throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
        uint8_t mode = 0;
        if (wireMode == DisplayWireMode::kHalfDuplexHW) mode |= SPI_3WIRE;
        if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0) {
            int e = errno;
            close(_fd);
            _fd = -1;
            throw std::runtime_error(std::string("SPI_IOC_WR_MODE on ") + path + ": " + strerror(e));
        }
        if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &max_speed_hz) < 0) {
            int e = errno;
            close(_fd);
            _fd = -1;
            throw std::runtime_error(std::string("SPI_IOC_WR_MAX_SPEED_HZ on ") + path + ": " + strerror(e));
        }
    }

    /** @brief Software SPI constructor (SoftSPI + CS# OutputPin driven here). */
    DisplayConnectionLinux(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                           DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                           uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                           OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _soft(&soft), _fd(-1), _speed_hz(0) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

    ~DisplayConnectionLinux() override {
        if (_fd >= 0) close(_fd);
    }

    DisplayConnectionLinux(const DisplayConnectionLinux&) = delete;
    DisplayConnectionLinux& operator=(const DisplayConnectionLinux&) = delete;

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        if (_soft != nullptr) {
            csLow();
            _soft->write(data, len);
            csHigh();
            return;
        }
        struct spi_ioc_transfer tr = {};
        tr.tx_buf        = reinterpret_cast<uintptr_t>(data);
        tr.len           = static_cast<uint32_t>(len);
        tr.speed_hz      = _speed_hz;
        tr.bits_per_word = 8;
        if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0)
            throw std::runtime_error(std::string("SPI write: ") + strerror(errno));
    }

    void spiRead(uint8_t* buf, size_t len) override {
        if (_soft != nullptr) {
            csLow();
            _soft->read(buf, len);
            csHigh();
            return;
        }
        // rx-only transfer: full-duplex masters clock zeros on MOSI, a 3-wire
        // master turns the line around to receive.
        struct spi_ioc_transfer tr = {};
        tr.rx_buf        = reinterpret_cast<uintptr_t>(buf);
        tr.len           = static_cast<uint32_t>(len);
        tr.speed_hz      = _speed_hz;
        tr.bits_per_word = 8;
        if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0)
            throw std::runtime_error(std::string("SPI read: ") + strerror(errno));
    }

    void delayUs(uint32_t us) override {
        std::this_thread::sleep_for(std::chrono::microseconds(us));
    }
    void delayMs(uint32_t ms) override {
        std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    }
    uint32_t nowMs() override {
        using namespace std::chrono;
        return static_cast<uint32_t>(
            duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
    }

private:
    SoftSPI* _soft;
    int      _fd;
    uint32_t _speed_hz;
};
#endif // __linux__
