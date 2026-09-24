#include "VL53L1X.h"
#include <stdlib.h>

namespace {
const uint16_t DEFAULT_CONFIG_START = 0x002D;

// ULD VL51L1X_DEFAULT_CONFIGURATION — opaque, written verbatim to
// 0x002D..0x0087, one byte per register.
const uint8_t DEFAULT_CONFIGURATION[] = {
    0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
    0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
    0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
    0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
    0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
    0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
};

// ULD status_rtn: raw RESULT__RANGE_STATUS (bits 4:0) -> mapped range status.
const uint8_t STATUS_MAP[24] = { 255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
                                 255, 255, 10, 6, 255, 255, 11, 12 };

// Timing budget table: ms, short A, short B, long A, long B (0 = not available).
const uint16_t BUDGETS[7][5] = {
    { 15,  0x001D, 0x0027, 0,      0      },
    { 20,  0x0051, 0x006E, 0x001E, 0x0022 },
    { 33,  0x00D6, 0x006E, 0x0060, 0x006E },
    { 50,  0x01AE, 0x01E8, 0x00AD, 0x00C6 },
    { 100, 0x02E1, 0x0388, 0x01CC, 0x01EA },
    { 200, 0x03E1, 0x0496, 0x02D9, 0x02F8 },
    { 500, 0x0591, 0x05C1, 0x048F, 0x04A4 },
};

const uint8_t CALIBRATION_SAMPLES = 50;

int32_t roundf_to_int(float x) { return x >= 0.0f ? (int32_t)(x + 0.5f) : -(int32_t)(-x + 0.5f); }

struct OffsetAcc {
    uint32_t distanceSum = 0;
};

struct XtalkAcc {
    uint32_t distanceSum = 0;
    float signalSum = 0.0f;
    float spadSum = 0.0f;
};
}  // namespace

// VL53L1XMinimal

VL53L1XMinimal::VL53L1XMinimal(Connection& connection) : VL53Base(connection, 2) {
    if (!_init()) {
        // Wrong chip / wrong address / wiring problem, or an init poll timed
        // out. abort() rather than throwing: exceptions are disabled per
        // platform convention in this repo.
        abort();
    }
}

bool VL53L1XMinimal::_init() {
    _bootWait();
    if (!_waitUntil([&]() { return (_rd8(REG_FIRMWARE_SYSTEM_STATUS) & 0x01) != 0; })) return false;
    if (_rd16(REG_MODEL_ID) != SENSOR_ID) return false;

    for (size_t i = 0; i < sizeof(DEFAULT_CONFIGURATION); i++) {
        _wr8((uint16_t)(DEFAULT_CONFIG_START + i), DEFAULT_CONFIGURATION[i]);
    }

    // 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
    _wr8(REG_PAD_I2C_HV_EXTSUP, 0x01);
    _wr8(REG_GPIO_EXTSUP_HV, 0x01);
    _wr8(REG_GPIO_HV_MUX_CTRL, 0x11);

    // Settling ranging (ULD SensorInit), then two-bound VHV from the previous temperature.
    _wr8(REG_MODE_START, 0x40);
    bool ok = _waitUntil([&]() { return _dataReady(); });
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    _wr8(REG_MODE_START, 0x00);
    _wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x09);
    _wr8(REG_VHV_INIT, 0x00);
    return ok;
}

bool VL53L1XMinimal::_dataReady() {
    // GPIO1 is active low: line asserted (bit 0 == 0) means data ready.
    return (_rd8(REG_GPIO_TIO_HV_STATUS) & 0x01) == 0;
}

void VL53L1XMinimal::_readResult() {
    _rdBlock(REG_RESULT_RANGE_STATUS, _result, sizeof(_result));
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    uint8_t raw = _result[0] & 0x1F;
    _rangeStatus = raw < sizeof(STATUS_MAP) ? STATUS_MAP[raw] : 255;
}

uint16_t VL53L1XMinimal::_waitAndRead() {
    if (!_waitUntil([&]() { return _dataReady(); })) return TIMEOUT;
    _readResult();
    return _resultWord(13);
}

uint16_t VL53L1XMinimal::distance() {
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    _wr8(REG_MODE_START, 0x10);
    return _waitAndRead();
}

bool VL53L1XMinimal::rangeValid() { return _rangeStatus == RANGE_STATUS_VALID; }

