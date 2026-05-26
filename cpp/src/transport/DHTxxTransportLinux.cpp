#ifdef __linux__

#include "DHTxxTransportLinux.h"
#include <stdio.h>
#include <unistd.h>

DHTxxTransportLinux::DHTxxTransportLinux(unsigned int chip_num, unsigned int line_num)
    : _chip(nullptr), _line(nullptr), _line_num(line_num) {
    _chip = gpiod_chip_open_by_number(chip_num);
    if (!_chip) {
        return;
    }
    _line = gpiod_chip_get_line(_chip, _line_num);
}

DHTxxTransportLinux::~DHTxxTransportLinux() {
    close();
}

bool DHTxxTransportLinux::read(uint8_t* frame, size_t len) {
    if (!_chip || !_line || len < 5) {
        return false;
    }

    if (gpiod_line_request_output(_line, "dhtxx", 0) < 0) {
        return false;
    }

    gpiod_line_set_value(_line, 0);
    usleep(T_HOST_LOW);

    gpiod_line_request_input(_line, "dhtxx");
    usleep(T_GO);

    if (waitLow(1000) < 0) {
        return false;
    }
    if (waitHigh(1000) < 0) {
        return false;
    }

    uint32_t bits = 0;
    for (int i = 0; i < 40; i++) {
        if (waitLow(1000) < 0) {
            return false;
        }
        int width = waitHigh(1000);
        if (width < 0) {
            return false;
        }
        bits = (bits << 1) | (width >= (int)T_THRESHOLD ? 1 : 0);
    }

    frame[0] = (bits >> 32) & 0xFF;
    frame[1] = (bits >> 24) & 0xFF;
    frame[2] = (bits >> 16) & 0xFF;
    frame[3] = (bits >> 8) & 0xFF;
    frame[4] = bits & 0xFF;

    return true;
}

void DHTxxTransportLinux::close() {
    if (_line) {
        gpiod_line_release(_line);
        _line = nullptr;
    }
    if (_chip) {
        gpiod_chip_close(_chip);
        _chip = nullptr;
    }
}

int DHTxxTransportLinux::waitLow(uint32_t timeout_us) {
    struct timespec start, now;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (gpiod_line_get_value(_line) == 1) {
        clock_gettime(CLOCK_MONOTONIC, &now);
        if ((now.tv_sec - start.tv_sec) * 1000000 + (now.tv_nsec - start.tv_nsec) / 1000 > (long)timeout_us) {
            return -1;
        }
    }
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - start.tv_sec) * 1000000 + (now.tv_nsec - start.tv_nsec) / 1000;
}

int DHTxxTransportLinux::waitHigh(uint32_t timeout_us) {
    struct timespec start, now;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (gpiod_line_get_value(_line) == 0) {
        clock_gettime(CLOCK_MONOTONIC, &now);
        if ((now.tv_sec - start.tv_sec) * 1000000 + (now.tv_nsec - start.tv_nsec) / 1000 > (long)timeout_us) {
            return -1;
        }
    }
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - start.tv_sec) * 1000000 + (now.tv_nsec - start.tv_nsec) / 1000;
}

#endif
