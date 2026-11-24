#include "BME688.h"
#include "../../connection/Register.h"
#include <stdlib.h>
#include <math.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

BME688Minimal::BME688Minimal(RegisterConnection& connection)
    : _connection(connection) {
    _read_calibration();
    { uint8_t v = _osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    { uint8_t v = (_osrs_t << 5) | (_osrs_p << 2) | 0; _connection.write(REG_CTRL_MEAS, &v, 1); }
    { uint8_t v = 0; _connection.write(REG_CONFIG, &v, 1); }
    _setup_heater(0, _heat_temp, _heat_dur);
    { uint8_t v = RUN_GAS | 0; _connection.write(REG_CTRL_GAS_1, &v, 1); }
}

void BME688Minimal::_read_calibration() {
    uint8_t b1[23], b2[14], s1[1], s2[1];
    _connection.read(REG_CAL_BLOCK1, b1, 23);
    _connection.read(REG_CAL_BLOCK2, b2, 14);
    _connection.read(REG_RES_HEAT_VAL, s1, 1);
    _connection.read(REG_RES_HEAT_RANGE, s2, 1);

    _par_T2 = (int16_t)(b1[0] | (b1[1] << 8));
    _par_T3 = (int8_t)b1[2];
    _par_P1 = (uint16_t)(b1[4] | (b1[5] << 8));
    _par_P2 = (int16_t)(b1[6] | (b1[7] << 8));
    _par_P3 = (int8_t)b1[8];
    _par_P4 = (int16_t)(b1[10] | (b1[11] << 8));
    _par_P5 = (int16_t)(b1[12] | (b1[13] << 8));
    _par_P7 = (int8_t)b1[14];
    _par_P6 = (int8_t)b1[15];
    _par_P8 = (int16_t)(b1[18] | (b1[19] << 8));
    _par_P9 = (int16_t)(b1[20] | (b1[21] << 8));
    _par_P10 = b1[22];

    _par_H2 = (uint16_t)((b2[0] << 4) | (b2[1] >> 4));
    _par_H1 = (uint16_t)((b2[2] << 4) | (b2[1] & 0x0F));
    _par_H3 = (int8_t)b2[3];
    _par_H4 = (int8_t)b2[4];
    _par_H5 = (int8_t)b2[5];
    _par_H6 = b2[6];
    _par_H7 = (int8_t)b2[7];
    _par_T1 = (uint16_t)(b2[8] | (b2[9] << 8));
    _par_G2 = (int16_t)(b2[10] | (b2[11] << 8));
    _par_G1 = (int8_t)b2[12];
    _par_G3 = (int8_t)b2[13];

    _res_heat_val = (int8_t)s1[0];
    _res_heat_range = (s2[0] >> 4) & 0x03;
}

uint8_t BME688Minimal::_calc_heater_resistance(int16_t target_temp, float ambient_temp) {
    int32_t var1 = (((int32_t)ambient_temp * _par_G3) / 10) << 8;
    int32_t var2 = (_par_G1 + 784) * (((((_par_G2 + 154009) * target_temp * 5) / 100) + 3276800) / 10);
    int32_t var3 = var1 + (var2 >> 1);
    int32_t var4 = var3 / (_res_heat_range + 4);
    int32_t var5 = (131 * _res_heat_val) + 65536;
    int32_t res_heat_x100 = ((var4 / var5) - 250) * 34;
    int32_t res_heat_x = (res_heat_x100 + 50) / 100;
    return (uint8_t)(res_heat_x & 0xFF);
}

uint8_t BME688Minimal::_calc_gas_wait(uint16_t target_ms) {
    if (target_ms <= 0x3F) {
        return (uint8_t)target_ms;
    } else if (target_ms <= 0x3F * 4) {
        return (uint8_t)((1 << 6) | (target_ms / 4));
    } else if (target_ms <= 0x3F * 16) {
        return (uint8_t)((2 << 6) | (target_ms / 16));
    } else {
        uint16_t val = target_ms / 64;
        if (val > 0x3F) val = 0x3F;
        return (uint8_t)((3 << 6) | val);
    }
}

void BME688Minimal::_setup_heater(uint8_t index, int16_t temp_c, uint16_t dur_ms) {
    uint8_t res = _calc_heater_resistance(temp_c, _ambient_temp);
    uint8_t gw = _calc_gas_wait(dur_ms);
    { uint8_t v = res; _connection.write(0x5A + index, &v, 1); }
    { uint8_t v = gw; _connection.write(0x64 + index, &v, 1); }
}

void BME688Minimal::_parse_field(const uint8_t* raw, uint32_t& press_adc, uint32_t& temp_adc,
                                 uint16_t& hum_adc, uint16_t& gas_adc, uint8_t& gas_range,
                                 uint8_t& gas_valid, uint8_t& heat_stab) {
    press_adc = ((uint32_t)raw[2] << 12) | ((uint32_t)raw[3] << 4) | (raw[4] >> 4);
    temp_adc  = ((uint32_t)raw[5] << 12) | ((uint32_t)raw[6] << 4) | (raw[7] >> 4);
    hum_adc   = ((uint16_t)raw[8] << 8) | raw[9];
    gas_adc   = ((uint16_t)raw[15] << 2) | (raw[16] >> 6);
    gas_range = raw[16] & 0x0F;
    gas_valid = (raw[16] >> 5) & 1;
    heat_stab = (raw[16] >> 4) & 1;
}

void BME688Minimal::_trigger_and_read(uint32_t& press_adc, uint32_t& temp_adc,
                                       uint16_t& hum_adc, uint16_t& gas_adc,
                                       uint8_t& gas_range, uint8_t& gas_valid,
                                       uint8_t& heat_stab) {
    { uint8_t v = _osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    uint8_t ctrl = (_osrs_t << 5) | (_osrs_p << 2) | 1;
    { uint8_t v = ctrl; _connection.write(REG_CTRL_MEAS, &v, 1); }
    // Poll new_data / measuring (timeout = heater time + 500 ms, 5 ms steps); on a
    // timeout the registers are read anyway and the gas value will be flagged invalid.
    uint32_t polls = ((uint32_t)_heat_dur + 500) / 5;
    for (uint32_t i = 0; i < polls; i++) {
        uint8_t st = 0;
        _connection.read(REG_MEAS_STATUS, &st, 1);
        if ((st & 0x80) && !(st & 0x20)) break;
        delay(5);
    }
    uint8_t raw[FIELD_LEN];
    _connection.read(REG_MEAS_STATUS, raw, FIELD_LEN);
    _parse_field(raw, press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
}

float BME688Minimal::_compensate_temp(uint32_t adc_T) {
    int32_t var1 = (int32_t)(adc_T >> 3) - ((int32_t)_par_T1 << 1);
    int32_t var2 = (var1 * (int32_t)_par_T2) >> 11;
    int32_t var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * ((int32_t)_par_T3 << 4) >> 14;
    _t_fine = var2 + var3;
    return (float)((_t_fine * 5 + 128) >> 8) / 100.0f;
}

float BME688Minimal::_compensate_pressure(uint32_t adc_P) {
    int32_t t_fine = _t_fine;
    int32_t var1 = (t_fine >> 1) - 64000;
    int32_t var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * (int32_t)_par_P6) >> 2;
    var2 = var2 + ((var1 * (int32_t)_par_P5) << 1);
    var2 = (var2 >> 2) + ((int32_t)_par_P4 << 16);
    var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * ((int32_t)_par_P3 << 5)) >> 3) + (((int32_t)_par_P2 * var1) >> 1);
    var1 = var1 >> 18;
    var1 = ((32768 + var1) * (int32_t)_par_P1) >> 15;
    int32_t press_comp = 1048576 - (int32_t)adc_P;
    press_comp = (int32_t)(((int64_t)(press_comp - (var2 >> 12)) * 3125));
    if (press_comp >= (1 << 30)) {
        press_comp = (press_comp / var1) << 1;
    } else {
        press_comp = (press_comp << 1) / var1;
    }
    var1 = ((int32_t)_par_P9 * (((press_comp >> 3) * (press_comp >> 3)) >> 13)) >> 12;
    var2 = ((press_comp >> 2) * (int32_t)_par_P8) >> 13;
    int32_t var3 = ((press_comp >> 8) * (press_comp >> 8) * (press_comp >> 8) * (int32_t)_par_P10) >> 17;
    press_comp = press_comp + ((var1 + var2 + var3 + ((int32_t)_par_P7 << 7)) >> 4);
    return (float)press_comp / 100.0f;
}