uint8_t VL53L1XMinimal::_activeSource() {
    uint8_t value = _rd8(REG_INTERRUPT_CONFIG_GPIO);
    if (value & 0x20) return SOURCE_NEW_SAMPLE_READY;
    static const uint8_t WINDOW_TO_SOURCE[4] = { SOURCE_LEVEL_LOW, SOURCE_LEVEL_HIGH, SOURCE_OUT_OF_WINDOW,
                                                 SOURCE_IN_WINDOW };
    return WINDOW_TO_SOURCE[value & 0x03];
}

uint8_t VL53L1XMinimal::_pollInterruptStatus() {
    if (!_dataReady()) return 0;
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    return _activeSource();
}

// VL53L1XFull

VL53L1XFull::VL53L1XFull(Connection& connection) : VL53L1XMinimal(connection) {}

bool VL53L1XFull::startContinuous(uint32_t periodMs) {
    if (periodMs > 60000) return false;
    uint32_t budgetMs = timingBudget() / 1000;
    uint32_t period = periodMs > budgetMs ? periodMs : budgetMs;
    if (period == 0) period = 1;
    setInterMeasurement(period);
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    _wr8(REG_MODE_START, 0x40);
    return true;
}

void VL53L1XFull::stopContinuous() { _wr8(REG_MODE_START, 0x00); }

uint16_t VL53L1XFull::readContinuous() { return _waitAndRead(); }

bool VL53L1XFull::dataReady() { return _dataReady(); }

VL53L1XFull::Measurement VL53L1XFull::readMeasurement() {
    _readResult();
    Measurement m;
    m.distanceMm = _resultWord(13);
    m.rangeStatus = _rangeStatus;
    m.signalRateMcps = (float)_resultWord(15) / 128.0f;
    m.ambientRateMcps = (float)_resultWord(7) / 128.0f;
    m.effectiveSpadCount = (float)_resultWord(3) / 256.0f;
    return m;
}

uint8_t VL53L1XFull::rangeStatus() { return _rangeStatus; }

bool VL53L1XFull::setTimingBudget(uint32_t budgetUs) {
    if (budgetUs % 1000) return false;
    DistanceMode mode = distanceMode();
    if (mode == DistanceMode::Unknown) return false;
    size_t col = mode == DistanceMode::Short ? 1 : 3;
    for (const auto& row : BUDGETS) {
        if (row[0] == budgetUs / 1000 && row[col] != 0) {
            _wr16(REG_RANGE_TIMEOUT_A, row[col]);
            _wr16(REG_RANGE_TIMEOUT_B, row[col + 1]);
            return true;
        }
    }
    return false;
}

uint32_t VL53L1XFull::timingBudget() {
    uint16_t a = _rd16(REG_RANGE_TIMEOUT_A);
    for (const auto& row : BUDGETS) {
        if (row[1] == a || (row[3] != 0 && row[3] == a)) return (uint32_t)row[0] * 1000;
    }
    return 0;
}

bool VL53L1XFull::setDistanceMode(DistanceMode mode) {
    if (mode == DistanceMode::Unknown) return false;
    uint32_t budget = timingBudget();
    if (budget == 0) budget = 100000;
    if (mode == DistanceMode::Long && budget == 15000) return false;
    bool isShort = mode == DistanceMode::Short;
    _wr8(REG_PHASECAL_TIMEOUT, isShort ? 0x14 : 0x0A);
    _wr8(REG_RANGE_VCSEL_PERIOD_A, isShort ? 0x07 : 0x0F);
    _wr8(REG_RANGE_VCSEL_PERIOD_B, isShort ? 0x05 : 0x0D);
    _wr8(REG_RANGE_VALID_PHASE_HIGH, isShort ? 0x38 : 0xB8);
    _wr16(REG_SD_WOI_SD0, isShort ? 0x0705 : 0x0F0D);
    _wr16(REG_SD_INITIAL_PHASE_SD0, isShort ? 0x0606 : 0x0E0E);
    return setTimingBudget(budget);
}

VL53L1XFull::DistanceMode VL53L1XFull::distanceMode() {
    uint8_t value = _rd8(REG_PHASECAL_TIMEOUT);
    if (value == 0x14) return DistanceMode::Short;
    if (value == 0x0A) return DistanceMode::Long;
    return DistanceMode::Unknown;
}

bool VL53L1XFull::setInterMeasurement(uint32_t periodMs) {
    if (periodMs < 1 || periodMs > 60000) return false;
    uint32_t clockPll = _rd16(REG_OSC_CALIBRATE_VAL) & 0x03FF;
    _wr32(REG_INTERMEASUREMENT_PERIOD, (uint32_t)(((uint64_t)clockPll * periodMs * 1075) / 1000));
    return true;
}

