#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "TMP102.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: TMP102 registers are 16 bits
// wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;
    bool osDone = true;

    WordMock() {
        words[0x00] = 0x0000; words[0x01] = 0x60A0; words[0x02] = 0x4B00; words[0x03] = 0x5000;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len == 3) words[data[0]] = (uint16_t)((data[1] << 8) | data[2]);
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        log.emplace_back(data, data + data_len);
        uint16_t word = words.count(data[0]) ? words[data[0]] : 0;
        if (data[0] == 0x01) word = osDone ? (uint16_t)(word | 0x8000) : (uint16_t)(word & 0x7FFF);
        buf[0] = (uint8_t)(word >> 8);
        if (buf_len > 1) buf[1] = (uint8_t)(word & 0xFF);
    }
};

static const uint8_t REG_TEMP   = 0x00;
static const uint8_t REG_CONFIG = 0x01;
static const uint8_t REG_TLOW   = 0x02;
static const uint8_t REG_THIGH  = 0x03;

int main() {
    WordMock mock;
    TMP102Minimal sensor(mock);
    check_true(mock.log.empty(), "init_no_bus_access");

    mock.words[REG_TEMP] = 0x1900;
    check_true(sensor.readTemperature() == 25.0f, "temp_25c");
    mock.words[REG_TEMP] = 0xE700;
    check_true(sensor.readTemperature() == -25.0f, "temp_minus25c");
    mock.words[REG_TEMP] = 0x7FF0;
    check_true(sensor.readTemperature() == 127.9375f, "temp_max");
    mock.words[REG_TEMP] = 0xC900;
    check_true(sensor.readTemperature() == -55.0f, "temp_min");
    mock.words[REG_TEMP] = (uint16_t)((2400 << 3) | 1);
    check_true(sensor.readTemperature() == 150.0f, "temp_extended_150c");
    mock.words[REG_TEMP] = (uint16_t)(((uint16_t)(-1600) << 3) | 1);
    check_true(sensor.readTemperature() == -100.0f, "temp_extended_negative");

    WordMock m2;
    TMP102Full full(m2);
    m2.words[REG_TEMP] = 0x1900;
    check_true(full.readRaw() == 400, "raw_count");
    check_true(full.readConfig() == (0x60A0 | 0x8000), "read_config_default");

    check_true(full.setConversionRate(8.0f), "rate_8hz_ok");
    check_true((m2.words[REG_CONFIG] & 0x00C0) == 0x00C0, "rate_8hz_bits");
    check_true((m2.words[REG_CONFIG] & 0x6000) == 0x6000, "rate_preserves_reserved");
    full.setConversionRate(0.25f);
    check_true((m2.words[REG_CONFIG] & 0x00C0) == 0x0000, "rate_025hz_bits");
    check_true(!full.setConversionRate(2.0f), "rate_invalid_rejected");

    full.setFaultQueue(6);
    check_true((m2.words[REG_CONFIG] & 0x1800) == 0x1800, "fault_queue_6");
    full.setFaultQueue(2);
    check_true((m2.words[REG_CONFIG] & 0x1800) == 0x0800, "fault_queue_2");
    check_true(!full.setFaultQueue(3), "fault_queue_invalid_rejected");

    full.setPolarity(true);
    check_true((m2.words[REG_CONFIG] & 0x0400) != 0, "polarity_high");
    full.setPolarity(false);
    check_true((m2.words[REG_CONFIG] & 0x0400) == 0, "polarity_low");
    full.setThermostatMode(true);
    check_true((m2.words[REG_CONFIG] & 0x0200) != 0, "tm_interrupt");
    full.setThermostatMode(false);
    check_true((m2.words[REG_CONFIG] & 0x0200) == 0, "tm_comparator");
    full.setExtendedMode(true);
    check_true((m2.words[REG_CONFIG] & 0x0010) != 0, "em_on");
    full.setExtendedMode(false);
    check_true((m2.words[REG_CONFIG] & 0x0010) == 0, "em_off");
    full.shutdown();
    check_true((m2.words[REG_CONFIG] & 0x0100) != 0, "shutdown_sd");
    full.wake();
    check_true((m2.words[REG_CONFIG] & 0x0100) == 0, "wake_sd_clear");
    full.writeConfig(0xFFFF);
    check_true((m2.words[REG_CONFIG] & 0x602F) == 0x6000, "write_config_masks_readonly");

    WordMock m3;
    TMP102Full lim(m3);
    lim.setTHigh(30.0f);
    check_true(m3.words[REG_THIGH] == 0x1E00, "t_high_encoding");
    check_true(lim.getTHigh() == 30.0f, "t_high_roundtrip");
    lim.setTLow(-10.5f);
    check_true(lim.getTLow() == -10.5f, "t_low_negative_roundtrip");
    lim.setTHigh(500.0f);
    check_true(lim.getTHigh() == 127.9375f, "t_high_12bit_clamp");
    lim.setExtendedMode(true);
    lim.setTHigh(140.0f);
    check_true(m3.words[REG_THIGH] == (uint16_t)(2240 << 3), "t_high_extended_encoding");
    check_true(lim.getTHigh() == 140.0f, "t_high_extended_roundtrip");
    lim.setTHigh(1000.0f);
    check_true(lim.getTHigh() == 150.0f, "t_high_clamped_150");

    WordMock m4;
    TMP102Full os(m4);
    m4.words[REG_TEMP] = 0x1900;
    float t = 0.0f;
    check_true(os.oneShot(t) && t == 25.0f, "one_shot_value");
    bool sawOs = false;
    for (const auto& w : m4.log) if (w.size() == 3 && w[0] == REG_CONFIG && (w[1] & 0x80)) sawOs = true;
    check_true(sawOs, "one_shot_sets_os");
    check_true((m4.words[REG_CONFIG] & 0x0100) == 0, "one_shot_restores_running");
    m4.words[REG_CONFIG] = 0x6180;
    os.oneShot(t);
    check_true((m4.words[REG_CONFIG] & 0x0100) != 0, "one_shot_stays_shutdown");
    m4.osDone = false;
    check_true(!os.oneShot(t), "one_shot_timeout");

    WordMock m5;
    TMP102Full al(m5);
    m5.words[REG_CONFIG] = 0x6000 | 0x0020;
    check_true(!al.alertState(), "alert_state_inactive_pol0");
    m5.words[REG_CONFIG] = 0x6000;
    check_true(al.alertState(), "alert_state_active_pol0");
    m5.words[REG_CONFIG] = 0x6400 | 0x0020;
    check_true(al.alertState(), "alert_state_active_pol1");
    m5.words[REG_CONFIG] = 0x6400;
    check_true(!al.alertState(), "alert_state_inactive_pol1");
    m5.log.clear();
    m5.words[REG_CONFIG] = 0x6000;
    check_true(al.pollInterrupt(), "poll_interrupt_true");
    bool readTemp = false;
    for (const auto& w : m5.log) if (w.size() == 1 && w[0] == REG_TEMP) readTemp = true;
    check_true(readTemp, "poll_interrupt_reads_temperature");

    m5.log.clear();
    al.reset();
    check_true(m5.log.size() == 1 && m5.log[0].size() == 2 && m5.log[0][0] == 0x00 && m5.log[0][1] == 0x06,
               "reset_general_call");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
