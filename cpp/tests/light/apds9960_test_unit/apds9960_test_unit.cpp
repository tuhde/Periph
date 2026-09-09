#include <stdio.h>
#include "I2CConnectionMock.h"
#include "APDS9960.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class APDS9960TestAccess : public APDS9960Full {
public:
    using APDS9960Full::APDS9960Full;
    using APDS9960Full::REG_ENABLE;
    using APDS9960Full::REG_ATIME;
    using APDS9960Full::REG_WTIME;
    using APDS9960Full::REG_AILTL;
    using APDS9960Full::REG_AILTH;
    using APDS9960Full::REG_AIHTL;
    using APDS9960Full::REG_AIHTH;
    using APDS9960Full::REG_PILT;
    using APDS9960Full::REG_PIHT;
    using APDS9960Full::REG_PERS;
    using APDS9960Full::REG_CONFIG1;
    using APDS9960Full::REG_PPULSE;
    using APDS9960Full::REG_CONTROL;
    using APDS9960Full::REG_CONFIG2;
    using APDS9960Full::REG_ID;
    using APDS9960Full::REG_STATUS;
    using APDS9960Full::REG_CDATAL;
    using APDS9960Full::REG_PDATA;
    using APDS9960Full::REG_POFFSET_UR;
    using APDS9960Full::REG_POFFSET_DL;
    using APDS9960Full::REG_CONFIG3;
    using APDS9960Full::REG_GPENTH;
    using APDS9960Full::REG_GEXTH;
    using APDS9960Full::REG_GCONF2;
    using APDS9960Full::REG_GPULSE;
    using APDS9960Full::REG_GCONF4;
    using APDS9960Full::REG_GFLVL;
    using APDS9960Full::REG_GSTATUS;
    using APDS9960Full::REG_PICLEAR;
    using APDS9960Full::REG_CICLEAR;
    using APDS9960Full::REG_AICLEAR;
    using APDS9960Full::REG_GFIFO_U;
};

