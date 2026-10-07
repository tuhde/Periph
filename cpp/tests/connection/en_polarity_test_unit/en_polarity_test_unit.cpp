#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include "Connection.h"
#include "OutputPin.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class RecordingPin : public OutputPin {
public:
    int last = -1;
    int count = 0;
    void set(bool high) override { last = high ? 1 : 0; count++; }
};

class NullConnection : public Connection {
public:
    NullConnection(OutputPin* en, bool activeHigh) : Connection(nullptr, en, activeHigh) {}
    NullConnection(OutputPin* en) : Connection(nullptr, en) {}
protected:
    void _write(const uint8_t*, size_t) override {}
    void _read(uint8_t*, size_t) override {}
    void _write_read(const uint8_t*, size_t, uint8_t*, size_t) override {}
};

int main() {
    {
        RecordingPin pin;
        NullConnection c(&pin, true);
        c.disable();
        check_true(pin.last == 0, "active-high: disable drives low");
        c.enable();
        check_true(pin.last == 1, "active-high: enable drives high");
    }
    {
        RecordingPin pin;
        NullConnection c(&pin, false);
        c.disable();
        check_true(pin.last == 1, "active-low: disable drives high");
        check_true(!c.isEnabled(), "active-low: software gate closed on disable");
        c.enable();
        check_true(pin.last == 0, "active-low: enable drives low");
        check_true(c.isEnabled(), "active-low: software gate open on enable");
    }
    {
        RecordingPin pin;
        NullConnection c(&pin);
        c.enable();
        check_true(pin.last == 1, "default polarity is active-high");
    }
    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
