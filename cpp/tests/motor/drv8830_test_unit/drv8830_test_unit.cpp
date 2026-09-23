#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "DRV8830.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_CONTROL = 0x00;
static const uint8_t REG_FAULT   = 0x01;

static int lastWrite(const I2CConnectionMock& mock, uint8_t reg) {
    int v = -1;
    for (const auto& w : mock.writes()) if (w.size() == 2 && w[0] == reg) v = w[1];
    return v;
}

int main() {
    // drive(): voltage-to-VSET conversion, direction bits, floor and clamp.
    {
        I2CConnectionMock mock;
        DRV8830Minimal motor(mock);
        check_true(mock.writes().empty(), "init_no_register_writes");
        motor.drive(3.0f);
        check_true(lastWrite(mock, REG_CONTROL) == ((37 << 2) | 0x01), "drive_forward_3v");
        motor.drive(-2.0f);
        check_true(lastWrite(mock, REG_CONTROL) == ((25 << 2) | 0x02), "drive_reverse_2v");
        motor.drive(0.0f);
        check_true(lastWrite(mock, REG_CONTROL) == 0x00, "drive_zero_coasts");
        motor.drive(0.4f);
        check_true(lastWrite(mock, REG_CONTROL) == 0x00, "drive_below_floor_coasts");
        motor.drive(0.48f);
        check_true(lastWrite(mock, REG_CONTROL) == ((6 << 2) | 0x01), "drive_floor_vset6");
        motor.drive(9.0f);
        check_true(lastWrite(mock, REG_CONTROL) == ((63 << 2) | 0x01), "drive_clamps_to_vset63");
        motor.brake();
        check_true(lastWrite(mock, REG_CONTROL) == 0x03, "brake_writes_0x03");
        motor.stop();
        check_true(lastWrite(mock, REG_CONTROL) == 0x00, "stop_writes_0x00");
    }

    // setOutput(): raw fields; reserved codes rejected without a write.
    {
        I2CConnectionMock mock;
        DRV8830Full motor(mock);
        check_true(motor.setOutput(20, false, true), "set_output_accepts_valid");
        check_true(lastWrite(mock, REG_CONTROL) == ((20 << 2) | 0x02), "set_output_raw");
        size_t n = mock.writes().size();
        check_true(!motor.setOutput(5, true, false) && mock.writes().size() == n, "set_output_rejects_reserved");
    }

    // readOutput(): decodes VSET and direction.
    {
        I2CConnectionMock mock;
        DRV8830Full motor(mock);
        mock.setRegister(REG_CONTROL, {(uint8_t)((63 << 2) | 0x01)});
        DRV8830Full::Output o = motor.readOutput();
        check_true(o.direction == DRV8830Full::Direction::Forward && std::fabs(o.voltage - 5.06f) < 0.01f, "read_output_forward");
        mock.setRegister(REG_CONTROL, {(uint8_t)((16 << 2) | 0x02)});
        o = motor.readOutput();
        check_true(o.direction == DRV8830Full::Direction::Reverse && std::fabs(o.voltage - 1.285f) < 0.001f, "read_output_reverse");
        mock.setRegister(REG_CONTROL, {0x03});
        o = motor.readOutput();
        check_true(o.direction == DRV8830Full::Direction::Brake && o.voltage == 0.0f, "read_output_brake");
        mock.setRegister(REG_CONTROL, {0x00});
        o = motor.readOutput();
        check_true(o.direction == DRV8830Full::Direction::Coast && o.voltage == 0.0f, "read_output_coast");
    }

    // readFault()/pollInterrupt(): decode bits, never clear; clearFault() writes CLEAR.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_FAULT, {0x01 | 0x10});
        DRV8830Full motor(mock);
        DRV8830Full::Fault f = motor.readFault();
        check_true(f.fault && !f.ocp && !f.uvlo && !f.ots && f.ilimit, "read_fault_ilimit");
        mock.setRegister(REG_FAULT, {0x01 | 0x02 | 0x04 | 0x08});
        f = motor.pollInterrupt();
        check_true(f.fault && f.ocp && f.uvlo && f.ots && !f.ilimit, "poll_interrupt_ocp_uvlo_ots");
        check_true(lastWrite(mock, REG_FAULT) == -1, "read_fault_does_not_clear");
        motor.clearFault();
        check_true(lastWrite(mock, REG_FAULT) == 0x80, "clear_fault_writes_0x80");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