float BME688Minimal::_compensate_humidity(uint16_t hum_adc) {
    int32_t temp_scaled = _t_fine;
    int32_t var1 = (int32_t)hum_adc - (((int32_t)_par_H1 << 4) + (((temp_scaled * (int32_t)_par_H3) / 100) >> 1));
    int32_t var2 = ((int32_t)_par_H2 * (((temp_scaled * (int32_t)_par_H4) / 100) +
                     (((temp_scaled * ((temp_scaled * (int32_t)_par_H5) / 100)) >> 6) / 100) +
                     (1 << 14))) >> 10;
    int32_t var3 = var1 * var2;
    int32_t var4 = (((int32_t)_par_H6 << 7) + ((temp_scaled * (int32_t)_par_H7) / 100)) >> 4;
    int32_t var5 = ((var3 >> 14) * (var3 >> 14)) >> 10;
    int32_t var6 = (var4 * var5) >> 1;
    int32_t hum_comp = (((var3 + var6) >> 10) * 1000) >> 12;
    if (hum_comp < 0) hum_comp = 0;
    if (hum_comp > 100000) hum_comp = 100000;
    return (float)hum_comp / 1000.0f;
}

float BME688Minimal::_compensate_gas(uint16_t gas_adc, uint8_t gas_range) {
    int32_t var1 = 262144 >> gas_range;
    int32_t var2 = 4096 + 3 * ((int32_t)gas_adc - 512);
    if (var2 <= 0) return NAN;
    return (float)((10000 * (int64_t)var1 / var2) * 100);
}

