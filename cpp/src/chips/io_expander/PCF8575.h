#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

class PCF8575Minimal {
public:
    class IOExpanderPin {
    public:
        IOExpanderPin(PCF8575Minimal& chip, uint8_t n);
        void mode(uint8_t m);
        void write(uint8_t v);
        uint8_t read();
        void high()   { write(HIGH); }
        void low()    { write(LOW); }
        void toggle() { write(read() ^ 1); }
    protected:
        PCF8575Minimal& _chip;
        uint8_t         _n;
    };

    PCF8575Minimal(Transport& transport, uint8_t addr = 0x20);
    IOExpanderPin pin(uint8_t n);
    uint8_t read_port(uint8_t port);
    void write_port(uint8_t port, uint8_t mask);
    uint8_t _shadow[2];

protected:
    Transport& _transport;
    uint8_t    _addr;

    void    _write_both();
    uint8_t _read_port(uint8_t port);
    void    _set_pin(uint8_t n, uint8_t value);
};

class PCF8575Full : public PCF8575Minimal {
public:
    class IOExpanderPin : public PCF8575Minimal::IOExpanderPin {
    public:
        IOExpanderPin(PCF8575Full& chip, uint8_t n);
        void attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode);
        void detachInterrupt();
    private:
        PCF8575Full& _full_chip;
        void (*_handler)(IOExpanderPin*) = nullptr;
        uint8_t _irq_mode = 0;
        uint8_t _last_state = 0xFF;
        friend class PCF8575Full;
    };

    PCF8575Full(Transport& transport, uint8_t addr = 0x20);
    IOExpanderPin pin(uint8_t n);
    void configure_interrupt(int int_gpio_pin, void (*callback)(uint8_t));
    uint16_t clear_interrupt();

private:
    uint8_t  _prev[2] = {0xFF, 0xFF};
    void   (*_callback)(uint8_t) = nullptr;

    static void _dispatch(PCF8575Full* chip, uint8_t changed);
};