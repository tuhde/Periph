#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "ADS1114.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: the four ADS111x registers are
// 16 bits wide at consecutive pointer values, which would overlap in the
// shared mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() {
        words[0] = 0x0000; words[1] = 0x8583; words[2] = 0x8000; words[3] = 0x7FFF;
    }

    uint16_t lastConfig() const {
        uint16_t last = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) last = (uint16_t)((w[1] << 8) | w[2]);
        return last;
    }
    size_t configWrites() const {
        size_t n = 0;
        for (const auto& w : log) if (w.size() == 3 && w[0] == 1) n++;
        return n;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len == 3) words[data[0]] = (uint16_t)((data[1] << 8) | data[2]);
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        log.emplace_back(data, data + data_len);
        uint16_t word = words.count(data[0]) ? words[data[0]] : 0;
        buf[0] = (uint8_t)(word >> 8);
        if (buf_len > 1) buf[1] = (uint8_t)(word & 0xFF);
    }
};

class FakePin : public InputPin {
public:
    bool high;
    explicit FakePin(bool h) : high(h) {}
    bool onEdge(Handler, uint8_t) override { return true; }
    void offEdge(Handler) override {}
    bool isHigh() override { return high; }
};

int main() {
    WordMock mock;
    ADS1114Minimal a14(mock);
    mock.words[0] = 0x1000;
    check_true(a14.readRaw() == 0x1000 && mock.lastConfig() == 0x8583, "ads1114_read_raw");
    ADS1114Full full(mock);
    full.setGain(ADS1114Full::PGA_0_256);
    check_true(full.lsb() == 0.256f / 32768.0f, "ads1114_lsb");
    full.startContinuous();
    check_true(mock.lastConfig() == 0x0A83, "ads1114_continuous");
    full.setThresholds(-0.1f, 0.1f);
    full.configureComparator();
    check_true((mock.lastConfig() & 3) == 0, "ads1114_comparator_enabled");
    full.reset();
    check_true(mock.words[1] == 0x8583 && full.getGain() == 2, "ads1114_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
