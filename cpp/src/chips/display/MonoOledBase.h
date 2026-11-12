#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief Transport hooks shared by the monochrome page-organised OLED controller drivers (SSD130x style).
 *
 *  Implemented by MonoOledI2CBus (control-byte framing on one I²C connection) and MonoOledSpiBus
 *  (a display connection; D/C low for commands, high for RAM data).
 */
class MonoOledBus {
public:
    virtual ~MonoOledBus() = default;
    /** @brief Send bytes as one command stream. */
    virtual void write_commands(const uint8_t* bytes, size_t len) = 0;
    /** @brief Send RAM data bytes (nullptr = zeros) in chunks. */
    virtual void write_data(const uint8_t* bytes, size_t len) = 0;
    /** @brief Pulse the hardware reset line, if any (no-op for I²C). */
    virtual void hardware_reset() {}
};

/** @brief I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data). */
class MonoOledI2CBus : public MonoOledBus {
public:
    /** @brief Largest write in bytes including the control byte (the 32-byte limit of some I²C layers). */
    static constexpr size_t CHUNK = 32;
    explicit MonoOledI2CBus(Connection* connection = nullptr) : _conn(connection) {}
    void write_commands(const uint8_t* bytes, size_t len) override;
    void write_data(const uint8_t* bytes, size_t len) override;
private:
    Connection* _conn;
    uint8_t     _buf[CHUNK];
};

/** @brief SPI transport on a display connection (4-wire): D/C low for commands, high for RAM data.
 *
 *  Every command byte (including arguments) is sent with D/C low; RAM data goes out in chunks of
 *  SPI_CHUNK bytes with D/C high.
 *
 *  @tparam CONN Display connection class (CS, D/C, RES optional; no BUSY).
 */
template <typename CONN>
class MonoOledSpiBus : public MonoOledBus {
public:
    static constexpr size_t SPI_CHUNK = 128;
    explicit MonoOledSpiBus(CONN& connection) : _conn(connection) {}
    void write_commands(const uint8_t* bytes, size_t len) override {
        for (size_t i = 0; i < len; i++) _conn.sendCommand(bytes[i]);
    }
    void write_data(const uint8_t* bytes, size_t len) override {
        static const uint8_t ZEROS[SPI_CHUNK] = {0};
        while (len > 0) {
            size_t n = len > SPI_CHUNK ? SPI_CHUNK : len;
            _conn.sendData(bytes ? bytes : ZEROS, n);
            if (bytes) bytes += n;
            len -= n;
        }
    }
    void hardware_reset() override { _conn.reset(); }
private:
    CONN& _conn;
};

/** @brief Page-major 1 bpp framebuffer (bit 0 of a byte = top pixel of its page) with drawing primitives.
 *
 *  Works on a caller-owned buffer of `width * height / 8` bytes, so the drivers stay free of heap
 *  allocation.
 */
class MonoFramebuffer {
public:
    /** @brief Wrap @p storage (`width * height / 8` bytes, zeroed by the constructor). */
    MonoFramebuffer(uint8_t* storage, uint16_t width, uint16_t height);

    uint16_t width() const { return _width; }
    uint16_t height() const { return _height; }
    uint8_t  pages() const { return _pages; }
    /** @brief The page-major byte array. */
    uint8_t* data() { return _buf; }
    /** @brief Pointer to the first byte of @p page. */
    const uint8_t* page(uint8_t page) const { return &_buf[static_cast<size_t>(page) * _width]; }
    /** @brief Number of bytes in the buffer. */
    size_t size() const { return static_cast<size_t>(_width) * _pages; }

    /** @brief Zero the buffer. */
    void clear() { fill(false); }
    /** @brief Set (or clear) every pixel. */
    void fill(bool on);
    /** @brief Set or clear one pixel; out-of-range coordinates are ignored. */
    void set_pixel(int16_t x, int16_t y, bool on);
    /** @brief Return a pixel; out-of-range coordinates read as false. */
    bool get_pixel(int16_t x, int16_t y) const;
    /** @brief Bresenham line. */
    void draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, bool on);
    /** @brief Rectangle outline or filled rectangle. */
    void draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, bool on, bool fill);
    /** @brief Copy a column-major bitmap (8 pixels per byte, bit 0 = top); clipped.
     *  @return False (nothing drawn) if @p y is not a multiple of 8.
     */
    bool blit(int16_t x, int16_t y, const uint8_t* columns, size_t len, uint8_t height_pages);

private:
    uint8_t* _buf;
    uint16_t _width;
    uint16_t _height;
    uint8_t  _pages;
};
