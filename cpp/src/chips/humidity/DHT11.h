#ifndef DHT11_H
#define DHT11_H

#include <stdint.h>
#include "DHTxxTransport.h"

class DHT11Minimal {
public:
    explicit DHT11Minimal(DHTxxTransport& transport);

    void read(float& temperature, float& humidity);

private:
    DHTxxTransport& _transport;
};

class DHT11Full : public DHT11Minimal {
public:
    explicit DHT11Full(DHTxxTransport& transport);

    float readTemperature();
    float readHumidity();
    void readRetry(float& temperature, float& humidity, int maxRetries = 3);
    void readRaw(uint8_t* frame, size_t len);

private:
    DHTxxTransport& _transport;
};

#endif
