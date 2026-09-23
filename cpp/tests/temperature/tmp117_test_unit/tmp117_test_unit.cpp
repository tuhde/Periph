#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "TMP117.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Word-addressed variant of the byte-slot mock: TMP117 registers are 16 bits
// wide at consecutive pointer values, which would overlap in the shared
// mock's byte-slot model. Each pointer owns one 16-bit word here. Writes are
// still logged through the base class.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, uint16_t> words;
    std::vector<std::vector<uint8_t>> log;

    WordMock() {
        words[0x00] = 0x8000; words[0x01] = 0x0220; words[0x02] = 0x6000;
        words[0x03] = 0x8000; words[0x0F] = 0x1117;
    }

    std::vector<uint8_t> lastWriteTo(uint8_t reg) const {
        std::vector<uint8_t> last;
        for (const auto& w : log) if (w.size() >= 2 && w[0] == reg) last = w;
        return last;
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
        buf[0] = (uint8_t)(word >> 8);
        if (buf_len > 1) buf[1] = (uint8_t)(word & 0xFF);
    }
};

static const uint8_t REG_TEMP      = 0x00;
static const uint8_t REG_CONFIG    = 0x01;
static const uint8_t REG_THIGH     = 0x02;
static const uint8_t REG_TLOW      = 0x03;
static const uint8_t REG_EEPROM_UL = 0x04;
static const uint8_t REG_EEPROM1   = 0x05;
static const uint8_t REG_EEPROM2   = 0x06;
static const uint8_t REG_OFFSET    = 0x07;
static const uint8_t REG_EEPROM3   = 0x08;

using Mode = TMP117Full::Mode;

