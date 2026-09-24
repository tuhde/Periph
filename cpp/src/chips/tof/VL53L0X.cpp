#include "VL53L0X.h"
#include <stdlib.h>

namespace {
const uint32_t MIN_TIMING_BUDGET_US = 20000;

// Timing-budget overheads, µs.
const uint32_t START_OVERHEAD       = 1910;
const uint32_t END_OVERHEAD         = 960;
const uint32_t MSRC_OVERHEAD        = 660;
const uint32_t TCC_OVERHEAD         = 590;
const uint32_t DSS_OVERHEAD         = 690;
const uint32_t PRE_RANGE_OVERHEAD   = 660;
const uint32_t FINAL_RANGE_OVERHEAD = 550;

// ST DefaultTuningSettings — opaque, written verbatim in this order as (reg, value) pairs.
const uint8_t TUNING[] = {
    0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
    0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
    0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
    0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
    0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
    0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
    0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
    0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
    0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
};

// Final-range phase settings per VCSEL period: pclks, VALID_PHASE_HIGH,
// VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT, page-1 PHASECAL_LIM.
const uint8_t FINAL_PHASE[4][6] = {
    { 8,  0x10, 0x08, 0x02, 0x0C, 0x30 },
    { 10, 0x28, 0x08, 0x03, 0x09, 0x20 },
    { 12, 0x38, 0x08, 0x03, 0x08, 0x20 },
    { 14, 0x48, 0x08, 0x03, 0x07, 0x20 },
};

int32_t roundf_to_int(float x) { return x >= 0.0f ? (int32_t)(x + 0.5f) : -(int32_t)(-x + 0.5f); }
}  // namespace

// VL53L0XMinimal

VL53L0XMinimal::VL53L0XMinimal(Connection& connection) : VL53Base(connection, 1) {
    if (!_init()) {
        // Wrong chip / wrong address / wiring problem, or an init poll timed
        // out. abort() rather than throwing: exceptions are disabled per
        // platform convention in this repo.
        abort();
    }
}

bool VL53L0XMinimal::_wait(uint8_t reg, uint8_t mask, bool untilSet) {
    return _waitUntil([&]() { return ((_rd(reg) & mask) != 0) == untilSet; });
}

uint32_t VL53L0XMinimal::_mclksToUs(uint32_t mclks, uint16_t pclks) {
    return (mclks * _macroPeriodNs(pclks) + 500) / 1000;
}

uint32_t VL53L0XMinimal::_usToMclks(uint32_t us, uint16_t pclks) {
    uint32_t period = _macroPeriodNs(pclks);
    return (us * 1000 + period / 2) / period;
}

uint32_t VL53L0XMinimal::_decodeTimeout(uint16_t reg) {
    return ((uint32_t)(reg & 0xFF) << (reg >> 8)) + 1;
}

uint16_t VL53L0XMinimal::_encodeTimeout(uint32_t mclks) {
    if (mclks == 0) return 0;
    uint32_t ls = mclks - 1;
    uint16_t ms = 0;
    while (ls > 0xFF) {
        ls >>= 1;
        ms++;
    }
    return (uint16_t)((ms << 8) | (ls & 0xFF));
}