int main() {
    I2CConnectionMock connection;
    connection.setRegister(APDS9960TestAccess::REG_ID, {0xAB});

    APDS9960TestAccess sensor(connection);
    check_true(true, "init");

    int enableWrites = 0;
    bool sawOff = false, sawOnLast = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == APDS9960TestAccess::REG_ENABLE) {
            enableWrites++;
            if (w[1] == 0x00) sawOff = true;
            sawOnLast = (w[1] == 0x03);
        }
    }
    check_true(enableWrites >= 2 && sawOff && sawOnLast, "init_writes_enable_off_then_on");

    const auto& regs = connection.registers();
    check_true(regs.at(APDS9960TestAccess::REG_ATIME) == 0xB6, "init_writes_atime_default");
    check_true(regs.at(APDS9960TestAccess::REG_CONTROL) == 0x01, "init_writes_control_default");
    check_true(regs.at(APDS9960TestAccess::REG_CONFIG2) == 0x01, "init_writes_config2_default");

    // color(): CDATAL burst of 8 bytes, LE 16-bit words: clear=0x1234, red=0x0102,
    // green=0x0304, blue=0x0506.
    connection.setRegister(APDS9960TestAccess::REG_CDATAL,
                            {0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05});
    uint16_t clear, red, green, blue;
    sensor.color(clear, red, green, blue);
    check_true(clear == 0x1234 && red == 0x0102 && green == 0x0304 && blue == 0x0506, "color");

    check_true(sensor.color_clear() == 0x1234, "color_clear_method");
    check_true(sensor.color_red() == 0x0102, "color_red_method");
    check_true(sensor.color_green() == 0x0304, "color_green_method");
    check_true(sensor.color_blue() == 0x0506, "color_blue_method");

    sensor.enable_proximity(true);
    check_true(regs.at(APDS9960TestAccess::REG_ENABLE) == 0x07, "enable_proximity_sets_pen");
    sensor.enable_proximity(false);
    check_true(regs.at(APDS9960TestAccess::REG_ENABLE) == 0x03, "enable_proximity_clears_pen");

    connection.setRegister(APDS9960TestAccess::REG_PDATA, {200});
    check_true(sensor.proximity() == 200, "proximity");

    sensor.enable_wait(true);
    check_true(regs.at(APDS9960TestAccess::REG_ENABLE) == 0x0B, "enable_wait_sets_wen");
    sensor.enable_wait(false);
    check_true(regs.at(APDS9960TestAccess::REG_ENABLE) == 0x03, "enable_wait_clears_wen");

    sensor.configure_wait(100, true);
    check_true(regs.at(APDS9960TestAccess::REG_WTIME) == 100, "configure_wait_wtime");
    check_true(regs.at(APDS9960TestAccess::REG_CONFIG1) == 0x62, "configure_wait_config1_wlong");
    sensor.configure_wait(50, false);
    check_true(regs.at(APDS9960TestAccess::REG_CONFIG1) == 0x60, "configure_wait_config1_no_wlong");

    sensor.configure_als(0xDB, 2);
    check_true(regs.at(APDS9960TestAccess::REG_ATIME) == 0xDB, "configure_als_atime");
    check_true((regs.at(APDS9960TestAccess::REG_CONTROL) & 0x03) == 2, "configure_als_again");

    sensor.configure_proximity_led(1, 2, 10, 3);
    uint8_t ctrl = regs.at(APDS9960TestAccess::REG_CONTROL);
    check_true(((ctrl >> 6) & 0x03) == 1, "configure_proximity_led_ldrive");
    check_true(((ctrl >> 2) & 0x03) == 2, "configure_proximity_led_pgain");
    check_true(regs.at(APDS9960TestAccess::REG_PPULSE) == ((3 << 6) | 10), "configure_proximity_led_ppulse");

    sensor.set_led_boost(2);
    check_true(regs.at(APDS9960TestAccess::REG_CONFIG2) == ((2 << 4) | 0x01), "set_led_boost");

    sensor.als_threshold(0x1234, 0x5678);
    check_true(regs.at(APDS9960TestAccess::REG_AILTL) == 0x34 && regs.at(APDS9960TestAccess::REG_AILTH) == 0x12,
               "als_threshold_low");
    check_true(regs.at(APDS9960TestAccess::REG_AIHTL) == 0x78 && regs.at(APDS9960TestAccess::REG_AIHTH) == 0x56,
               "als_threshold_high");

    sensor.proximity_threshold(10, 200);
    check_true(regs.at(APDS9960TestAccess::REG_PILT) == 10 && regs.at(APDS9960TestAccess::REG_PIHT) == 200,
               "proximity_threshold");

    sensor.set_persistence(5, 3);
    check_true(regs.at(APDS9960TestAccess::REG_PERS) == ((5 << 4) | 3), "set_persistence");

    sensor.enable_als_interrupt(true);
    check_true((regs.at(APDS9960TestAccess::REG_ENABLE) & 0x10) != 0, "enable_als_interrupt");
    sensor.enable_proximity_interrupt(true);
    check_true((regs.at(APDS9960TestAccess::REG_ENABLE) & 0x20) != 0, "enable_proximity_interrupt");

    sensor.clear_proximity_interrupt();
    check_true(connection.writes().back() == std::vector<uint8_t>{APDS9960TestAccess::REG_PICLEAR},
               "clear_proximity_interrupt");
    sensor.clear_als_interrupt();
    check_true(connection.writes().back() == std::vector<uint8_t>{APDS9960TestAccess::REG_CICLEAR},
               "clear_als_interrupt");
    sensor.clear_all_interrupts();
    check_true(connection.writes().back() == std::vector<uint8_t>{APDS9960TestAccess::REG_AICLEAR},
               "clear_all_interrupts");

    // Sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64.
    sensor.set_proximity_offset(-50, 100);
    check_true(regs.at(APDS9960TestAccess::REG_POFFSET_UR) == 0xB2, "set_proximity_offset_negative");
    check_true(regs.at(APDS9960TestAccess::REG_POFFSET_DL) == 0x64, "set_proximity_offset_positive");

    sensor.set_proximity_mask(true, false, true, false);
    check_true(regs.at(APDS9960TestAccess::REG_CONFIG3) == (0x08 | 0x02), "set_proximity_mask");

    sensor.enable_gesture(true);
    check_true((regs.at(APDS9960TestAccess::REG_ENABLE) & 0x40) != 0, "enable_gesture_sets_gen");
    check_true((regs.at(APDS9960TestAccess::REG_GCONF4) & 0x01) != 0, "enable_gesture_sets_gmode");
    sensor.enable_gesture(false);
    check_true((regs.at(APDS9960TestAccess::REG_ENABLE) & 0x40) == 0, "enable_gesture_clears_gen");
    check_true((regs.at(APDS9960TestAccess::REG_GCONF4) & 0x01) == 0, "enable_gesture_clears_gmode");

    sensor.configure_gesture(1, 2, 20, 3, 5, 30, 10);
    check_true(regs.at(APDS9960TestAccess::REG_GPENTH) == 30, "configure_gesture_gpenth");
    check_true(regs.at(APDS9960TestAccess::REG_GEXTH) == 10, "configure_gesture_gexth");
    check_true(regs.at(APDS9960TestAccess::REG_GCONF2) == ((1 << 5) | (2 << 3) | 5), "configure_gesture_gconf2");
    check_true(regs.at(APDS9960TestAccess::REG_GPULSE) == ((3 << 6) | 20), "configure_gesture_gpulse");

    connection.setRegister(APDS9960TestAccess::REG_GSTATUS, {0x01});
    check_true(sensor.gesture_available() == true, "gesture_available");

    connection.setRegister(APDS9960TestAccess::REG_GFLVL, {2});
    connection.setRegister(APDS9960TestAccess::REG_GFIFO_U, {10, 20, 30, 40});
    uint8_t fifoBuf[4 * 4];
    uint8_t level = sensor.read_gesture_fifo(fifoBuf, 4);
    check_true(level == 2, "read_gesture_fifo_level");
    check_true(fifoBuf[0] == 10 && fifoBuf[1] == 20 && fifoBuf[2] == 30 && fifoBuf[3] == 40,
               "read_gesture_fifo_first_dataset");

    connection.setRegister(APDS9960TestAccess::REG_GFLVL, {0});
    check_true(sensor.read_gesture_fifo(fifoBuf, 4) == 0, "read_gesture_fifo_empty");
    check_true(sensor.gesture_fifo_level() == 0, "gesture_fifo_level");

    sensor.clear_gesture_fifo();
    check_true((regs.at(APDS9960TestAccess::REG_GCONF4) & 0x04) != 0, "clear_gesture_fifo");

    sensor.enable_gesture_interrupt(true);
    check_true((regs.at(APDS9960TestAccess::REG_GCONF4) & 0x02) != 0, "enable_gesture_interrupt");

    connection.setRegister(APDS9960TestAccess::REG_STATUS, {0x93});  // CPSAT|PVALID|AVALID
    check_true(sensor.status() == 0x93, "status");
    check_true(sensor.is_als_valid() == true, "is_als_valid");
    check_true(sensor.is_proximity_valid() == true, "is_proximity_valid");
    check_true(sensor.is_als_saturated() == true, "is_als_saturated");
    check_true(sensor.is_proximity_saturated() == false, "is_proximity_saturated");

    check_true(sensor.chip_id() == 0xAB, "chip_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
