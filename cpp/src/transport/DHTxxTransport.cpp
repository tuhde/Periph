#include "DHTxxTransport.h"
#include <Arduino.h>

DHTxxTransport::DHTxxTransport(int pin) : _pin(pin) {
    pinMode(_pin, INPUT);
}

bool DHTxxTransport::read(uint8_t* frame, size_t len) {
    if (len < 5) {
        return false;
    }

    pinMode(_pin, OUTPUT);
    digitalWrite(_pin, LOW);
    delayMicroseconds(T_HOST_LOW);
    pinMode(_pin, INPUT);
    delayMicroseconds(T_GO);

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

void DHTxxTransport::close() {
    pinMode(_pin, INPUT);
}

int DHTxxTransport::waitLow(uint32_t timeout_us) {
    uint32_t start = micros();
    while (digitalRead(_pin) == HIGH) {
        if ((micros() - start) > timeout_us) {
            return -1;
        }
    }
    return (micros() - start);
}

int DHTxxTransport::waitHigh(uint32_t timeout_us) {
    uint32_t start = micros();
    while (digitalRead(_pin) == LOW) {
        if ((micros() - start) > timeout_us) {
            return -1;
        }
    }
    return (micros() - start);
}
