#ifdef __linux__
#include "DHTxxTransportLinux.h"
#include <gpiod.h>
#include <time.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>

static int64_t now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

DHTxxTransportLinux::DHTxxTransportLinux(const char* chip_path, unsigned int line_num)
    : _chip_path(strdup(chip_path)),
      _line_num(line_num),
      _line_num_out(0),
      _two_pin(false),
      _chip(nullptr),
      _input_req(nullptr),
      _output_req(nullptr),
      _closed(false)
{
    _chip = gpiod_chip_open(_chip_path);
    if (!_chip) return;
    // Default: request the line as input with bias pull-up.
    gpiod_line_settings* settings = gpiod_line_settings_new();
    gpiod_line_settings_set_direction(settings, GPIOD_LINE_DIRECTION_INPUT);
    gpiod_line_settings_set_bias(settings, GPIOD_LINE_BIAS_PULL_UP);
    gpiod_line_config* line_cfg = gpiod_line_config_new();
    gpiod_line_config_add_line_settings(line_cfg, &_line_num, 1, settings);
    gpiod_request_config* req_cfg = gpiod_request_config_new();
    gpiod_request_config_set_consumer(req_cfg, "dhtxx");
    _input_req = gpiod_chip_request_lines((gpiod_chip*)_chip, req_cfg, line_cfg);
    gpiod_request_config_free(req_cfg);
    gpiod_line_config_free(line_cfg);
    gpiod_line_settings_free(settings);
}

DHTxxTransportLinux::DHTxxTransportLinux(const char* chip_path, unsigned int line_num, unsigned int line_num_out)
    : _chip_path(strdup(chip_path)),
      _line_num(line_num),
      _line_num_out(line_num_out),
      _two_pin(true),
      _chip(nullptr),
      _input_req(nullptr),
      _output_req(nullptr),
      _closed(false)
{
    _chip = gpiod_chip_open(_chip_path);
    if (!_chip) return;
    // Request input line with pull-up bias.
    {
        gpiod_line_settings* in_settings = gpiod_line_settings_new();
        gpiod_line_settings_set_direction(in_settings, GPIOD_LINE_DIRECTION_INPUT);
        gpiod_line_settings_set_bias(in_settings, GPIOD_LINE_BIAS_PULL_UP);
        gpiod_line_config* in_cfg = gpiod_line_config_new();
        gpiod_line_config_add_line_settings(in_cfg, &_line_num, 1, in_settings);
        gpiod_request_config* in_req_cfg = gpiod_request_config_new();
        gpiod_request_config_set_consumer(in_req_cfg, "dhtxx-in");
        _input_req = gpiod_chip_request_lines((gpiod_chip*)_chip, in_req_cfg, in_cfg);
        gpiod_request_config_free(in_req_cfg);
        gpiod_line_config_free(in_cfg);
        gpiod_line_settings_free(in_settings);
    }
    // Request output line as open-drain.
    {
        gpiod_line_settings* out_settings = gpiod_line_settings_new();
        gpiod_line_settings_set_direction(out_settings, GPIOD_LINE_DIRECTION_OUTPUT);
        gpiod_line_settings_set_drive(out_settings, GPIOD_LINE_DRIVE_OPEN_DRAIN);
        gpiod_line_settings_set_output_value(out_settings, GPIOD_LINE_VALUE_INACTIVE);
        gpiod_line_config* out_cfg = gpiod_line_config_new();
        gpiod_line_config_add_line_settings(out_cfg, &_line_num_out, 1, out_settings);
        gpiod_request_config* out_req_cfg = gpiod_request_config_new();
        gpiod_request_config_set_consumer(out_req_cfg, "dhtxx-out");
        _output_req = gpiod_chip_request_lines((gpiod_chip*)_chip, out_req_cfg, out_cfg);
        gpiod_request_config_free(out_req_cfg);
        gpiod_line_config_free(out_cfg);
        gpiod_line_settings_free(out_settings);
    }
}

DHTxxTransportLinux::~DHTxxTransportLinux() {
    close();
}

