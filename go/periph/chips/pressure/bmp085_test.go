package pressure

import (
	"math"
	"testing"
)

func preloadBmp085Calibration(conn *mockConnection) {
	// Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
	// AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
	conn.setRegister(0xAA,
		0x01, 0x98, // AC1 = 408
		0xFF, 0xB8, // AC2 = -72
		0xC7, 0xD1, // AC3 = -14383
		0x7F, 0xE5, // AC4 = 32741
		0x7F, 0xF5, // AC5 = 32757
		0x5A, 0x71, // AC6 = 23153
		0x18, 0x2E, // B1 = 6190
		0x00, 0x04, // B2 = 4
		0x80, 0x00, // MB = -32768
		0xDD, 0xF9, // MC = -8711
		0x0B, 0x34, // MD = 2868
	)
	conn.setRegister(0xD0, 0x55) // chip ID (Go's constructor verifies it)
}

func newBmp085Connection() *mockConnection {
	conn := newMockConnection()
	preloadBmp085Calibration(conn)
	return conn
}

func TestBmp085MinimalConstructionAndReadings(t *testing.T) {
	conn := newBmp085Connection()
	chip, err := NewBmp085Minimal(conn)
	if err != nil {
		t.Fatalf("NewBmp085Minimal: %v", err)
	}

	conn.setRegister(0xF6, 0x6C, 0xFA) // UT = 27898
	temp, err := chip.Temperature()
	if err != nil || temp != 15.0 {
		t.Errorf("Temperature() = %v, %v, want 15.0, nil", temp, err)
	}
	if v, _ := lastWriteToReg(conn, 0xF4); v != 0x2E {
		t.Errorf("Temperature() CTRL_MEAS write = %#x, want 0x2E", v)
	}

	conn.setRegister(0xF6, 0x6C, 0xFA)
	p, err := chip.Pressure()
	if err != nil || math.Abs(p-82080.0) > 1e-6 {
		t.Errorf("Pressure() = %v, %v, want ~82080.0, nil", p, err)
	}
}

func TestBmp085MinimalConstructionBadChipID(t *testing.T) {
	conn := newMockConnection()
	preloadBmp085Calibration(conn)
	conn.setRegister(0xD0, 0x00) // wrong chip ID
	if _, err := NewBmp085Minimal(conn); err == nil {
		t.Error("NewBmp085Minimal with bad chip ID: expected error, got nil")
	}
}

func TestBmp085MinimalConstructionBadCalibration(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(0xD0, 0x55)
	conn.setRegister(0xAA, make([]byte, 22)...) // every word 0x0000
	if _, err := NewBmp085Minimal(conn); err == nil {
		t.Error("NewBmp085Minimal with all-zero calibration: expected error, got nil")
	}

	conn2 := newMockConnection()
	conn2.setRegister(0xD0, 0x55)
	ffff := make([]byte, 22)
	for i := range ffff {
		ffff[i] = 0xFF
	}
	conn2.setRegister(0xAA, ffff...)
	if _, err := NewBmp085Minimal(conn2); err == nil {
		t.Error("NewBmp085Minimal with all-0xFFFF calibration: expected error, got nil")
	}
}

func TestBmp085FullOversampling(t *testing.T) {
	conn := newBmp085Connection()
	full, err := NewBmp085Full(conn)
	if err != nil {
		t.Fatalf("NewBmp085Full: %v", err)
	}
	if full.Oversampling() != 0 {
		t.Errorf("default Oversampling() = %d, want 0", full.Oversampling())
	}
	full.SetOversampling(OssHighRes)
	if full.Oversampling() != OssHighRes {
		t.Errorf("Oversampling() = %d, want %d", full.Oversampling(), OssHighRes)
	}
	conn.setRegister(0xF6, 0x6C, 0xFA, 0x00)
	if _, err := full.Pressure(); err != nil {
		t.Fatalf("Pressure: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0xF4); v != 0xB4 {
		t.Errorf("Pressure() at OSS=2 CTRL_MEAS write = %#x, want 0xB4", v)
	}
}

func TestBmp085FullAltitudeAndSeaLevelPressure(t *testing.T) {
	conn := newBmp085Connection()
	full, _ := NewBmp085Full(conn)

	conn.setRegister(0xF6, 0x6C, 0xFA)
	alt, err := full.Altitude()
	if err != nil || alt <= 0 {
		t.Errorf("Altitude() = %v, %v, want > 0, nil", alt, err)
	}

	conn.setRegister(0xF6, 0x6C, 0xFA)
	slp, err := full.SeaLevelPressure(0.0)
	if err != nil || math.Abs(slp-82080.0) > 1e-6 {
		t.Errorf("SeaLevelPressure(0.0) = %v, %v, want ~82080.0, nil", slp, err)
	}
}

func TestBmp085FullChipIDAndReset(t *testing.T) {
	conn := newBmp085Connection()
	full, _ := NewBmp085Full(conn)

	id, err := full.ChipID()
	if err != nil || id != 0x55 {
		t.Errorf("ChipID() = %#x, %v, want 0x55, nil", id, err)
	}

	if err := full.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0xE0); v != 0xB6 {
		t.Errorf("Reset() SOFT_RESET write = %#x, want 0xB6", v)
	}
}
