#include "24AA02UID.h"

#ifdef __linux__
#include <unistd.h>
static void _delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(CONFIG_ZEPHYR)
#include <zephyr/kernel.h>
static void _delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#else
#include <Arduino.h>
static void _delay_ms(unsigned ms) { delay(ms); }
#endif

// --- Minimal ---

EEPROM24AA02UIDMinimal::EEPROM24AA02UIDMinimal(Transport& transport)
    : _transport(transport) {}

void EEPROM24AA02UIDMinimal::read_uid(uint8_t* buf) {
    uint8_t reg = ADDR_UID_BASE;
    _transport.write_read(&reg, 1, buf, 4);
}

uint8_t EEPROM24AA02UIDMinimal::read_byte(uint8_t address) {
    uint8_t reg = address;
    uint8_t val = 0;
    _transport.write_read(&reg, 1, &val, 1);
    return val;
}

void EEPROM24AA02UIDMinimal::write_byte(uint8_t address, uint8_t value) {
    uint8_t buf[2] = { address, value };
    _transport.write(buf, 2);
    _ack_poll();
}

void EEPROM24AA02UIDMinimal::_ack_poll() {
    // The C++ Transport interface does not propagate ACK/NACK status
    // back to the caller. Wait the worst-case write-cycle time so the
    // next operation starts after the chip has finished.
    _delay_ms(WRITE_CYCLE_MS);
}

// --- Full ---

EEPROM24AA02UIDFull::EEPROM24AA02UIDFull(Transport& transport)
    : EEPROM24AA02UIDMinimal(transport) {}

void EEPROM24AA02UIDFull::read(uint8_t address, uint8_t* buf, uint8_t length) {
    uint8_t reg = address;
    _transport.write_read(&reg, 1, buf, length);
}

void EEPROM24AA02UIDFull::write_page(uint8_t address, const uint8_t* data, uint8_t length) {
    if (length == 0) return;
    // Layout: [address, byte0, byte1, …, byteN-1]
    uint8_t buf[1 + PAGE_SIZE];
    buf[0] = address;
    for (uint8_t i = 0; i < length; i++) buf[1 + i] = data[i];
    _transport.write(buf, 1 + length);
    _ack_poll();
}

void EEPROM24AA02UIDFull::write(uint8_t address, const uint8_t* data, uint8_t length) {
    uint8_t offset = 0;
    uint8_t remaining = length;
    uint8_t current = address;
    while (remaining > 0) {
        uint8_t page_offset = current & (PAGE_SIZE - 1);
        uint8_t chunk = PAGE_SIZE - page_offset;
        if (chunk > remaining) chunk = remaining;
        write_page(current, data + offset, chunk);
        offset += chunk;
        current += chunk;
        remaining -= chunk;
    }
}

uint8_t EEPROM24AA02UIDFull::read_manufacturer_code() {
    return read_byte(ADDR_MFR_CODE);
}

uint8_t EEPROM24AA02UIDFull::read_device_code() {
    return read_byte(ADDR_DEV_CODE);
}