bool VL53L0XMinimal::_init() {
    _bootWait();

    if (_rd(REG_MODEL_ID) != MODEL_ID) return false;

    // 2V8 I/O mode, standard I²C mode.
    _wr(REG_VHV_PAD_EXTSUP_HV, _rd(REG_VHV_PAD_EXTSUP_HV) | 0x01);
    _wr(REG_I2C_MODE, 0x00);

    // Stop variable.
    _wr(REG_POWER_FORCE, 0x01);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_SYSRANGE_START, 0x00);
    _stopVariable = _rd(REG_STOP_VARIABLE);
    _wr(REG_SYSRANGE_START, 0x01);
    _wr(REG_PAGE_SELECT, 0x00);
    _wr(REG_POWER_FORCE, 0x00);

    // Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
    _wr(REG_MSRC_CONFIG_CONTROL, _rd(REG_MSRC_CONFIG_CONTROL) | 0x12);
    _wr16(REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020);
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, 0xFF);

    uint8_t spadCount = 0;
    bool spadIsAperture = false;
    if (!_spadInfo(spadCount, spadIsAperture)) return false;

    // Reference SPADs.
    uint8_t refMap[6];
    _rdBlock(REG_SPAD_ENABLES_REF_0, refMap, 6);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_DYNAMIC_SPAD_START_OFFSET, 0x00);
    _wr(REG_DYNAMIC_SPAD_NUM_REQ, 0x2C);
    _wr(REG_PAGE_SELECT, 0x00);
    _wr(REG_REF_EN_START_SELECT, 0xB4);
    uint8_t first = spadIsAperture ? 12 : 0;
    uint8_t enabled = 0;
    for (uint8_t i = 0; i < 48; i++) {
        uint8_t& byte = refMap[i / 8];
        if (i < first || enabled == spadCount) {
            byte &= (uint8_t)~(1 << (i % 8));
        } else if ((byte >> (i % 8)) & 0x01) {
            enabled++;
        }
    }
    _wrBlock(REG_SPAD_ENABLES_REF_0, refMap, 6);

    // Default tuning settings.
    for (size_t i = 0; i < sizeof(TUNING); i += 2) _wr(TUNING[i], TUNING[i + 1]);

    // GPIO1 = new sample ready, active low.
    _wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x04);
    _wr(REG_GPIO_HV_MUX_ACTIVE_HIGH, _rd(REG_GPIO_HV_MUX_ACTIVE_HIGH) & (uint8_t)~0x10);
    _wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01);

    uint32_t budget = _getTimingBudget();
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, SEQ_OPERATING);
    if (!_setTimingBudget(budget)) return false;

    return _refCalibration();
}

bool VL53L0XMinimal::_spadInfo(uint8_t& count, bool& isAperture) {
    _wr(REG_POWER_FORCE, 0x01);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_SYSRANGE_START, 0x00);
    _wr(REG_PAGE_SELECT, 0x06);
    _wr(0x83, _rd(0x83) | 0x04);
    _wr(REG_PAGE_SELECT, 0x07);
    _wr(0x81, 0x01);
    _wr(REG_POWER_FORCE, 0x01);
    _wr(0x94, 0x6B);
    _wr(0x83, 0x00);
    bool ok = _wait(0x83, 0xFF, true);
    _wr(0x83, 0x01);
    uint8_t tmp = _rd(0x92);
    _wr(0x81, 0x00);
    _wr(REG_PAGE_SELECT, 0x06);
    _wr(0x83, _rd(0x83) & (uint8_t)~0x04);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_SYSRANGE_START, 0x01);
    _wr(REG_PAGE_SELECT, 0x00);
    _wr(REG_POWER_FORCE, 0x00);
    count = tmp & 0x7F;
    isAperture = (tmp >> 7) & 0x01;
    return ok;
}

bool VL53L0XMinimal::_singleRefCalibration(uint8_t vhvInit) {
    _wr(REG_SYSRANGE_START, 0x01 | vhvInit);
    bool ok = _wait(REG_RESULT_INTERRUPT_STATUS, 0x07, true);
    _wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
    _wr(REG_SYSRANGE_START, 0x00);
    return ok;
}

bool VL53L0XMinimal::_refCalibration() {
    uint8_t seq = _rd(REG_SYSTEM_SEQUENCE_CONFIG);
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x01);
    bool ok = _singleRefCalibration(0x40);
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02);
    ok = _singleRefCalibration(0x00) && ok;
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, seq);
    return ok;
}

VL53L0XMinimal::StepTimeouts VL53L0XMinimal::_stepTimeouts(uint8_t enables) {
    StepTimeouts t;
    t.prePclks = _decodeVcsel(_rd(REG_PRE_RANGE_VCSEL_PERIOD));
    t.msrcUs = _mclksToUs((uint32_t)_rd(REG_MSRC_CONFIG_TIMEOUT) + 1, t.prePclks);
    t.preMclks = _decodeTimeout(_rd16(REG_PRE_RANGE_TIMEOUT));
    t.preUs = _mclksToUs(t.preMclks, t.prePclks);
    t.finalPclks = _decodeVcsel(_rd(REG_FINAL_RANGE_VCSEL_PERIOD));
    uint32_t finalMclks = _decodeTimeout(_rd16(REG_FINAL_RANGE_TIMEOUT));
    if (enables & SEQ_PRE_RANGE) finalMclks -= t.preMclks;
    t.finalUs = _mclksToUs(finalMclks, t.finalPclks);
    return t;
}

