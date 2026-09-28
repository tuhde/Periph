package magnetometer

import (
	"testing"
)

func lastWriteToReg(conn *mockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func closeF64(a, b float64) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < 1e-9
}

func TestHMC5883LMinimalConstructionAndMagneticField(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewHMC5883LMinimal(conn)
	if err != nil {
		t.Fatalf("NewHMC5883LMinimal: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x00); v != 0x70 {
		t.Errorf("init Config A = %#x, want 0x70", v)
	}
	if v, _ := lastWriteToReg(conn, 0x01); v != 0x20 {
		t.Errorf("init Config B = %#x, want 0x20", v)
	}
	if v, _ := lastWriteToReg(conn, 0x02); v != 0x00 {
		t.Errorf("init Mode = %#x, want 0x00", v)
	}

	// X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss)
	conn.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0) // x=1000,z=-500,y=2000
	x, y, z, err := chip.MagneticField()
	if err != nil {
		t.Fatalf("MagneticField: %v", err)
	}
	if !closeF64(x, (1000.0/1090.0)*1e-4) || !closeF64(y, (2000.0/1090.0)*1e-4) || !closeF64(z, (-500.0/1090.0)*1e-4) {
		t.Errorf("MagneticField() = (%v, %v, %v)", x, y, z)
	}
}

func TestHMC5883LFullConfigureAndSetGain(t *testing.T) {
	conn := newMockConnection()
	full, err := NewHMC5883LFull(conn)
	if err != nil {
		t.Fatalf("NewHMC5883LFull: %v", err)
	}

	if err := full.Configure(30.0, 4, 5); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x00); v != 0x54 { // MA=10,DO=101
		t.Errorf("Configure Config A = %#x, want 0x54", v)
	}
	if v, _ := lastWriteToReg(conn, 0x01); v != 0xA0 { // GN=101
		t.Errorf("Configure Config B = %#x, want 0xA0", v)
	}
	if full.gain != 5 {
		t.Errorf("gain = %d, want 5", full.gain)
	}

	if err := full.Configure(30.0, 3, 1); err == nil {
		t.Error("Configure with bad averaging: expected error, got nil")
	}
	if err := full.Configure(100.0, 4, 1); err == nil {
		t.Error("Configure with bad odr: expected error, got nil")
	}
	if err := full.Configure(30.0, 4, 8); err == nil {
		t.Error("Configure with bad gain: expected error, got nil")
	}

	if err := full.SetGain(2); err != nil {
		t.Fatalf("SetGain: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x01); v != (2 << 5) {
		t.Errorf("SetGain Config B = %#x, want %#x", v, 2<<5)
	}
	if err := full.SetGain(9); err == nil {
		t.Error("SetGain(9): expected error, got nil")
	}
}

func TestHMC5883LFullSetMode(t *testing.T) {
	conn := newMockConnection()
	full, _ := NewHMC5883LFull(conn)

	if err := full.SetMode("continuous"); err != nil {
		t.Fatalf("SetMode(continuous): %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x02); v != 0b00 {
		t.Errorf("SetMode(continuous) = %#x, want 0x00", v)
	}
	if err := full.SetMode("single"); err != nil {
		t.Fatalf("SetMode(single): %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x02); v != 0b01 {
		t.Errorf("SetMode(single) = %#x, want 0x01", v)
	}
	if err := full.SetMode("idle"); err != nil {
		t.Fatalf("SetMode(idle): %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x02); v != 0b10 {
		t.Errorf("SetMode(idle) = %#x, want 0x02", v)
	}
	if err := full.SetMode("bogus"); err == nil {
		t.Error("SetMode(bogus): expected error, got nil")
	}
}

func TestHMC5883LFullDataReadyAndStatus(t *testing.T) {
	conn := newMockConnection()
	full, _ := NewHMC5883LFull(conn)

	conn.setRegister(0x09, 0x01)
	ready, err := full.DataReady()
	if err != nil || !ready {
		t.Errorf("DataReady() = %v, %v, want true, nil", ready, err)
	}
	status, err := full.Status()
	if err != nil || status != 0x01 {
		t.Errorf("Status() = %#x, %v, want 0x01, nil", status, err)
	}

	conn.setRegister(0x09, 0x02) // LOCK set, RDY clear
	ready, err = full.DataReady()
	if err != nil || ready {
		t.Errorf("DataReady() = %v, %v, want false, nil", ready, err)
	}
}

func TestHMC5883LFullSingleMeasurement(t *testing.T) {
	conn := newMockConnection()
	full, _ := NewHMC5883LFull(conn)

	conn.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0) // x=1000,z=-500,y=2000
	x, _, _, err := full.SingleMeasurement()
	if err != nil {
		t.Fatalf("SingleMeasurement: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x02); v != 0x01 {
		t.Errorf("SingleMeasurement Mode write = %#x, want 0x01", v)
	}
	if !closeF64(x, (1000.0/full.gainLsb)*1e-4) {
		t.Errorf("SingleMeasurement x = %v", x)
	}
}

func TestHMC5883LFullIdentify(t *testing.T) {
	conn := newMockConnection()
	full, _ := NewHMC5883LFull(conn)
	conn.setRegister(0x0A, 0x48, 0x34, 0x33)
	idA, idB, idC, err := full.Identify()
	if err != nil || idA != 0x48 || idB != 0x34 || idC != 0x33 {
		t.Errorf("Identify() = (%#x %#x %#x), %v", idA, idB, idC, err)
	}
}

func TestHMC5883LFullSelfTest(t *testing.T) {
	conn := newMockConnection()
	full, _ := NewHMC5883LFull(conn)
	conn.setRegister(0x00, 0x70) // current Config A (post-init)
	conn.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)

	x, _, _, err := full.SelfTest(true)
	if err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	var sawPositiveBias bool
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == 0x00 && w[1] == 0x71 { // 0x70|0b01
			sawPositiveBias = true
		}
	}
	if !sawPositiveBias {
		t.Error("SelfTest(true): expected a Config A write of 0x71 (positive bias)")
	}
	if v, _ := lastWriteToReg(conn, 0x00); v != 0x70 {
		t.Errorf("SelfTest did not restore normal mode: Config A = %#x, want 0x70", v)
	}
	if !closeF64(x, (1000.0/full.gainLsb)*1e-4) {
		t.Errorf("SelfTest x = %v", x)
	}
}
