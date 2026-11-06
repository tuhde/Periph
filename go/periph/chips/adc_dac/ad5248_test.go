package adcdac

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ad5248Mock is an in-memory RegisterConnection: a register write [instr, data...]
// stores data at instr, a register read returns the stored byte.
type ad5248Mock struct {
	mockConnection
}

func newAD5248Mock() *ad5248Mock {
	return &ad5248Mock{mockConnection: *newMockConnection()}
}

func (m *ad5248Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *ad5248Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

var _ connection.RegisterConnection = (*ad5248Mock)(nil)

func TestAD5248FullAPI(t *testing.T) {
	conn := newAD5248Mock()
	chip := NewAD5248Full(conn, 10000, 160)
	if len(conn.writes) != 0 {
		t.Fatalf("construction produced I2C traffic")
	}

	expectWrite := func(label string, want ...byte) {
		t.Helper()
		if got := lastWrite(conn.writes); !bytesEqual(got, want) {
			t.Errorf("%s: last write = %v, want %v", label, got, want)
		}
	}

	chip.SetWiper(0, 100)
	expectWrite("set_wiper_ch0", 0x00, 100)
	chip.SetWiper(1, 200)
	expectWrite("set_wiper_ch1", 0x80, 200)
	chip.SetWiper(0, 999)
	expectWrite("clamp_high", 0x00, 255)
	chip.SetWiper(0, -5)
	expectWrite("clamp_low", 0x00, 0)
	if err := chip.SetWiper(2, 0); err == nil {
		t.Errorf("invalid channel did not return an error")
	}

	// GetWiper sends the instruction byte alone, then reads one byte.
	chip.SetWiper(0, 100)
	n := len(conn.writes)
	if v, err := chip.GetWiper(0); err != nil || v != 100 {
		t.Errorf("GetWiper(0) = %v, %v, want 100", v, err)
	}
	if len(conn.writes) != n+1 || !bytesEqual(lastWrite(conn.writes), []byte{0x00}) {
		t.Errorf("GetWiper did not send the instruction byte alone")
	}

	// R = D/256*RAB + 2*RW.
	chip.SetWiper(0, 128)
	if r, _ := chip.GetResistance(0); math.Abs(r-5320) > 1e-9 {
		t.Errorf("GetResistance = %v, want 5320", r)
	}
	chip.SetResistance(1, 5320)
	expectWrite("set_resistance", 0x80, 128)
	chip.SetResistance(1, 1)
	expectWrite("set_resistance_low", 0x80, 0)
	chip.SetResistance(1, 1e9)
	expectWrite("set_resistance_high", 0x80, 255)

	chip.SetBoth(10, 20)
	if !bytesEqual(conn.writes[len(conn.writes)-2], []byte{0x00, 10}) {
		t.Errorf("SetBoth first write wrong")
	}
	expectWrite("set_both", 0x80, 20)
	chip.SetRatio(0, 0.5)
	expectWrite("set_ratio", 0x00, 128)
	chip.SetRatio(0, 1.0)
	expectWrite("set_ratio_clamp", 0x00, 255)
	chip.SetWiper(1, 64)
	if r, _ := chip.GetRatio(1); r != 0.25 {
		t.Errorf("GetRatio = %v, want 0.25", r)
	}

	chip.SetWiper(0, 250)
	if v, _ := chip.Increment(0, 10); v != 255 {
		t.Errorf("Increment clamp = %v, want 255", v)
	}
	if v, _ := chip.Increment(0, -5); v != 250 {
		t.Errorf("Decrement = %v, want 250", v)
	}

	chip.SetWiper(1, 77)
	chip.Shutdown(1)
	expectWrite("shutdown", 0xC0, 77)
	if s, _ := chip.IsShutdown(1); !s {
		t.Errorf("IsShutdown(1) = false after Shutdown")
	}
	chip.SetWiper(1, 99)
	expectWrite("set_wiper_keeps_sd", 0xC0, 99)
	chip.Resume(1)
	expectWrite("resume", 0x80, 99)
	if s, _ := chip.IsShutdown(1); s {
		t.Errorf("IsShutdown(1) = true after Resume")
	}

	chip.Shutdown(0)
	chip.Reset()
	if !bytesEqual(conn.writes[len(conn.writes)-2], []byte{0x00, 0x80}) {
		t.Errorf("Reset first write wrong")
	}
	expectWrite("reset", 0x80, 0x80)
	if s, _ := chip.IsShutdown(0); s {
		t.Errorf("Reset did not clear shutdown")
	}

	n = len(conn.writes)
	chip.SetRab(50000, 100)
	if len(conn.writes) != n {
		t.Errorf("SetRab produced I2C traffic")
	}
	chip.SetWiper(0, 128)
	if r, _ := chip.GetResistance(0); math.Abs(r-25200) > 1e-9 {
		t.Errorf("GetResistance after SetRab = %v, want 25200", r)
	}
}