uint32_t VL53L0XMinimal::_fixedOverheadUs(uint8_t enables, const StepTimeouts& t) {
    uint32_t budget = START_OVERHEAD + END_OVERHEAD;
    if (enables & SEQ_TCC) budget += t.msrcUs + TCC_OVERHEAD;
    if (enables & SEQ_DSS) {
        budget += 2 * (t.msrcUs + DSS_OVERHEAD);
    } else if (enables & SEQ_MSRC) {
        budget += t.msrcUs + MSRC_OVERHEAD;
    }
    if (enables & SEQ_PRE_RANGE) budget += t.preUs + PRE_RANGE_OVERHEAD;
    return budget;
}

uint32_t VL53L0XMinimal::_getTimingBudget() {
    uint8_t enables = _rd(REG_SYSTEM_SEQUENCE_CONFIG);
    StepTimeouts t = _stepTimeouts(enables);
    uint32_t budget = _fixedOverheadUs(enables, t);
    if (enables & SEQ_FINAL_RANGE) budget += t.finalUs + FINAL_RANGE_OVERHEAD;
    return budget;
}

bool VL53L0XMinimal::_setTimingBudget(uint32_t budgetUs) {
    if (budgetUs < MIN_TIMING_BUDGET_US) return false;
    uint8_t enables = _rd(REG_SYSTEM_SEQUENCE_CONFIG);
    StepTimeouts t = _stepTimeouts(enables);
    uint32_t used = _fixedOverheadUs(enables, t);
    if (enables & SEQ_FINAL_RANGE) {
        used += FINAL_RANGE_OVERHEAD;
        if (used > budgetUs) return false;
        uint32_t finalMclks = _usToMclks(budgetUs - used, t.finalPclks);
        if (enables & SEQ_PRE_RANGE) finalMclks += t.preMclks;
        _wr16(REG_FINAL_RANGE_TIMEOUT, _encodeTimeout(finalMclks));
    }
    _timingBudgetUs = budgetUs;
    return true;
}

void VL53L0XMinimal::_stopVariablePreamble() {
    _wr(REG_POWER_FORCE, 0x01);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_SYSRANGE_START, 0x00);
    _wr(REG_STOP_VARIABLE, _stopVariable);
    _wr(REG_SYSRANGE_START, 0x01);
    _wr(REG_PAGE_SELECT, 0x00);
    _wr(REG_POWER_FORCE, 0x00);
}

void VL53L0XMinimal::_readResult() {
    _rdBlock(REG_RESULT_RANGE_STATUS, _result, 12);
    _wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
    _rangeStatus = (_result[0] & 0x78) >> 3;
}

uint16_t VL53L0XMinimal::_waitAndRead() {
    if (!_wait(REG_RESULT_INTERRUPT_STATUS, 0x07, true)) return TIMEOUT;
    _readResult();
    return (uint16_t)((_result[10] << 8) | _result[11]);
}

uint16_t VL53L0XMinimal::distance() {
    _stopVariablePreamble();
    _wr(REG_SYSRANGE_START, 0x01);
    if (!_wait(REG_SYSRANGE_START, 0x01, false)) return TIMEOUT;
    return _waitAndRead();
}

bool VL53L0XMinimal::rangeValid() { return _rangeStatus == RANGE_STATUS_VALID; }