uint32_t VL53L1XFull::interMeasurement() {
    uint32_t clockPll = _rd16(REG_OSC_CALIBRATE_VAL) & 0x03FF;
    if (clockPll == 0) return 0;
    return (uint32_t)(((uint64_t)_rd32(REG_INTERMEASUREMENT_PERIOD) * 1000) / ((uint64_t)clockPll * 1075));
}

bool VL53L1XFull::setSignalRateLimit(float limitMcps) {
    if (limitMcps < 0.0f || limitMcps > 511.99f) return false;
    _wr16(REG_MIN_COUNT_RATE_RTN_LIMIT, (uint16_t)(limitMcps * 128.0f + 0.5f));
    return true;
}

float VL53L1XFull::signalRateLimit() { return (float)_rd16(REG_MIN_COUNT_RATE_RTN_LIMIT) / 128.0f; }

bool VL53L1XFull::setSigmaThreshold(uint16_t sigmaMm) {
    if (sigmaMm > 16383) return false;
    _wr16(REG_SIGMA_THRESH, (uint16_t)(sigmaMm << 2));
    return true;
}

uint16_t VL53L1XFull::sigmaThreshold() { return (uint16_t)(_rd16(REG_SIGMA_THRESH) >> 2); }

bool VL53L1XFull::setRoi(uint8_t width, uint8_t height) {
    if (width < 4 || width > 16 || height < 4 || height > 16) return false;
    if (width > 10 || height > 10) _wr8(REG_ROI_CENTRE_SPAD, 199);
    _wr8(REG_ROI_XY_SIZE, (uint8_t)(((height - 1) << 4) | (width - 1)));
    return true;
}

void VL53L1XFull::roi(uint8_t& width, uint8_t& height) {
    uint8_t value = _rd8(REG_ROI_XY_SIZE);
    width = (uint8_t)((value & 0x0F) + 1);
    height = (uint8_t)((value >> 4) + 1);
}

void VL53L1XFull::setRoiCenter(uint8_t spad) { _wr8(REG_ROI_CENTRE_SPAD, spad); }

uint8_t VL53L1XFull::roiCenter() { return _rd8(REG_ROI_CENTRE_SPAD); }

uint8_t VL53L1XFull::opticalCenter() { return _rd8(REG_MODE_ROI_CENTRE_SPAD); }

bool VL53L1XFull::setOffset(float offsetMm) {
    if (offsetMm < -1024.0f || offsetMm > 1023.75f) return false;
    _wr16(REG_PART_TO_PART_OFFSET, (uint16_t)(roundf_to_int(offsetMm * 4.0f) & 0x1FFF));
    _wr16(REG_MM_INNER_OFFSET, 0);
    _wr16(REG_MM_OUTER_OFFSET, 0);
    return true;
}

float VL53L1XFull::offset() {
    int16_t raw = (int16_t)(_rd16(REG_PART_TO_PART_OFFSET) & 0x1FFF);
    if (raw & 0x1000) raw -= 0x2000;
    return (float)raw * 0.25f;
}

bool VL53L1XFull::setCrosstalkCompensation(float rateMcps) {
    if (rateMcps < 0.0f || rateMcps >= 0.128f) return false;
    uint32_t raw = (uint32_t)(rateMcps * 512000.0f + 0.5f);
    _wr16(REG_XTALK_X_GRADIENT, 0);
    _wr16(REG_XTALK_Y_GRADIENT, 0);
    _wr16(REG_XTALK_PLANE_OFFSET, (uint16_t)(raw > 0xFFFF ? 0xFFFF : raw));
    return true;
}

float VL53L1XFull::crosstalkCompensation() { return (float)_rd16(REG_XTALK_PLANE_OFFSET) / 512000.0f; }

bool VL53L1XFull::_collect(void (*accumulate)(const uint8_t* result, void* ctx), void* ctx) {
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    _wr8(REG_MODE_START, 0x40);
    bool ok = true;
    for (uint8_t i = 0; i < CALIBRATION_SAMPLES && ok; i++) {
        ok = _waitUntil([&]() { return _dataReady(); });
        if (ok) {
            _readResult();
            accumulate(_result, ctx);
        }
    }
    _wr8(REG_MODE_START, 0x00);
    return ok;
}