int main() {
    // Identity check passes and writes nothing. (A mismatch calls abort(),
    // which a single-process unit test cannot observe without dying.)
    WordMock mock;
    {
        TMP117Minimal sensor(mock);
        check_true(mock.log.size() == 1 && mock.log[0].size() == 1 && mock.log[0][0] == 0x0F,
                   "init_reads_device_id_only");

        WordMock rev;
        rev.words[0x0F] = 0x2117;  // revision nibble is ignored
        TMP117Minimal revSensor(rev);
        check_true(true, "init_ignores_revision");

        mock.words[REG_TEMP] = 0x0C80;
        check_true(sensor.readTemperature() == 25.0f, "temperature_positive");
        mock.words[REG_TEMP] = 0xFFFF;
        check_true(sensor.readTemperature() == -0.0078125f, "temperature_minus_lsb");
        mock.words[REG_TEMP] = 0xF380;
        check_true(sensor.readTemperature() == -25.0f, "temperature_negative");
        mock.words[REG_TEMP] = 0x8000;
        check_true(sensor.readTemperature() == -256.0f, "temperature_power_up_sentinel");
        mock.words[REG_TEMP] = 0x7FFF;
        check_true(sensor.readTemperature() == 255.9921875f, "temperature_max");
    }

    TMP117Full full(mock);

    // Limits and offset.
    full.setHighLimit(30.0f);
    check_true(mock.words[REG_THIGH] == 0x0F00, "set_high_limit_raw");
    check_true(full.getHighLimit() == 30.0f, "get_high_limit");
    full.setLowLimit(-10.25f);
    check_true(mock.words[REG_TLOW] == 0xFAE0, "set_low_limit_raw");
    check_true(full.getLowLimit() == -10.25f, "get_low_limit");
    full.setLowLimit(0.004f);
    check_true(mock.words[REG_TLOW] == 0x0001, "limit_rounding");
    full.setHighLimit(1000.0f);
    check_true(mock.words[REG_THIGH] == 0x7FFF, "limit_clamp_high");
    full.setLowLimit(-1000.0f);
    check_true(mock.words[REG_TLOW] == 0x8000, "limit_clamp_low");
    full.setTemperatureOffset(-0.5f);
    check_true(mock.words[REG_OFFSET] == 0xFFC0, "set_offset_raw");
    check_true(full.getTemperatureOffset() == -0.5f, "get_offset");

    // Conversion configuration.
    mock.words[REG_CONFIG] = 0x0220;
    TMP117Full::Config c = full.getConfig();
    check_true(c.mode == Mode::Continuous && c.averaging == 8 && c.cycleSeconds == 1.0f,
               "get_config_default");
    check_true(full.configure(Mode::Shutdown, 64, 16.0f), "configure_returns_true");
    check_true(mock.words[REG_CONFIG] == 0x07E0, "configure_shutdown_raw");
    c = full.getConfig();
    check_true(c.mode == Mode::Shutdown && c.averaging == 64 && c.cycleSeconds == 16.0f,
               "get_config_shutdown");
    check_true(full.isShutdown(), "is_shutdown");
    full.configure(Mode::Continuous, 0, 0.01f);
    check_true(mock.words[REG_CONFIG] == 0x0000, "configure_fastest_raw");
    check_true(!full.isShutdown(), "not_shutdown");
    full.configure(Mode::Continuous, 8, 0.3f);
    check_true(mock.words[REG_CONFIG] == 0x0120, "configure_nearest_cycle");
    full.configure(Mode::OneShot, 32, 2.0f);
    check_true(mock.words[REG_CONFIG] == 0x0E40, "configure_one_shot_raw");
    check_true(full.getConfig().mode == Mode::OneShot, "get_config_one_shot");
    mock.words[REG_CONFIG] = 0x0800;
    check_true(full.getConfig().mode == Mode::Continuous, "get_config_mod_10");
    mock.words[REG_CONFIG] = 0xF01C;
    full.configure();
    check_true(mock.words[REG_CONFIG] == 0x023C, "configure_preserves_alert_bits");
    mock.words[REG_CONFIG] = 0x0220;
    check_true(!full.configure(Mode::Continuous, 16, 1.0f) && mock.words[REG_CONFIG] == 0x0220,
               "configure_rejects_averaging");

    mock.words[REG_CONFIG] = 0xE660;
    full.triggerOneShot();
    check_true(mock.words[REG_CONFIG] == 0x0E60, "trigger_one_shot");

    mock.words[REG_CONFIG] = 0x2220;
    check_true(full.isDataReady(), "is_data_ready");
    mock.words[REG_CONFIG] = 0x0220;
    check_true(!full.isDataReady(), "is_not_data_ready");

    // Soft reset.
    full.reset();
    std::vector<uint8_t> w = mock.lastWriteTo(REG_CONFIG);
    check_true(w.size() == 3 && w[1] == 0x00 && w[2] == 0x02, "reset_write");

    // EEPROM.
    full.unlockEeprom();
    check_true(mock.words[REG_EEPROM_UL] == 0x8000, "unlock_eeprom");
    full.lockEeprom();
    check_true(mock.words[REG_EEPROM_UL] == 0x0000, "lock_eeprom");
    mock.words[REG_EEPROM_UL] = 0x4000;
    check_true(full.isEepromBusy(), "is_eeprom_busy");
    mock.words[REG_EEPROM_UL] = 0x8000;
    check_true(!full.isEepromBusy(), "is_eeprom_not_busy");

    mock.words[REG_EEPROM1] = 0x1111;
    mock.words[REG_EEPROM2] = 0x2222;
    mock.words[REG_EEPROM3] = 0x3333;
    uint16_t v = 0;
    check_true(full.readEepromScratch(1, v) && v == 0x1111, "read_scratch_1");
    check_true(full.readEepromScratch(2, v) && v == 0x2222, "read_scratch_2");
    check_true(full.readEepromScratch(3, v) && v == 0x3333, "read_scratch_3");
    check_true(!full.readEepromScratch(4, v), "read_scratch_rejects_slot");
    check_true(full.writeEepromScratch(2, 0xBEEF) && mock.words[REG_EEPROM2] == 0xBEEF,
               "write_scratch_2");
    check_true(!full.writeEepromScratch(1, 0) && !full.writeEepromScratch(3, 0) &&
               mock.words[REG_EEPROM1] == 0x1111 && mock.words[REG_EEPROM3] == 0x3333,
               "write_scratch_rejects_factory_slots");

    // Alert configuration.
    mock.words[REG_CONFIG] = 0x0220;
    full.configureAlert(TMP117Full::AlertMode::Therm, TMP117Full::AlertPolarity::ActiveHigh,
                        TMP117Full::AlertPinFunction::DataReady);
    check_true(mock.words[REG_CONFIG] == 0x023C, "configure_alert_bits");
    full.configureAlert();
    check_true(mock.words[REG_CONFIG] == 0x0220, "configure_alert_defaults");

    // poll_interrupt.
    mock.words[REG_CONFIG] = 0x2220;
    check_true(full.pollInterrupt() == 0, "poll_interrupt_none");
    mock.words[REG_CONFIG] = 0x8220;
    check_true(full.pollInterrupt() == TMP117Full::SOURCE_HIGH, "poll_interrupt_high");
    mock.words[REG_CONFIG] = 0x4220;
    check_true(full.pollInterrupt() == TMP117Full::SOURCE_LOW, "poll_interrupt_low");
    mock.words[REG_CONFIG] = 0xC220;
    check_true(full.pollInterrupt() == (TMP117Full::SOURCE_HIGH | TMP117Full::SOURCE_LOW),
               "poll_interrupt_both");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
