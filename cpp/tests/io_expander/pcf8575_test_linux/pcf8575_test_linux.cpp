#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/i2c-dev.h>
#include "PCF8575.h"

class LinuxI2CTransport {
public:
    LinuxI2CTransport(int bus_num, uint8_t addr) {
        char path[32];
        snprintf(path, sizeof(path), "/dev/i2c-%d", bus_num);
        fd = open(path, O_RDWR);
        this->addr = addr;
    }
    void write(const uint8_t* data, size_t len) {
        ::write(fd, data, len);
    }
    void read(uint8_t* buf, size_t len) {
        ::ioctl(fd, I2C_SLAVE, addr);
        ::read(fd, buf, len);
    }
    ~LinuxI2CTransport() { close(fd); }
private:
    int fd;
    uint8_t addr;
};

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s\n", label); failed++; }
}

int main() {
    int bus_num = std::getenv("I2C_BUS") ? std::atoi(std::getenv("I2C_BUS")) : 1;
    uint8_t addr = std::getenv("I2C_ADDR") ? std::atoi(std::getenv("I2C_ADDR")) : 0x20;

    LinuxI2CTransport transport(bus_num, addr);
    PCF8575Minimal chip(transport);

    check_eq("init_shadow_0", chip._shadow[0], 0xFF);
    check_eq("init_shadow_1", chip._shadow[1], 0xFF);

    uint8_t port0 = chip.read_port(0);
    uint8_t port1 = chip.read_port(1);
    check_true("read_port_0_range", port0 <= 0xFF);
    check_true("read_port_1_range", port1 <= 0xFF);

    chip.write_port(0, 0xAA);
    check_eq("write_port_0_shadow", chip._shadow[0], 0xAA);
    chip.write_port(1, 0x55);
    check_eq("write_port_1_shadow", chip._shadow[1], 0x55);
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);
    p0.mode(OUTPUT);
    p0.low();
    check_eq("pin_low_shadow",  chip._shadow[0] & 0x01, 0x00);
    p0.high();
    check_eq("pin_high_shadow", chip._shadow[0] & 0x01, 0x01);
    p0.toggle();
    check_eq("pin_toggle_shadow", chip._shadow[0] & 0x01, 0x00);
    p0.write(HIGH);
    check_eq("pin_write_high_shadow", chip._shadow[0] & 0x01, 0x01);

    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed;
}