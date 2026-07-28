#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "ENS160.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class ENS160TestAccess : public ENS160Full {
public:
    using ENS160Full::ENS160Full;
    using ENS160Full::REG_OPMODE;
    using ENS160Full::REG_CONFIG;
    using ENS160Full::REG_TEMP_IN;
    using ENS160Full::REG_RH_IN;
    using ENS160Full::REG_DEVICE_STATUS;
    using ENS160Full::REG_DATA_AQI;
    using ENS160Full::REG_DATA_T;
    using ENS160Full::REG_GPR_READ;
    using ENS160Full::REG_PART_ID;
    using ENS160Full::OPMODE_IDLE;
    using ENS160Full::OPMODE_STANDARD;
    using ENS160Full::OPMODE_DEEP_SLEEP;
};

int main() {
    I2CConnectionMock connection;
    // PART_ID read during construction must return 0x0160 (LE: 0x60, 0x01).
    connection.setRegister(ENS160TestAccess::REG_PART_ID, {0x60, 0x01});

    ENS160TestAccess sensor(connection);
    check_true(true, "init");

    int opmodeWrites = 0;
    bool sawIdle = false, sawStandardLast = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == ENS160TestAccess::REG_OPMODE) {
            opmodeWrites++;
            if (w[1] == ENS160TestAccess::OPMODE_IDLE) sawIdle = true;
            sawStandardLast = (w[1] == ENS160TestAccess::OPMODE_STANDARD);
        }
    }
    check_true(opmodeWrites >= 2 && sawIdle && sawStandardLast, "init_writes_idle_then_standard");

    // DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK).
    connection.setRegister(ENS160TestAccess::REG_DEVICE_STATUS, {0x02});
    check_true(sensor.status() == ENS160Full::VALIDITY_OK, "status_ok");

    // DATA_AQI block (5 bytes): aqi=2, tvoc_ppb=150 (0x0096 LE), eco2_ppm=500 (0x01F4 LE).
    connection.setRegister(ENS160TestAccess::REG_DATA_AQI, {2, 0x96, 0x00, 0xF4, 0x01});

    uint8_t aqi; float tvoc_ppb, eco2_ppm;
    bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);
    check_true(ok, "read_air_quality_ok");
    check_true(aqi == 2, "read_air_quality_aqi");
    check_true(tvoc_ppb == 150.0f, "read_air_quality_tvoc");
    check_true(eco2_ppm == 500.0f, "read_air_quality_eco2");

    check_true(sensor.read_tvoc() == 150.0f, "read_tvoc");
    check_true(sensor.read_eco2() == 500.0f, "read_eco2");
    check_true(sensor.read_aqi() == 2, "read_aqi");
    check_true(sensor.read_ethanol() == 150.0f, "read_ethanol_aliases_tvoc");

    sensor.set_compensation(25.0f, 50.0f);
    uint16_t temp_raw = (uint16_t)((25.0f + 273.15f) * 64.0f);
    uint16_t rh_raw = (uint16_t)(50.0f * 512.0f);
    const auto& regs = connection.registers();
    check_true(regs.at(ENS160TestAccess::REG_TEMP_IN) == (temp_raw & 0xFF) &&
               regs.at(ENS160TestAccess::REG_TEMP_IN + 1) == ((temp_raw >> 8) & 0xFF),
               "set_compensation_temp");
    check_true(regs.at(ENS160TestAccess::REG_RH_IN) == (rh_raw & 0xFF) &&
               regs.at(ENS160TestAccess::REG_RH_IN + 1) == ((rh_raw >> 8) & 0xFF),
               "set_compensation_rh");

    // DATA_T/DATA_RH actuals (4 bytes): temp_raw=0x5202, rh_raw=0x6400 (50.0 %RH).
    connection.setRegister(ENS160TestAccess::REG_DATA_T, {0x02, 0x52, 0x00, 0x64});
    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);
    check_true(fabsf(temp_actual - ((0x5202 / 64.0f) - 273.15f)) < 1e-4f, "read_compensation_actuals_temp");
    check_true(rh_actual == 0x6400 / 512.0f, "read_compensation_actuals_rh");

    // GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm.
    connection.setRegister(ENS160TestAccess::REG_GPR_READ, {0x00, 0x08});
    check_true(sensor.read_raw_resistance(1) == 2.0f, "read_raw_resistance_1");

    // GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm.
    connection.setRegister(ENS160TestAccess::REG_GPR_READ + 6, {0x00, 0x10});
    check_true(sensor.read_raw_resistance(4) == 4.0f, "read_raw_resistance_4");

    // GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3.
    connection.setRegister(ENS160TestAccess::REG_GPR_READ + 4, {1, 2, 3});
    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);
    check_true(major == 1 && minor == 2 && release == 3, "get_firmware_version");

    sensor.configure_interrupt(true, true, true, true, true);
    uint8_t lastConfig = 0;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == ENS160TestAccess::REG_CONFIG) lastConfig = w[1];
    }
    check_true(lastConfig == 0x6B, "configure_interrupt");

    sensor.sleep();
    const auto& lastWrite1 = connection.writes().back();
    check_true(lastWrite1.size() == 2 && lastWrite1[0] == ENS160TestAccess::REG_OPMODE &&
               lastWrite1[1] == ENS160TestAccess::OPMODE_DEEP_SLEEP, "sleep");

    sensor.wake();
    const auto& lastWrite2 = connection.writes().back();
    check_true(lastWrite2.size() == 2 && lastWrite2[0] == ENS160TestAccess::REG_OPMODE &&
               lastWrite2[1] == ENS160TestAccess::OPMODE_STANDARD, "wake");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
