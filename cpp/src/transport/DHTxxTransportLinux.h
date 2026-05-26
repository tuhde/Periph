#ifndef DHTXX_TRANSPORT_LINUX_H
#define DHTXX_TRANSPORT_LINUX_H

#ifdef __linux__

#include <stdint.h>
#include <gpiod.h>

class DHTxxTransportLinux {
public:
    DHTxxTransportLinux(unsigned int chip_num, unsigned int line_num);
    ~DHTxxTransportLinux();

    bool read(uint8_t* frame, size_t len);
    void close();

private:
    struct gpiod_chip* _chip;
    struct gpiod_line* _line;
    unsigned int _line_num;

    static constexpr uint32_t T_HOST_LOW = 20000;
    static constexpr uint32_t T_GO = 20;
    static constexpr uint32_t T_THRESHOLD = 40;

    int waitLow(uint32_t timeout_us);
    int waitHigh(uint32_t timeout_us);
};

#endif
