package other

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func findWrite(writes [][]byte, reg, value byte) bool {
	for _, w := range writes {
		if len(w) == 2 && w[0] == reg && w[1] == value {
			return true
		}
	}
	return false
}

func TestMPR121MinimalConstruction(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMPR121Minimal(conn)
	if err != nil {
		t.Fatalf("NewMPR121Minimal: %v", err)
	}
	if !findWrite(conn.writes, mpr121RegSRST, mpr121SoftResetKey) {
		t.Errorf("init: SRST not written")
	}
	if !findWrite(conn.writes, mpr121RegECR, mpr121ECRDefault) {
		t.Errorf("init: ECR not written with default 0x8C")
	}
	if !findWrite(conn.writes, mpr121RegE0TTH+0, mpr121TouchDefault) {
		t.Errorf("init: ELE0_TTH not written")
	}
	if !findWrite(conn.writes, mpr121RegE0RTH+0, mpr121ReleaseDefault) {
		t.Errorf("init: ELE0_RTH not written")
	}
	_ = chip
}

func TestMPR121TouchedDecodes(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(mpr121RegELE0_7Touch, 0x5A, 0x05)
	chip, err := NewMPR121Minimal(conn)
	if err != nil {
		t.Fatalf("NewMPR121Minimal: %v", err)
	}
	touched, err := chip.Touched()
	if err != nil {
		t.Fatalf("Touched: %v", err)
	}
	if touched != 0x5A|((0x05&0x0F)<<8) {
		t.Errorf("Touched = 0x%04X, want 0x05%02X", touched, 0x5A)
	}
}

func TestMPR121IsTouchedPerElectrode(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(mpr121RegELE0_7Touch, 0x28, 0x08)
	chip, err := NewMPR121Minimal(conn)
	if err != nil {
		t.Fatalf("NewMPR121Minimal: %v", err)
	}
	if v, _ := chip.IsTouched(5); !v {
		t.Errorf("IsTouched(5) = false, want true")
	}
	if v, _ := chip.IsTouched(11); !v {
		t.Errorf("IsTouched(11) = false, want true (byte 1 bit 3)")
	}
	if v, _ := chip.IsTouched(0); v {
		t.Errorf("IsTouched(0) = true, want false")
	}
}

func TestMPR121IsTouchedRejectsOutOfRange(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMPR121Minimal(conn)
	if err != nil {
		t.Fatalf("NewMPR121Minimal: %v", err)
	}
	if _, err := chip.IsTouched(12); err == nil {
		t.Errorf("IsTouched(12) should error")
	}
}

func TestMPR121FilteredDecodes10Bit(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(0x04, 0x80, 0x02)
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if v, err := chip.Filtered(0); err != nil || v != 0x280 {
		t.Errorf("Filtered(0) = 0x%X (%v), want 0x280", v, err)
	}
}

func TestMPR121BaselineShiftsLeft2(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(0x1E, 0x80)
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if v, err := chip.Baseline(0); err != nil || v != 0x200 {
		t.Errorf("Baseline(0) = 0x%X (%v), want 0x200", v, err)
	}
}

func TestMPR121SetBaselineShiftsRight2(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if err := chip.SetBaseline(0, 0x300); err != nil {
		t.Fatalf("SetBaseline: %v", err)
	}
	if !findWrite(conn.writes, 0x1E, 0xC0) {
		t.Errorf("SetBaseline(0, 0x300) did not write 0xC0 to 0x1E")
	}
}

func TestMPR121ProximityTouched(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(mpr121RegELE8_ProxTch, 0x10)
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if v, _ := chip.ProximityTouched(); !v {
		t.Errorf("ProximityTouched at 0x01=0x10 should be true")
	}
	conn.setRegister(mpr121RegELE8_ProxTch, 0x00)
	if v, _ := chip.ProximityTouched(); v {
		t.Errorf("ProximityTouched at 0x01=0x00 should be false")
	}
}

func TestMPR121ClearOvercurrent(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(mpr121RegELE8_ProxTch, 0x80)
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if err := chip.ClearOvercurrent(); err != nil {
		t.Fatalf("ClearOvercurrent: %v", err)
	}
	found := false
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == mpr121RegELE8_ProxTch && (w[1]&0x80) == 0 {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("ClearOvercurrent did not write 0x01 with bit 7 cleared")
	}
}

func TestMPR121ConfigureSampling(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if err := chip.ConfigureSampling(10, 2, 1, 2, 5); err != nil {
		t.Fatalf("ConfigureSampling: %v", err)
	}
	if !findWrite(conn.writes, mpr121RegCDCConfig, 0x4A) {
		t.Errorf("ConfigureSampling: CDC_CONFIG not written 0x4A")
	}
	if !findWrite(conn.writes, mpr121RegCDTConfig, 0x4D) {
		t.Errorf("ConfigureSampling: CDT_CONFIG not written 0x4D")
	}
}

func TestMPR121ConfigureDebounce(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if err := chip.ConfigureDebounce(3, 5); err != nil {
		t.Fatalf("ConfigureDebounce: %v", err)
	}
	if !findWrite(conn.writes, mpr121RegDebounce, 0x53) {
		t.Errorf("ConfigureDebounce: DEBOUNCE not written 0x53")
	}
}

func TestMPR121EnableDisableInterrupt(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(mpr121RegAutoconfig1, 0x00)
	chip, err := NewMPR121Full(conn)
	if err != nil {
		t.Fatalf("NewMPR121Full: %v", err)
	}
	if err := chip.EnableInterrupt(SOURCE_OOR); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if !findWrite(conn.writes, mpr121RegAutoconfig1, 0x04) {
		t.Errorf("EnableInterrupt(SOURCE_OOR) did not write 0x7C=0x04")
	}
	conn.setRegister(mpr121RegAutoconfig1, 0x04)
	if err := chip.DisableInterrupt(SOURCE_OOR); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	if !findWrite(conn.writes, mpr121RegAutoconfig1, 0x00) {
		t.Errorf("DisableInterrupt(SOURCE_OOR) did not write 0x7C=0x00")
	}
}
