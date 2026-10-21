#include "23X1024.h"
#include <stdlib.h>
#include <string.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint32_t SRAM23X1024Minimal::SIZE;
constexpr uint8_t SRAM23X1024Minimal::CMD_READ;
constexpr uint8_t SRAM23X1024Minimal::CMD_WRITE;
constexpr uint8_t SRAM23X1024Minimal::CMD_RDMR;
constexpr uint8_t SRAM23X1024Minimal::CMD_WRMR;
constexpr uint8_t SRAM23X1024Minimal::CMD_RSTIO;
constexpr uint8_t SRAM23X1024Minimal::MODE_MASK;
constexpr uint8_t SRAM23X1024Full::MODE_BYTE;
constexpr uint8_t SRAM23X1024Full::MODE_PAGE;
constexpr uint8_t SRAM23X1024Full::MODE_SEQUENTIAL;
constexpr size_t SRAM23X1024Full::PAGE_SIZE;

// Largest frame: instruction + 3 address bytes + max_chunk data bytes.
static constexpr size_t MAX_CHUNK_LIMIT = 256;

SRAM23X1024Minimal::SRAM23X1024Minimal(Connection& connection) : _connection(connection) {
    _init();
}

void SRAM23X1024Minimal::_init() {
    _write_mode(0x40);  // Sequential (power-on default, written for idempotency)
    if (_read_mode() != 0x40) abort();
}

uint8_t SRAM23X1024Minimal::_read_mode() {
    const uint8_t cmd = CMD_RDMR;
    uint8_t mode = 0;
    _connection.write_read(&cmd, 1, &mode, 1);
    return mode;
}

void SRAM23X1024Minimal::_write_mode(uint8_t mode) {
    const uint8_t frame[2] = {CMD_WRMR, mode};
    _connection.write(frame, 2);
}

void SRAM23X1024Minimal::_read_raw(uint32_t address, uint8_t* buf, size_t length) {
    const uint8_t cmd[4] = {CMD_READ, static_cast<uint8_t>(address >> 16),
                            static_cast<uint8_t>(address >> 8), static_cast<uint8_t>(address)};
    _connection.write_read(cmd, 4, buf, length);
}

void SRAM23X1024Minimal::_write_raw(uint32_t address, const uint8_t* data, size_t length) {
    // Stack frame sized for the largest chunk the Full class allows.
    uint8_t frame[4 + MAX_CHUNK_LIMIT];
    frame[0] = CMD_WRITE;
    frame[1] = static_cast<uint8_t>(address >> 16);
    frame[2] = static_cast<uint8_t>(address >> 8);
    frame[3] = static_cast<uint8_t>(address);
    memcpy(frame + 4, data, length);
    _connection.write(frame, 4 + length);
}

uint8_t SRAM23X1024Minimal::read_byte(uint32_t address) {
    if (address >= SIZE) abort();
    uint8_t value = 0;
    _read_raw(address, &value, 1);
    return value;
}

void SRAM23X1024Minimal::write_byte(uint32_t address, uint8_t value) {
    if (address >= SIZE) abort();
    _write_raw(address, &value, 1);
}

SRAM23X1024Full::SRAM23X1024Full(Connection& connection) : SRAM23X1024Minimal(connection) {}

size_t SRAM23X1024Full::_chunk_len(uint32_t address, size_t remaining) const {
    size_t n = remaining < _max_chunk ? remaining : _max_chunk;
    if (_mode == MODE_BYTE) return 1;
    if (_mode == MODE_PAGE) {
        size_t to_page_end = PAGE_SIZE - (address % PAGE_SIZE);
        if (n > to_page_end) n = to_page_end;
    }
    return n;
}

void SRAM23X1024Full::read(uint32_t address, uint8_t* buf, size_t length) {
    if (length > SIZE || address > SIZE - length) abort();
    while (length > 0) {
        size_t n = _chunk_len(address, length);
        _read_raw(address, buf, n);
        address += n; buf += n; length -= n;
    }
}

void SRAM23X1024Full::write(uint32_t address, const uint8_t* data, size_t length) {
    if (length > SIZE || address > SIZE - length) abort();
    while (length > 0) {
        size_t n = _chunk_len(address, length);
        _write_raw(address, data, n);
        address += n; data += n; length -= n;
    }
}

void SRAM23X1024Full::fill(uint32_t address, size_t length, uint8_t value) {
    if (length > SIZE || address > SIZE - length) abort();
    uint8_t block[PAGE_SIZE];
    memset(block, value, sizeof(block));
    while (length > 0) {
        size_t n = _chunk_len(address, length);
        if (n > sizeof(block)) n = sizeof(block);
        _write_raw(address, block, n);
        address += n; length -= n;
    }
}

void SRAM23X1024Full::set_mode(uint8_t mode) {
    if (mode != MODE_BYTE && mode != MODE_PAGE && mode != MODE_SEQUENTIAL) abort();
    _write_mode(mode);
    if (_read_mode() != mode) abort();
    _mode = mode;
}

uint8_t SRAM23X1024Full::get_mode() {
    return _read_mode() & MODE_MASK;
}

void SRAM23X1024Full::reset_io() {
    _connection.write(&CMD_RSTIO, 1);
    _mode = get_mode();
}

void SRAM23X1024Full::set_max_chunk(size_t n) {
    if (n < 1 || n > MAX_CHUNK_LIMIT) abort();
    _max_chunk = n;
}