float BME688Minimal::temperature() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    float t = _compensate_temp(temp_adc);
    _ambient_temp = t;
    return t;
}

float BME688Minimal::pressure() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    return _compensate_pressure(press_adc);
}

float BME688Minimal::humidity() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    return _compensate_humidity(hum_adc);
}

float BME688Minimal::gas_resistance() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    if (!gas_valid || !heat_stab) return NAN;
    return _compensate_gas(gas_adc, gas_range);
}

// BME688Full

BME688Full::BME688Full(RegisterConnection& connection)
    : BME688Minimal(connection) {
}

void BME688Full::configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h, uint8_t mode, uint8_t filter) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _osrs_h = osrs_h;
    _filter = filter;
    { uint8_t v = osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    { uint8_t v = filter << 2; _connection.write(REG_CONFIG, &v, 1); }
    { uint8_t v = (osrs_t << 5) | (osrs_p << 2) | mode; _connection.write(REG_CTRL_MEAS, &v, 1); }
}

void BME688Full::set_oversampling(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _osrs_h = osrs_h;
    { uint8_t v = osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    { uint8_t v = (osrs_t << 5) | (osrs_p << 2) | 0; _connection.write(REG_CTRL_MEAS, &v, 1); }
}

void BME688Full::set_filter(uint8_t coeff) {
    _filter = coeff;
    { uint8_t v = coeff << 2; _connection.write(REG_CONFIG, &v, 1); }
}

void BME688Full::set_heater(int16_t temp_c, uint16_t duration_ms) {
    _heat_temp = temp_c;
    _heat_dur = duration_ms;
    _setup_heater(0, temp_c, duration_ms);
    { uint8_t v = RUN_GAS | 0; _connection.write(REG_CTRL_GAS_1, &v, 1); }
}

void BME688Full::set_heater_profile(uint8_t index, int16_t temp_c, uint16_t duration_ms) {
    _setup_heater(index, temp_c, duration_ms);
}

void BME688Full::select_heater_profile(uint8_t index) {
    _nb_conv = index;
    uint8_t gas1 = _gas_enabled ? (RUN_GAS | index) : index;
    { uint8_t v = gas1; _connection.write(REG_CTRL_GAS_1, &v, 1); }
}

void BME688Full::set_gas_enabled(bool enabled) {
    _gas_enabled = enabled ? 1 : 0;
    uint8_t gas1 = enabled ? (RUN_GAS | _nb_conv) : _nb_conv;
    { uint8_t v = gas1; _connection.write(REG_CTRL_GAS_1, &v, 1); }
}

void BME688Full::set_heater_off(bool off) {
    { uint8_t v = off ? 0x08 : 0x00; _connection.write(REG_CTRL_GAS_0, &v, 1); }
}

void BME688Full::set_ambient_temperature(float temp_c) {
    _ambient_temp = temp_c;
    _setup_heater(_nb_conv, (int16_t)_heat_temp, _heat_dur);
}

void BME688Full::read_all(float& t, float& p, float& h, float& g) {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    t = _compensate_temp(temp_adc);
    _ambient_temp = t;
    p = _compensate_pressure(press_adc);
    h = _compensate_humidity(hum_adc);
    g = (gas_valid && heat_stab) ? _compensate_gas(gas_adc, gas_range) : NAN;
}

bool BME688Full::gas_valid() {
    uint8_t buf[1];
    _connection.read(0x2D, buf, 1);
    return (buf[0] >> 5) & 1;
}

bool BME688Full::heater_stable() {
    uint8_t buf[1];
    _connection.read(0x2D, buf, 1);
    return (buf[0] >> 4) & 1;
}

uint8_t BME688Full::status() {
    uint8_t buf[1];
    _connection.read(REG_MEAS_STATUS, buf, 1);
    return buf[0];
}

uint8_t BME688Full::chip_id() {
    uint8_t buf[1];
    _connection.read(REG_ID, buf, 1);
    return buf[0];
}

uint8_t BME688Full::variant_id() {
    uint8_t buf[1];
    _connection.read(REG_VARIANT_ID, buf, 1);
    return buf[0];
}

uint8_t BME688Full::_calc_shared_wait(float ms) {
    uint32_t units = (uint32_t)(ms / 0.477f + 0.5f);
    if (units <= 0x3F) return (uint8_t)units;
    if (units <= 0x3F * 4) return (uint8_t)((1 << 6) | (units / 4));
    if (units <= 0x3F * 16) return (uint8_t)((2 << 6) | (units / 16));
    uint32_t val = units / 64;
    if (val > 0x3F) val = 0x3F;
    return (uint8_t)((3 << 6) | val);
}

void BME688Full::start_parallel(const int16_t* temps, const uint8_t* repeats, uint8_t steps, float shared_wait_ms) {
    if (steps < 1 || steps > 10) return;
    for (uint8_t i = 0; i < steps; i++) {
        uint8_t res = _calc_heater_resistance(temps[i], _ambient_temp);
        uint8_t rep = repeats[i] ? repeats[i] : 1;
        { uint8_t v = res; _connection.write(0x5A + i, &v, 1); }
        { uint8_t v = rep; _connection.write(0x64 + i, &v, 1); }
    }
    { uint8_t v = _calc_shared_wait(shared_wait_ms); _connection.write(0x6E, &v, 1); }
    { uint8_t v = RUN_GAS | steps; _connection.write(REG_CTRL_GAS_1, &v, 1); }
    { uint8_t v = _osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    { uint8_t v = _filter << 2; _connection.write(REG_CONFIG, &v, 1); }
    { uint8_t v = (_osrs_t << 5) | (_osrs_p << 2) | MODE_PARALLEL; _connection.write(REG_CTRL_MEAS, &v, 1); }
}

uint8_t BME688Full::read_parallel(BME688Field* out, uint8_t max) {
    uint8_t raw[3 * FIELD_LEN];
    _connection.read(REG_MEAS_STATUS, raw, 3 * FIELD_LEN);
    uint8_t n = 0;
    for (uint8_t f = 0; f < 3 && n < max; f++) {
        const uint8_t* b = raw + f * FIELD_LEN;
        if (!(b[0] & 0x80)) continue;
        uint32_t press_adc, temp_adc;
        uint16_t hum_adc, gas_adc;
        uint8_t gas_range, gas_valid, heat_stab;
        _parse_field(b, press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
        BME688Field& e = out[n++];
        e.step = b[0] & 0x0F;
        e.sub_index = b[1];
        e.temperature = _compensate_temp(temp_adc);
        e.pressure = _compensate_pressure(press_adc);
        e.humidity = _compensate_humidity(hum_adc);
        e.gas_valid = gas_valid;
        e.heater_stable = heat_stab;
        e.gas_resistance = (gas_valid && heat_stab) ? _compensate_gas(gas_adc, gas_range) : NAN;
    }
    return n;
}

void BME688Full::stop_parallel() {
    { uint8_t v = (_osrs_t << 5) | (_osrs_p << 2) | MODE_SLEEP; _connection.write(REG_CTRL_MEAS, &v, 1); }
}

void BME688Full::reset() {
    { uint8_t v = RESET_CMD; _connection.write(REG_RESET, &v, 1); }
    delay(2);
    _read_calibration();
    { uint8_t v = _osrs_h; _connection.write(REG_CTRL_HUM, &v, 1); }
    { uint8_t v = _filter << 2; _connection.write(REG_CONFIG, &v, 1); }
    { uint8_t v = (_osrs_t << 5) | (_osrs_p << 2) | 0; _connection.write(REG_CTRL_MEAS, &v, 1); }
    _setup_heater(_nb_conv, (int16_t)_heat_temp, _heat_dur);
    uint8_t gas1 = _gas_enabled ? (RUN_GAS | _nb_conv) : _nb_conv;
    { uint8_t v = gas1; _connection.write(REG_CTRL_GAS_1, &v, 1); }
}
