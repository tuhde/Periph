#ifndef DHTXX_TRANSPORT_H
#define DHTXX_TRANSPORT_H

#include <stdint.h>

class DHTxxTransport {
public:
    explicit DHTxxTransport(int pin);

    bool read(uint8_t* frame, size_t len);

    void close();

private:
    int _pin;
    static constexpr uint32_t T_HOST_LOW = 20000;
    static constexpr uint32_t T_GO = 20;
    static constexpr uint32_t T_THRESHOLD = 40;

    int waitLow(uint32_t timeout_us);
    int waitHigh(uint32_t timeout_us);
};

#endif