uint8_t VL53L0XMinimal::_pollInterruptStatus() {
    uint8_t status = _rd(REG_RESULT_INTERRUPT_STATUS) & 0x07;
    if (status) _wr(REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
    return status;
}

// VL53L0XFull

VL53L0XFull::VL53L0XFull(Connection& connection) : VL53L0XMinimal(connection) {}

void VL53L0XFull::startContinuous(uint32_t periodMs) {
    _stopVariablePreamble();
    if (periodMs > 0) {
        uint16_t osc = _rd16(REG_OSC_CALIBRATE_VAL);
        if (osc != 0) periodMs *= osc;
        _wr32(REG_SYSTEM_INTERMEASUREMENT, periodMs);
        _wr(REG_SYSRANGE_START, 0x04);
    } else {
        _wr(REG_SYSRANGE_START, 0x02);
    }
}

void VL53L0XFull::stopContinuous() {
    _wr(REG_SYSRANGE_START, 0x01);
    _wr(REG_PAGE_SELECT, 0x01);
    _wr(REG_SYSRANGE_START, 0x00);
    _wr(REG_STOP_VARIABLE, 0x00);
    _wr(REG_SYSRANGE_START, 0x01);
    _wr(REG_PAGE_SELECT, 0x00);
}

uint16_t VL53L0XFull::readContinuous() { return _waitAndRead(); }

bool VL53L0XFull::dataReady() { return (_rd(REG_RESULT_INTERRUPT_STATUS) & 0x07) != 0; }

VL53L0XFull::Measurement VL53L0XFull::readMeasurement() {
    _readResult();
    Measurement m;
    m.distanceMm = (uint16_t)((_result[10] << 8) | _result[11]);
    m.rangeStatus = _rangeStatus;
    m.signalRateMcps = (float)((_result[6] << 8) | _result[7]) / 128.0f;
    m.ambientRateMcps = (float)((_result[8] << 8) | _result[9]) / 128.0f;
    m.effectiveSpadCount = (float)((_result[2] << 8) | _result[3]) / 256.0f;
    return m;
}

uint8_t VL53L0XFull::rangeStatus() { return _rangeStatus; }

bool VL53L0XFull::setTimingBudget(uint32_t budgetUs) { return _setTimingBudget(budgetUs); }

uint32_t VL53L0XFull::timingBudget() { return _getTimingBudget(); }

bool VL53L0XFull::setSignalRateLimit(float limitMcps) {
    if (limitMcps < 0.0f || limitMcps > 511.99f) return false;
    _wr16(REG_FINAL_MIN_COUNT_RATE_RTN, (uint16_t)(limitMcps * 128.0f + 0.5f));
    return true;
}

float VL53L0XFull::signalRateLimit() { return (float)_rd16(REG_FINAL_MIN_COUNT_RATE_RTN) / 128.0f; }

bool VL53L0XFull::setVcselPulsePeriod(VcselPeriodType type, uint8_t pclks) {
    uint8_t preHigh = 0;
    const uint8_t* fin = nullptr;
    if (type == VcselPeriodType::PreRange) {
        switch (pclks) {
            case 12: preHigh = 0x18; break;
            case 14: preHigh = 0x30; break;
            case 16: preHigh = 0x40; break;
            case 18: preHigh = 0x50; break;
            default: return false;
        }
    } else {
        for (auto& row : FINAL_PHASE) {
            if (row[0] == pclks) fin = row;
        }
        if (!fin) return false;
    }

    uint8_t enables = _rd(REG_SYSTEM_SEQUENCE_CONFIG);
    StepTimeouts t = _stepTimeouts(enables);
    uint8_t vcsel = _encodeVcsel(pclks);

    if (type == VcselPeriodType::PreRange) {
        _wr(REG_PRE_VALID_PHASE_HIGH, preHigh);
        _wr(REG_PRE_VALID_PHASE_LOW, 0x08);
        _wr(REG_PRE_RANGE_VCSEL_PERIOD, vcsel);
        _wr16(REG_PRE_RANGE_TIMEOUT, _encodeTimeout(_usToMclks(t.preUs, pclks)));
        uint32_t m = _usToMclks(t.msrcUs, pclks);
        _wr(REG_MSRC_CONFIG_TIMEOUT, m > 256 ? 255 : (uint8_t)(m - 1));
    } else {
        _wr(REG_FINAL_VALID_PHASE_HIGH, fin[1]);
        _wr(REG_FINAL_VALID_PHASE_LOW, fin[2]);
        _wr(REG_GLOBAL_CONFIG_VCSEL_WIDTH, fin[3]);
        _wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[4]);
        _wr(REG_PAGE_SELECT, 0x01);
        _wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[5]);
        _wr(REG_PAGE_SELECT, 0x00);
        _wr(REG_FINAL_RANGE_VCSEL_PERIOD, vcsel);
        uint32_t f = _usToMclks(t.finalUs, pclks);
        if (enables & SEQ_PRE_RANGE) f += t.preMclks;
        _wr16(REG_FINAL_RANGE_TIMEOUT, _encodeTimeout(f));
    }

    _setTimingBudget(_timingBudgetUs);
    uint8_t seq = _rd(REG_SYSTEM_SEQUENCE_CONFIG);
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02);
    bool ok = _singleRefCalibration(0x00);
    _wr(REG_SYSTEM_SEQUENCE_CONFIG, seq);
    return ok;
}