void DHTxxTransportLinux::_drive_low() {
    if (_two_pin) {
        if (_output_req) {
            gpiod_line_request_set_value((gpiod_line_request*)_output_req, _line_num_out, GPIOD_LINE_VALUE_ACTIVE);
        }
    } else {
        if (_input_req) {
            gpiod_line_request_release((gpiod_line_request*)_input_req);
            _input_req = nullptr;
        }
        if (!_output_req) {
            gpiod_line_settings* settings = gpiod_line_settings_new();
            gpiod_line_settings_set_direction(settings, GPIOD_LINE_DIRECTION_OUTPUT);
            gpiod_line_settings_set_output_value(settings, GPIOD_LINE_VALUE_ACTIVE);
            gpiod_line_config* line_cfg = gpiod_line_config_new();
            gpiod_line_config_add_line_settings(line_cfg, &_line_num, 1, settings);
            gpiod_request_config* req_cfg = gpiod_request_config_new();
            gpiod_request_config_set_consumer(req_cfg, "dhtxx-out");
            _output_req = gpiod_chip_request_lines((gpiod_chip*)_chip, req_cfg, line_cfg);
            gpiod_request_config_free(req_cfg);
            gpiod_line_config_free(line_cfg);
            gpiod_line_settings_free(settings);
        }
    }
}

void DHTxxTransportLinux::_release_bus() {
    if (_two_pin) {
        if (_output_req) {
            gpiod_line_request_set_value((gpiod_line_request*)_output_req, _line_num_out, GPIOD_LINE_VALUE_INACTIVE);
        }
    } else {
        if (_output_req) {
            gpiod_line_request_release((gpiod_line_request*)_output_req);
            _output_req = nullptr;
        }
        if (!_input_req) {
            gpiod_line_settings* settings = gpiod_line_settings_new();
            gpiod_line_settings_set_direction(settings, GPIOD_LINE_DIRECTION_INPUT);
            gpiod_line_settings_set_bias(settings, GPIOD_LINE_BIAS_PULL_UP);
            gpiod_line_config* line_cfg = gpiod_line_config_new();
            gpiod_line_config_add_line_settings(line_cfg, &_line_num, 1, settings);
            gpiod_request_config* req_cfg = gpiod_request_config_new();
            gpiod_request_config_set_consumer(req_cfg, "dhtxx-in");
            _input_req = gpiod_chip_request_lines((gpiod_chip*)_chip, req_cfg, line_cfg);
            gpiod_request_config_free(req_cfg);
            gpiod_line_config_free(line_cfg);
            gpiod_line_settings_free(settings);
        }
    }
}

int32_t DHTxxTransportLinux::_measure_pulse(int level, uint32_t timeout_us) {
    if (!_input_req) return -1;
    int64_t deadline = now_ns() + (int64_t)timeout_us * 1000LL;
    int value;
    do {
        value = gpiod_line_request_get_value((gpiod_line_request*)_input_req, _line_num);
        if (now_ns() >= deadline) return -1;
    } while ((value != 0) != (level != 0));
    int64_t pulse_start = now_ns();
    do {
        value = gpiod_line_request_get_value((gpiod_line_request*)_input_req, _line_num);
        if (now_ns() >= deadline) return -1;
    } while ((value != 0) == (level != 0));
    return (int32_t)((now_ns() - pulse_start) / 1000LL);
}

bool DHTxxTransportLinux::read(uint8_t* out) {
    if (!_chip) return false;
    _drive_low();
    usleep(_START_LOW_MS * 1000);
    _release_bus();

    int32_t elapsed = _measure_pulse(0, _RESPONSE_TIMEOUT_US);
    if (elapsed < 0) return false;
    elapsed = _measure_pulse(1, _RESPONSE_TIMEOUT_US);
    if (elapsed < 0) return false;

    for (uint8_t byte_idx = 0; byte_idx < 5; byte_idx++) {
        uint8_t byte = 0;
        for (uint8_t bit_idx = 0; bit_idx < 8; bit_idx++) {
            elapsed = _measure_pulse(0, _BIT_TIMEOUT_US);
            if (elapsed < 0) return false;
            elapsed = _measure_pulse(1, _BIT_TIMEOUT_US);
            if (elapsed < 0) return false;
            byte = (byte << 1) | (elapsed > (int32_t)_BIT_THRESHOLD_US ? 1 : 0);
        }
        out[byte_idx] = byte;
    }
    return true;
}

void DHTxxTransportLinux::close() {
    if (_closed) return;
    _closed = true;
    if (_input_req)  { gpiod_line_request_release((gpiod_line_request*)_input_req);  _input_req  = nullptr; }
    if (_output_req) { gpiod_line_request_release((gpiod_line_request*)_output_req); _output_req = nullptr; }
    if (_chip)       { gpiod_chip_close((gpiod_chip*)_chip);                          _chip       = nullptr; }
    if (_chip_path)  { free(_chip_path); _chip_path = nullptr; }
}
#endif // __linux__
