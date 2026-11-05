package adcdac

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ad5243Mock is an in-memory connection.RegisterConnection keyed by the
// instruction byte, recording every write.
type ad5243Mock struct {
	writes [][]byte
	regs   map[uint8]byte
}

func newAD5243Mock() *ad5243Mock { return &ad5243Mock{regs: map[uint8]byte{}} }

func (m *ad5243Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}
func (m *ad5243Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *ad5243Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *ad5243Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	out[0] = m.regs[uint8(reg)]
	return out, nil
}
func (m *ad5243Mock) WriteReg(reg uint32, data []byte) error {
	m.regs[uint8(reg)] = data[0]
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *ad5243Mock) Close() error                { return nil }
func (m *ad5243Mock) Enable()                     {}
func (m *ad5243Mock) Disable()                    {}
func (m *ad5243Mock) IsEnabled() bool             { return true }
func (m *ad5243Mock) IntPin() connection.InputPin { return nil }
func (m *ad5243Mock) EnPin() connection.OutputPin { return nil }

func (m *ad5243Mock) last() []byte { return m.writes[len(m.writes)-1] }

func eq(t *testing.T, label string, got []byte, want ...byte) {
	t.Helper()
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("%s: got %v, want %v", label, got, want)
	}
}

func near(a, b float32) bool { return math.Abs(float64(a-b)) < 0.01 }

func TestAD5243MinimalWiper(t *testing.T) {
	m := newAD5243Mock()
	pot, _ := NewAD5243Minimal(m, AD5243DefaultRAB, AD5243DefaultRWiper)
	if len(m.writes) != 0 {
		t.Fatalf("construction made bus traffic: %v", m.writes)
	}
	pot.SetWiper(0, 100)
	eq(t, "ch0", m.last(), 0x00, 100)
	pot.SetWiper(1, 200)
	eq(t, "ch1", m.last(), 0x80, 200)
	pot.SetWiper(0, 300)
	eq(t, "clamp hi", m.last(), 0x00, 255)
	pot.SetWiper(0, -5)
	eq(t, "clamp lo", m.last(), 0x00, 0)
	if err := pot.SetWiper(2, 1); err != ErrAD5243InvalidChannel {
		t.Errorf("invalid channel: got %v", err)
	}
	m.regs[0x00], m.regs[0x80] = 17, 99
	if v, _ := pot.GetWiper(0); v != 17 {
		t.Errorf("get ch0 = %d", v)
	}
	if v, _ := pot.GetWiper(1); v != 99 {
		t.Errorf("get ch1 = %d", v)
	}
}

func TestAD5243MinimalResistance(t *testing.T) {
	m := newAD5243Mock()
	pot, _ := NewAD5243Minimal(m, 10000, 160)
	pot.SetResistance(0, 5320)
	eq(t, "R mid", m.last(), 0x00, 128)
	pot.SetResistance(0, 0)
	eq(t, "R clamp lo", m.last(), 0x00, 0)
	pot.SetResistance(1, 1e6)
	eq(t, "R clamp hi", m.last(), 0x80, 255)
	m.regs[0x00], m.regs[0x80] = 128, 0
	if r, _ := pot.GetResistance(0); !near(r, 5320) {
		t.Errorf("R mid = %v", r)
	}
	if r, _ := pot.GetResistance(1); !near(r, 320) {
		t.Errorf("R zero = %v", r)
	}
}

func TestAD5243FullHelpers(t *testing.T) {
	m := newAD5243Mock()
	pot, _ := NewAD5243Full(m, 10000, 160)
	pot.SetBoth(1, 2)
	eq(t, "both ch0", m.writes[0], 0x00, 1)
	eq(t, "both ch1", m.writes[1], 0x80, 2)
	pot.SetRatio(0, 0.25)
	eq(t, "ratio", m.last(), 0x00, 64)
	pot.SetRatio(0, 1.0)
	eq(t, "ratio clamp", m.last(), 0x00, 255)
	pot.SetResistanceWA(1, 5320)
	eq(t, "wa", m.last(), 0x80, 128)

	m.regs[0x00], m.regs[0x80] = 250, 128
	if r, _ := pot.GetRatio(1); r != 0.5 {
		t.Errorf("ratio = %v", r)
	}
	if r, _ := pot.GetResistanceWA(1); !near(r, 5320) {
		t.Errorf("wa = %v", r)
	}
	if v, _ := pot.Increment(0, 3); v != 253 {
		t.Errorf("increment = %d", v)
	}
	eq(t, "increment write", m.last(), 0x00, 253)
	if v, _ := pot.Increment(0, 100); v != 255 {
		t.Errorf("increment clamp = %d", v)
	}
	if v, _ := pot.Increment(1, -28); v != 100 {
		t.Errorf("decrement = %d", v)
	}
}

func TestAD5243Shutdown(t *testing.T) {
	m := newAD5243Mock()
	pot, _ := NewAD5243Full(m, 10000, 160)
	pot.SetWiper(1, 77)
	if sd, _ := pot.IsShutdown(1); sd {
		t.Error("shutdown at start")
	}
	pot.Shutdown(1)
	eq(t, "shutdown", m.last(), 0xC0, 77)
	if sd, _ := pot.IsShutdown(1); !sd {
		t.Error("not shutdown")
	}
	if sd, _ := pot.IsShutdown(0); sd {
		t.Error("ch0 shutdown")
	}
	pot.SetWiper(1, 50)
	eq(t, "set keeps SD", m.last(), 0xC0, 50)
	pot.Resume(1)
	eq(t, "resume", m.last(), 0x80, 50)
	pot.Shutdown(0)
	eq(t, "shutdown ch0 midscale", m.last(), 0x40, 128)
	pot.Reset()
	eq(t, "reset ch0", m.writes[len(m.writes)-2], 0x00, 128)
	eq(t, "reset ch1", m.last(), 0x80, 128)
	if sd, _ := pot.IsShutdown(0); sd {
		t.Error("reset did not clear shutdown")
	}
}

func TestAD5243SetRAB(t *testing.T) {
	m := newAD5243Mock()
	m.regs[0x00] = 128
	pot, _ := NewAD5243Full(m, 10000, 160)
	pot.SetRAB(50000, 100)
	if len(m.writes) != 0 {
		t.Error("SetRAB made bus traffic")
	}
	if r, _ := pot.GetResistance(0); !near(r, 25200) {
		t.Errorf("R = %v", r)
	}
	pot.SetRAB(10000, -1)
	if r, _ := pot.GetResistance(0); !near(r, 5200) {
		t.Errorf("R keeps RW = %v", r)
	}
}
