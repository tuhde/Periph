#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Max17048.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Last 3-byte write (register + two data bytes), ignoring the one-byte register-pointer writes of reads.
static bool last_write_is(I2CConnectionMock& c, uint8_t reg, uint8_t msb, uint8_t lsb) {
    for (auto it = c.writes().rbegin(); it != c.writes().rend(); ++it)
        if (it->size() == 3) return (*it)[0] == reg && (*it)[1] == msb && (*it)[2] == lsb;
    return false;
}

static size_t word_writes(I2CConnectionMock& c) {
    size_t n = 0;
    for (const auto& w : c.writes()) if (w.size() == 3) n++;
    return n;
}

static bool near(float a, float b, float tol = 1e-4f) { return fabsf(a - b) < tol; }

static uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

int main() {
    I2CConnectionMock conn;
    Max17048Full gauge(conn);
    check_true(conn.writes().empty(), "init_no_bus_access");

    conn.setRegister(0x02, {0xC3, 0x50});   // (0xC350 >> 4) * 1.25 mV = 3.90625 V
    check_true(near(gauge.voltage(), 3.90625f), "voltage");
    check_true(gauge.raw_voltage() == 0xC350, "raw_voltage");
    Max17048Minimal twoCell(conn, 2);
    check_true(near(twoCell.voltage(), 7.8125f), "voltage_two_cells");

    conn.setRegister(0x04, {0x4B, 0x80});
    check_true(near(gauge.percent(), 75.5f), "percent");
    check_true(gauge.raw_soc() == 0x4B80, "raw_soc");
    conn.setRegister(0x04, {0xFF, 0xFF});
    check_true(gauge.percent() == 100.0f, "percent_clamped");

    conn.setRegister(0x16, {0xFF, 0xEC});
    check_true(near(gauge.charge_rate(), -4.16f), "charge_rate_negative");
    conn.setRegister(0x16, {0x00, 0x64});
    check_true(near(gauge.charge_rate(), 20.8f), "charge_rate");

    conn.setRegister(0x08, {0x00, 0x12});
    check_true(gauge.version() == 0x0012, "version");
    conn.setRegister(0x18, {0x96, 0xAB});
    check_true(gauge.chip_id() == 0xAB, "chip_id");

    conn.setRegister(0x0C, {0x97, 0x1C});
    check_true(gauge.get_rcomp() == 0x97, "get_rcomp");
    gauge.set_rcomp(0x80);
    check_true(last_write_is(conn, 0x0C, 0x80, 0x1C), "set_rcomp_rmw");
    conn.setRegister(0x0C, {0x97, 0x1C});
    gauge.compensate_temperature(35.0f);
    check_true(last_write_is(conn, 0x0C, 0x90, 0x1C), "compensate_hot");
    gauge.compensate_temperature(5.0f);
    check_true(last_write_is(conn, 0x0C, 0xE2, 0x1C), "compensate_cold");

    conn.setRegister(0x0C, {0x97, 0x1C});
    gauge.set_alert_soc(10);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x16), "set_alert_soc");
    check_true(gauge.get_alert_soc() == 10, "get_alert_soc");
    gauge.set_soc_change_alert(true);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x56), "soc_change_on");
    gauge.set_soc_change_alert(false);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x16), "soc_change_off");

    check_true(gauge.set_voltage_alert(3.3f, 4.2f), "voltage_alert_ok");
    check_true(last_write_is(conn, 0x14, 0xA5, 0xD2), "set_voltage_alert");
    float lo = 0, hi = 0;
    gauge.get_voltage_alert(&lo, &hi);
    check_true(near(lo, 3.3f, 1e-3f) && near(hi, 4.2f, 1e-3f), "get_voltage_alert");
    gauge.set_voltage_alert(0.0f, 5.1f);
    check_true(last_write_is(conn, 0x14, 0x00, 0xFF), "voltage_alert_disabled");
    size_t before = word_writes(conn);
    check_true(!gauge.set_voltage_alert(4.0f, 3.0f) && word_writes(conn) == before, "voltage_alert_invalid");

    conn.setRegister(0x18, {0x01, 0x5A});
    gauge.set_reset_voltage(3.0f);
    check_true(last_write_is(conn, 0x18, 0x97, 0x5A), "set_reset_voltage");
    check_true(near(gauge.get_reset_voltage(), 3.0f), "get_reset_voltage");
    gauge.set_comparator_disabled(false);
    check_true(last_write_is(conn, 0x18, 0x96, 0x5A), "comparator_enabled");
    gauge.set_comparator_disabled(true);
    check_true(last_write_is(conn, 0x18, 0x97, 0x5A), "comparator_disabled");

    conn.setRegister(0x1A, {0x01, 0x00});
    gauge.enable_voltage_reset_alert(true);
    check_true(last_write_is(conn, 0x1A, 0x41, 0x00), "envr_on");
    gauge.enable_voltage_reset_alert(false);
    check_true(last_write_is(conn, 0x1A, 0x01, 0x00), "envr_off");

    gauge.set_hibernate_thresholds(26.624f, 0.06f);
    check_true(last_write_is(conn, 0x0A, 0x80, 0x30), "set_hibernate");
    float hib = 0, act = 0;
    gauge.get_hibernate_thresholds(&hib, &act);
    check_true(near(hib, 26.624f, 1e-3f) && near(act, 0.06f, 1e-4f), "get_hibernate");
    gauge.disable_hibernate();
    check_true(last_write_is(conn, 0x0A, 0x00, 0x00), "disable_hibernate");
    gauge.force_hibernate();
    check_true(last_write_is(conn, 0x0A, 0xFF, 0xFF), "force_hibernate");

    conn.setRegister(0x06, {0x10, 0x00});
    check_true(gauge.is_hibernating(), "is_hibernating_true");
    conn.setRegister(0x06, {0x00, 0x00});
    check_true(!gauge.is_hibernating(), "is_hibernating_false");
    gauge.enable_sleep(true);
    check_true(last_write_is(conn, 0x06, 0x20, 0x00), "enable_sleep");
    gauge.quick_start();
    check_true(last_write_is(conn, 0x06, 0x60, 0x00), "quick_start_keeps_ensleep");
    gauge.enable_sleep(false);
    check_true(last_write_is(conn, 0x06, 0x00, 0x00), "disable_sleep");
    gauge.quick_start();
    check_true(last_write_is(conn, 0x06, 0x40, 0x00), "quick_start");

    conn.setRegister(0x0C, {0x97, 0x1C});
    gauge.sleep();
    check_true(last_write_is(conn, 0x0C, 0x97, 0x9C), "sleep");
    gauge.wake();
    check_true(last_write_is(conn, 0x0C, 0x97, 0x1C), "wake");

    conn.setRegister(0x0C, {0x97, 0x1C});
    check_true(gauge.read_config() == 0x971C, "read_config");
    gauge.write_config(0x9700);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x00), "write_config");

    conn.setRegister(0x1A, {0x11, 0x00});
    check_true(gauge.read_status() == 0x1100, "read_status");
    conn.setRegister(0x0C, {0x97, 0x3C});
    gauge.clear_status(0x0100);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x1C), "clear_status_alrt");
    check_true(conn.registers().at(0x1A) == 0x10 && conn.registers().at(0x1B) == 0x00, "clear_status_status");

    conn.setRegister(0x1A, {0x51, 0x00});   // ENVR | HD | RI
    conn.setRegister(0x0C, {0x97, 0x3C});
    check_true(gauge.pollInterrupt() == 0x1100, "poll_interrupt_flags");
    check_true(conn.registers().at(0x1A) == 0x40, "poll_interrupt_clears_status");
    check_true(conn.registers().at(0x0C) == 0x97 && conn.registers().at(0x0D) == 0x1C, "poll_interrupt_clears_alrt");
    before = word_writes(conn);
    check_true(gauge.pollInterrupt() == 0 && word_writes(conn) == before, "poll_interrupt_none");

    conn.setRegister(0x0C, {0x97, 0x1C});
    gauge.enableInterrupt(Max17048Full::SOURCE_SOC_CHANGE);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x5C), "enable_soc_change");
    gauge.disableInterrupt(Max17048Full::SOURCE_SOC_CHANGE);
    check_true(last_write_is(conn, 0x0C, 0x97, 0x1C), "disable_soc_change");
    conn.setRegister(0x14, {0xA5, 0xD2});
    gauge.disableInterrupt(Max17048Full::SOURCE_UNDERVOLTAGE);
    check_true(last_write_is(conn, 0x14, 0x00, 0xD2), "disable_undervoltage");
    gauge.disableInterrupt(Max17048Full::SOURCE_OVERVOLTAGE);
    check_true(last_write_is(conn, 0x14, 0x00, 0xFF), "disable_overvoltage");

    gauge.onInterrupt(on_alert);   // no pin: nothing is subscribed
    gauge.offInterrupt();
    check_true(g_alert == 0, "on_interrupt_without_pin");

    conn.failNextWrites(1);
    gauge.reset();
    check_true(last_write_is(conn, 0xFE, 0x54, 0x00), "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