uint8_t VL53L0XFull::vcselPulsePeriod(VcselPeriodType type) {
    uint8_t reg = type == VcselPeriodType::PreRange ? REG_PRE_RANGE_VCSEL_PERIOD : REG_FINAL_RANGE_VCSEL_PERIOD;
    return (uint8_t)_decodeVcsel(_rd(reg));
}

bool VL53L0XFull::setProfile(Profile profile) {
    float limit = 0.25f;
    uint8_t pre = 14, finalPclks = 10;
    uint32_t budget = 33000;
    switch (profile) {
        case Profile::Default: break;
        case Profile::LongRange: limit = 0.10f; pre = 18; finalPclks = 14; break;
        case Profile::HighSpeed: budget = 20000; break;
        case Profile::HighAccuracy: budget = 200000; break;
    }
    setSignalRateLimit(limit);
    bool ok = setVcselPulsePeriod(VcselPeriodType::PreRange, pre);
    ok = setVcselPulsePeriod(VcselPeriodType::FinalRange, finalPclks) && ok;
    return _setTimingBudget(budget) && ok;
}

bool VL53L0XFull::setOffset(float offsetMm) {
    if (offsetMm < -512.0f || offsetMm > 511.75f) return false;
    _wr16(REG_PART_TO_PART_RANGE_OFFSET, (uint16_t)(roundf_to_int(offsetMm * 4.0f) & 0x0FFF));
    return true;
}

float VL53L0XFull::offset() {
    int16_t raw = (int16_t)(_rd16(REG_PART_TO_PART_RANGE_OFFSET) & 0x0FFF);
    if (raw & 0x0800) raw -= 0x1000;
    return (float)raw * 0.25f;
}

bool VL53L0XFull::setCrosstalkCompensation(float rateMcps) {
    if (rateMcps < 0.0f || rateMcps >= 8.0f) return false;
    _wr16(REG_CROSSTALK_COMPENSATION, (uint16_t)(rateMcps * 8192.0f + 0.5f));
    return true;
}

bool VL53L0XFull::recalibrate() { return _refCalibration(); }

bool VL53L0XFull::setAddress(uint8_t address) { return _setAddressReg(REG_I2C_SLAVE_DEVICE_ADDRESS, address); }

bool VL53L0XFull::setInterruptThresholds(uint16_t lowMm, uint16_t highMm) {
    if (highMm < lowMm || highMm > 8190) return false;
    _wr16(REG_SYSTEM_THRESH_LOW, (uint16_t)((lowMm / 2) & 0x0FFF));
    _wr16(REG_SYSTEM_THRESH_HIGH, (uint16_t)((highMm / 2) & 0x0FFF));
    return true;
}

void VL53L0XFull::interruptThresholds(uint16_t& lowMm, uint16_t& highMm) {
    lowMm = (uint16_t)((_rd16(REG_SYSTEM_THRESH_LOW) & 0x0FFF) * 2);
    highMm = (uint16_t)((_rd16(REG_SYSTEM_THRESH_HIGH) & 0x0FFF) * 2);
}

uint8_t VL53L0XFull::modelId() { return _rd(REG_MODEL_ID); }

uint8_t VL53L0XFull::revisionId() { return _rd(REG_REVISION_ID); }

bool VL53L0XFull::enableInterrupt(uint8_t source) {
    if (source < SOURCE_LEVEL_LOW || source > SOURCE_NEW_SAMPLE_READY) return false;
    _wr(REG_SYSTEM_INTERRUPT_CONFIG, source);
    return true;
}

void VL53L0XFull::disableInterrupt(uint8_t source) {
    if ((_rd(REG_SYSTEM_INTERRUPT_CONFIG) & 0x07) == source) _wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x00);
}

uint8_t VL53L0XFull::pollInterrupt() { return _pollInterruptStatus(); }

void VL53L0XFull::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) { _subscribe(callback, intPin); }

void VL53L0XFull::offInterrupt() { _unsubscribe(); }
