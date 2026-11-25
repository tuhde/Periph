// Unit test: Linux I2C / SMBus / NeoPixel connections report short transfers and failures.
//
// A socketpair stands in for the bus device: the connection adopts one end, the test controls the
// other. A short read is simulated by sending fewer bytes than requested on a non-blocking
// descriptor; a failed write by closing the peer (EPIPE); ioctl-based paths (I2C_RDWR,
// SPI_IOC_MESSAGE) fail on a socket with ENOTTY.
#ifdef __linux__
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <csignal>
#include <stdexcept>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include "I2CConnectionLinux.h"
#include "NeoPixelConnectionLinux.h"
#include "SMBusConnectionLinux.h"

static int passed = 0, failed = 0;

static void check(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

struct Pair {
    int dev;    // adopted by the connection (non-blocking)
    int peer;   // driven by the test
    Pair() {
        int sv[2];
        socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
        dev = sv[0];
        peer = sv[1];
        fcntl(dev, F_SETFL, fcntl(dev, F_GETFL) | O_NONBLOCK);
    }
    void closePeer() { if (peer >= 0) { close(peer); peer = -1; } }
    ~Pair() { closePeer(); }   // dev is closed by the connection
};

static uint8_t crc8(const uint8_t* d, size_t n, uint8_t crc = 0) {
    for (size_t i = 0; i < n; i++) {
        crc ^= d[i];
        for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
    return crc;
}

static bool allEq(const uint8_t* b, size_t n, uint8_t v) {
    for (size_t i = 0; i < n; i++) if (b[i] != v) return false;
    return true;
}

int main() {
    signal(SIGPIPE, SIG_IGN);
    const uint8_t addr = 0x48;

    // ---- I2C ----
    {
        Pair p;
        I2CConnectionLinux c(I2CConnectionLinux::AdoptFd{}, p.dev, addr);
        const uint8_t in[4] = {1, 2, 3, 4};
        (void)!write(p.peer, in, 4);
        uint8_t buf[4] = {0xAA, 0xAA, 0xAA, 0xAA};
        c.read(buf, 4);
        check(c.ok() && memcmp(buf, in, 4) == 0, "I2C full read: ok() true, data intact");

        (void)!write(p.peer, in, 2);
        memset(buf, 0xAA, 4);
        c.read(buf, 4);
        check(!c.ok(), "I2C short read: ok() false");
        check(allEq(buf, 4, 0), "I2C short read: buffer zero-filled");

        memset(buf, 0xAA, 4);
        c.read(buf, 4);
        check(!c.ok() && allEq(buf, 4, 0), "I2C read with no data: ok() false, zero-filled");

        const uint8_t out[3] = {9, 8, 7};
        c.write(out, 3);
        uint8_t got[3] = {};
        ssize_t n = read(p.peer, got, 3);
        check(c.ok() && n == 3 && memcmp(got, out, 3) == 0, "I2C write: ok() true, bytes sent");

        memset(buf, 0xAA, 4);
        c.write_read(out, 1, buf, 4);
        check(!c.ok() && allEq(buf, 4, 0), "I2C write_read failure (I2C_RDWR on non-i2c fd): ok() false, zero-filled");

        (void)!write(p.peer, in, 4);
        c.read(buf, 4);
        check(c.ok(), "I2C ok() recovers after a successful transfer");

        p.closePeer();
        c.write(out, 3);
        check(!c.ok(), "I2C write to closed peer: ok() false");
    }

    // ---- SMBus, no PEC ----
    {
        Pair p;
        SMBusConnectionLinux c(SMBusConnectionLinux::AdoptFd{}, p.dev, addr, false);
        const uint8_t in[4] = {5, 6, 7, 8};
        (void)!write(p.peer, in, 2);
        uint8_t buf[4] = {0xAA, 0xAA, 0xAA, 0xAA};
        c.read(buf, 4);
        check(!c.ok() && allEq(buf, 4, 0), "SMBus short read: ok() false, zero-filled");

        (void)!write(p.peer, in, 4);
        c.read(buf, 4);
        check(c.ok() && c.valid() && memcmp(buf, in, 4) == 0, "SMBus full read: ok() true");

        p.closePeer();
        const uint8_t out[2] = {1, 2};
        c.write(out, 2);
        check(!c.ok(), "SMBus write to closed peer: ok() false");
    }

    // ---- SMBus, PEC ----
    {
        Pair p;
        SMBusConnectionLinux c(SMBusConnectionLinux::AdoptFd{}, p.dev, addr, true);
        uint8_t frame[5] = {0x10, 0x20, 0x30, 0x40, 0};
        uint8_t ar = (uint8_t)((addr << 1) | 1);
        frame[4] = crc8(frame, 4, crc8(&ar, 1));
        (void)!write(p.peer, frame, 5);
        uint8_t buf[4];
        c.read(buf, 4);
        check(c.ok() && c.valid() && memcmp(buf, frame, 4) == 0, "SMBus PEC match: ok() and valid() true");

        frame[4] ^= 0xFF;
        (void)!write(p.peer, frame, 5);
        c.read(buf, 4);
        check(!c.ok() && !c.valid(), "SMBus PEC mismatch: ok() false and valid() false");

        (void)!write(p.peer, frame, 3);
        memset(buf, 0xAA, 4);
        c.read(buf, 4);
        check(!c.ok() && allEq(buf, 4, 0), "SMBus PEC short read: ok() false, zero-filled");

        const uint8_t cmd[1] = {0x01};
        memset(buf, 0xAA, 4);
        c.write_read(cmd, 1, buf, 4);
        check(!c.ok() && allEq(buf, 4, 0), "SMBus write_read failure: ok() false, zero-filled");
    }

    // ---- NeoPixel ----
    {
        Pair p;
        NeoPixelConnectionLinux c(NeoPixelConnectionLinux::AdoptFd{}, p.dev);
        const uint8_t px[3] = {255, 0, 0};
        bool threw = false;
        try { c.write(px, 3); } catch (const std::runtime_error&) { threw = true; }
        check(threw, "NeoPixel failed SPI transfer throws std::runtime_error");
        check(!c.ok(), "NeoPixel failed SPI transfer: ok() false");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
#else
int main() { return 0; }
#endif
