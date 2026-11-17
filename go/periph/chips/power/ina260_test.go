package power

import (
	"testing"
)

// mockConnection and newMockConnection are defined in ina219_test.go
// (shared within this package - Go doesn't allow redeclaring a type/func
// across files in the same package).

func writeIs(w []byte, reg, hi, lo byte) bool {
	return len(w) == 3 && w[0] == reg && w[1] == hi && w[2] == lo
}

func TestINA260FullAPI(t *testing.T) {
	conn := newMockConnection()

	// Construction makes no bus access: no calibration, chip power-on state is the default.
	sensor, err := NewINA260Full(conn)
	if err != nil {
		t.Fatalf("NewINA260Full: %v", err)
	}
	if len(conn.writes) != 0 {
		t.Errorf("init: expected no writes, got %v", conn.writes)
	}

	// Voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
	conn.setRegister(ina260RegBus, 0x19, 0x00)
	if v, err := sensor.Voltage(); err != nil || v != 8.0 {
		t.Errorf("Voltage() = %v, %v, want 8.0, nil", v, err)
	}

	// Current: raw signed = -100 (0xFF9C) -> -0.125 A
	conn.setRegister(ina260RegCurrent, 0xFF, 0x9C)
	if v, err := sensor.Current(); err != nil || v != -0.125 {
		t.Errorf("Current() = %v, %v, want -0.125, nil", v, err)
	}

	// Current: datasheet example 0x2710 = 10000 -> 12.5 A
	conn.setRegister(ina260RegCurrent, 0x27, 0x10)
	if v, err := sensor.Current(); err != nil || v != 12.5 {
		t.Errorf("Current() = %v, %v, want 12.5, nil", v, err)
	}

	// Power: datasheet example 0x3A7F = 14975 -> 149.75 W
	conn.setRegister(ina260RegPower, 0x3A, 0x7F)
	if v, err := sensor.Power(); err != nil || v < 149.749 || v > 149.751 {
		t.Errorf("Power() = %v, %v, want 149.75, nil", v, err)
	}

	last := func() []byte { return conn.writes[len(conn.writes)-1] }

	// Configure(2, 3, 5, 6) -> 0x6000|(2<<9)|(3<<6)|(5<<3)|6 = 0x64EE
	if err := sensor.Configure(2, 3, 5, 6); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if !writeIs(last(), ina260RegConfig, 0x64, 0xEE) {
		t.Errorf("Configure: expected CONFIG=0x64EE, got %v", last())
	}

	// Trigger(3) with the same settings -> 0x64EB
	if err := sensor.Trigger(INA260ModeShuntBusTriggered); err != nil {
		t.Fatalf("Trigger: %v", err)
	}
	if !writeIs(last(), ina260RegConfig, 0x64, 0xEB) {
		t.Errorf("Trigger: expected CONFIG=0x64EB, got %v", last())
	}

	// ConversionReady(): CVRF bit (0x0008) set
	conn.setRegister(ina260RegMask, 0x00, 0x08)
	if v, err := sensor.ConversionReady(); err != nil || !v {
		t.Errorf("ConversionReady() = %v, %v, want true, nil", v, err)
	}

	// Overflow(): OVF bit (0x0004) set
	conn.setRegister(ina260RegMask, 0x00, 0x04)
	if v, err := sensor.Overflow(); err != nil || !v {
		t.Errorf("Overflow() = %v, %v, want true, nil", v, err)
	}

	// SetAlert(POL, 1.5 W, true, true): limit 150 (0x0096) first, then mask 0x0803
	conn.writes = nil
	if err := sensor.SetAlert(INA260POL, 1.5, true, true); err != nil {
		t.Fatalf("SetAlert: %v", err)
	}
	if len(conn.writes) != 2 || !writeIs(conn.writes[0], ina260RegAlert, 0x00, 0x96) ||
		!writeIs(conn.writes[1], ina260RegMask, 0x08, 0x03) {
		t.Errorf("SetAlert(POL): got %v", conn.writes)
	}

	// SetAlert(OCL, 5.0 A): 4000 (0x0FA0), mask 0x8000
	conn.writes = nil
	if err := sensor.SetAlert(INA260OCL, 5.0, false, false); err != nil {
		t.Fatalf("SetAlert: %v", err)
	}
	if len(conn.writes) != 2 || !writeIs(conn.writes[0], ina260RegAlert, 0x0F, 0xA0) ||
		!writeIs(conn.writes[1], ina260RegMask, 0x80, 0x00) {
		t.Errorf("SetAlert(OCL): got %v", conn.writes)
	}

	// SetAlert(UCL, -1.0 A): -800 -> 0xFCE0
	conn.writes = nil
	if err := sensor.SetAlert(INA260UCL, -1.0, false, false); err != nil {
		t.Fatalf("SetAlert: %v", err)
	}
	if len(conn.writes) != 2 || !writeIs(conn.writes[0], ina260RegAlert, 0xFC, 0xE0) {
		t.Errorf("SetAlert(UCL): got %v", conn.writes)
	}

	// SetAlert(CNVR): limit register not written
	conn.writes = nil
	if err := sensor.SetAlert(INA260CNVR, 0, false, false); err != nil {
		t.Fatalf("SetAlert: %v", err)
	}
	if len(conn.writes) != 1 || !writeIs(conn.writes[0], ina260RegMask, 0x04, 0x00) {
		t.Errorf("SetAlert(CNVR): got %v", conn.writes)
	}

	// ClearAlert(): Mask/Enable = 0
	if err := sensor.ClearAlert(); err != nil {
		t.Fatalf("ClearAlert: %v", err)
	}
	if !writeIs(last(), ina260RegMask, 0x00, 0x00) {
		t.Errorf("ClearAlert: got %v", last())
	}

	// AlertFlags(): raw Mask/Enable register
	conn.setRegister(ina260RegMask, 0x08, 0x03)
	if v, err := sensor.AlertFlags(); err != nil || v != 0x0803 {
		t.Errorf("AlertFlags() = %v, %v, want 0x0803, nil", v, err)
	}

	// Reset(): CONFIG = 0x8000
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if !writeIs(last(), ina260RegConfig, 0x80, 0x00) {
		t.Errorf("Reset: got %v", last())
	}

	// Shutdown(): MODE = 000 with default settings -> 0x6120
	if err := sensor.Shutdown(); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	if !writeIs(last(), ina260RegConfig, 0x61, 0x20) {
		t.Errorf("Shutdown: got %v", last())
	}

	// Wake(): restore MODE = 111 -> 0x6127
	if err := sensor.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if !writeIs(last(), ina260RegConfig, 0x61, 0x27) {
		t.Errorf("Wake: got %v", last())
	}

	// ManufacturerID() / DieID()
	conn.setRegister(ina260RegMfrID, 0x54, 0x49)
	if v, err := sensor.ManufacturerID(); err != nil || v != 0x5449 {
		t.Errorf("ManufacturerID() = %v, %v, want 0x5449, nil", v, err)
	}
	conn.setRegister(ina260RegDieID, 0x22, 0x70)
	if v, err := sensor.DieID(); err != nil || v != 0x2270 {
		t.Errorf("DieID() = %v, %v, want 0x2270, nil", v, err)
	}
}