bool VL53L1XFull::calibrateOffset(uint16_t targetMm, float& offsetMm) {
    _wr16(REG_PART_TO_PART_OFFSET, 0);
    _wr16(REG_MM_INNER_OFFSET, 0);
    _wr16(REG_MM_OUTER_OFFSET, 0);
    OffsetAcc acc;
    bool ok = _collect(
        [](const uint8_t* r, void* ctx) {
            static_cast<OffsetAcc*>(ctx)->distanceSum += (uint16_t)((r[13] << 8) | r[14]);
        },
        &acc);
    if (!ok) return false;
    float mean = (float)acc.distanceSum / CALIBRATION_SAMPLES;
    offsetMm = (float)targetMm - mean;
    return setOffset(offsetMm);
}

bool VL53L1XFull::calibrateCrosstalk(uint16_t targetMm, float& rateMcps) {
    if (targetMm == 0) return false;
    _wr16(REG_XTALK_PLANE_OFFSET, 0);
    XtalkAcc acc;
    bool ok = _collect(
        [](const uint8_t* r, void* ctx) {
            XtalkAcc* a = static_cast<XtalkAcc*>(ctx);
            a->distanceSum += (uint16_t)((r[13] << 8) | r[14]);
            a->signalSum += (float)((r[15] << 8) | r[16]) / 128.0f;
            a->spadSum += (float)((r[3] << 8) | r[4]) / 256.0f;
        },
        &acc);
    if (!ok) return false;
    float meanDistance = (float)acc.distanceSum / CALIBRATION_SAMPLES;
    float meanSignal = acc.signalSum / CALIBRATION_SAMPLES;
    float meanSpads = acc.spadSum / CALIBRATION_SAMPLES;
    float rate = 0.0f;
    if (meanSpads > 0.0f) rate = meanSignal * (1.0f - meanDistance / (float)targetMm) / meanSpads;
    if (rate < 0.0f) rate = 0.0f;
    if (rate > 0.127f) rate = 0.127f;
    rateMcps = rate;
    return setCrosstalkCompensation(rate);
}

bool VL53L1XFull::recalibrate() {
    _wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x81);
    _wr8(REG_VHV_INIT, 0x92);
    _wr8(REG_MODE_START, 0x40);
    bool ok = _waitUntil([&]() { return _dataReady(); });
    _wr8(REG_INTERRUPT_CLEAR, 0x01);
    _wr8(REG_MODE_START, 0x00);
    _wr8(REG_VHV_CONFIG_LOOP_BOUND, 0x09);
    _wr8(REG_VHV_INIT, 0x00);
    return ok;
}

bool VL53L1XFull::setAddress(uint8_t address) { return _setAddressReg(REG_I2C_SLAVE_DEVICE_ADDRESS, address); }

bool VL53L1XFull::setInterruptThresholds(uint16_t lowMm, uint16_t highMm) {
    if (highMm < lowMm) return false;
    _wr16(REG_THRESH_HIGH, highMm);
    _wr16(REG_THRESH_LOW, lowMm);
    return true;
}

void VL53L1XFull::interruptThresholds(uint16_t& lowMm, uint16_t& highMm) {
    lowMm = _rd16(REG_THRESH_LOW);
    highMm = _rd16(REG_THRESH_HIGH);
}

uint8_t VL53L1XFull::modelId() { return _rd8(REG_MODEL_ID); }

uint8_t VL53L1XFull::moduleType() { return _rd8(REG_MODULE_TYPE); }

uint8_t VL53L1XFull::revisionId() { return _rd8(REG_REVISION_ID); }

bool VL53L1XFull::enableInterrupt(uint8_t source) {
    uint8_t config;
    switch (source) {
        case SOURCE_LEVEL_LOW: config = 0x00; break;
        case SOURCE_LEVEL_HIGH: config = 0x01; break;
        case SOURCE_OUT_OF_WINDOW: config = 0x02; break;
        case SOURCE_NEW_SAMPLE_READY: config = 0x20; break;
        case SOURCE_IN_WINDOW: config = 0x03; break;
        default: return false;
    }
    _wr8(REG_INTERRUPT_CONFIG_GPIO, config);
    return true;
}

void VL53L1XFull::disableInterrupt(uint8_t source) {
    if (source != SOURCE_NEW_SAMPLE_READY && _activeSource() == source) _wr8(REG_INTERRUPT_CONFIG_GPIO, 0x20);
}

uint8_t VL53L1XFull::pollInterrupt() { return _pollInterruptStatus(); }

void VL53L1XFull::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) { _subscribe(callback, intPin); }

void VL53L1XFull::offInterrupt() { _unsubscribe(); }
