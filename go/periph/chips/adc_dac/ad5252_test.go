package adcdac

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ad5252Mock is an in-memory connection.RegisterConnection keyed by the
// instruction byte, recording every write. failWrites NACKs that many
// writes after skipWrites have succeeded.
type ad5252Mock struct {
	writes     [][]byte
	regs       map[uint8]byte
	failWrites int
	skipWrites int
}

func newAD5252Mock() *ad5252Mock { return &ad5252Mock{regs: map[uint8]byte{}} }

func (m *ad5252Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.skipWrites > 0 {
		m.skipWrites--
	} else if m.failWrites > 0 {
		m.failWrites--
		return errors.New("NACK")
	}
	if len(data) >= 2 {
		for i, b := range data[1:] {
			m.regs[data[0]+uint8(i)] = b
		}
	}
	return nil
}
func (m *ad5252Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *ad5252Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	for i := range out {
		out[i] = m.regs[data[0]+uint8(i)]
	}
	return out, nil
}
func (m *ad5252Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}
func (m *ad5252Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *ad5252Mock) Close() error                { return nil }
func (m *ad5252Mock) Enable()                     {}
func (m *ad5252Mock) Disable()                    {}
func (m *ad5252Mock) IsEnabled() bool             { return true }
func (m *ad5252Mock) IntPin() connection.InputPin { return nil }
func (m *ad5252Mock) EnPin() connection.OutputPin { return nil }

func (m *ad5252Mock) last() []byte { return m.writes[len(m.writes)-1] }

func ad5252Check(t *testing.T, label string, got [][]byte, want ...[]byte) {
	t.Helper()
	if len(got) != len(want) {
		t.Errorf("%s: got %v, want %v", label, got, want)
		return
	}
	for i := range got {
		if string(got[i]) != string(want[i]) {
			t.Errorf("%s: got %v, want %v", label, got, want)
			return
		}
	}
}

func ad5252Last(t *testing.T, label string, m *ad5252Mock, want ...byte) {
	t.Helper()
	ad5252Check(t, label, [][]byte{m.last()}, want)
}

func ad5252Near(a, b float32) bool { return math.Abs(float64(a-b)) < 0.01 }

func TestAD5252MinimalWiper(t *testing.T) {
	m := newAD5252Mock()
	pot, _ := NewAD5252Minimal(m, AD5252DefaultRAB, AD5252DefaultRWiper)
	if len(m.writes) != 0 {
		t.Fatalf("construction made bus traffic: %v", m.writes)
	}
	pot.SetWiper(0, 100)
	ad5252Last(t, "ch0", m, 0x01, 100)
	pot.SetWiper(1, 200)
	ad5252Last(t, "ch1", m, 0x03, 200)
	pot.SetWiper(0, 300)
	ad5252Last(t, "clamp hi", m, 0x01, 255)
	pot.SetWiper(0, -5)
	ad5252Last(t, "clamp lo", m, 0x01, 0)
	if err := pot.SetWiper(2, 1); err != ErrAD5252InvalidChannel {
		t.Errorf("invalid channel: got %v", err)
	}
	m.regs[0x01], m.regs[0x03] = 17, 99
	if v, _ := pot.GetWiper(0); v != 17 {
		t.Errorf("get ch0 = %d", v)
	}
	if v, _ := pot.GetWiper(1); v != 99 {
		t.Errorf("get ch1 = %d", v)
	}
}

func TestAD5252MinimalResistance(t *testing.T) {
	m := newAD5252Mock()
	pot, _ := NewAD5252Minimal(m, 10000, 75)
	pot.SetResistance(0, 5075)
	ad5252Last(t, "R mid", m, 0x01, 128)
	pot.SetResistance(0, 0)
	ad5252Last(t, "R clamp lo", m, 0x01, 0)
	pot.SetResistance(1, 1e6)
	ad5252Last(t, "R clamp hi", m, 0x03, 255)
	m.regs[0x01], m.regs[0x03] = 128, 0
	if r, _ := pot.GetResistance(0); !ad5252Near(r, 5075) {
		t.Errorf("R mid = %v", r)
	}
	if r, _ := pot.GetResistance(1); !ad5252Near(r, 75) {
		t.Errorf("R zero = %v", r)
	}
}

func TestAD5252FullHelpers(t *testing.T) {
	m := newAD5252Mock()
	pot, _ := NewAD5252Full(m, 10000, 75)
	pot.SetBoth(1, 2)
	ad5252Check(t, "both one transaction", m.writes, []byte{0x01, 1, 2})
	pot.SetRatio(0, 0.25)
	ad5252Last(t, "ratio", m, 0x01, 64)
	pot.SetRatio(0, 1.0)
	ad5252Last(t, "ratio clamp", m, 0x01, 255)
	pot.SetResistanceWA(1, 5075)
	ad5252Last(t, "wa", m, 0x03, 128)

	m.regs[0x01], m.regs[0x03] = 250, 128
	if r, _ := pot.GetRatio(1); r != 0.5 {
		t.Errorf("ratio = %v", r)
	}
	if r, _ := pot.GetResistanceWA(1); !ad5252Near(r, 5075) {
		t.Errorf("wa = %v", r)
	}
}

func TestAD5252QuickCommands(t *testing.T) {
	m := newAD5252Mock()
	pot, _ := NewAD5252Full(m, 10000, 75)
	pot.Increment(0)
	pot.Increment(1)
	pot.Increment(AD5252Both)
	pot.Decrement(0)
	pot.Decrement(1)
	pot.Decrement(AD5252Both)
	ad5252Check(t, "inc/dec", m.writes, []byte{0xD1}, []byte{0xD3}, []byte{0xD8}, []byte{0xA9}, []byte{0xAB}, []byte{0xB0})
	m.writes = nil
	pot.Double(0)
	pot.Double(1)
	pot.Double(AD5252Both)
	pot.Halve(0)
	pot.Halve(1)
	pot.Halve(AD5252Both)
	ad5252Check(t, "double/halve", m.writes, []byte{0xC1}, []byte{0xC3}, []byte{0xC8}, []byte{0x99}, []byte{0x9B}, []byte{0xA0})
	m.writes = nil
	pot.Nop()
	ad5252Check(t, "nop", m.writes, []byte{0x80})
	if err := pot.Increment(2); err != ErrAD5252InvalidChannel {
		t.Errorf("invalid channel: got %v", err)
	}
}

func TestAD5252StoreRestoreReset(t *testing.T) {
	m := newAD5252Mock()
	pot, _ := NewAD5252Full(m, 10000, 75)
	pot.Store(1)
	ad5252Check(t, "store ch1", m.writes, []byte{0x93}, []byte{0x80})
	m.writes = nil
	m.skipWrites, m.failWrites = 1, 2
	if err := pot.Store(0); err != nil {
		t.Errorf("store with polling: %v", err)
	}
	ad5252Check(t, "store polls", m.writes, []byte{0x91}, []byte{0x80}, []byte{0x80}, []byte{0x80})
	m.writes = nil
	pot.Store(AD5252Both)
	ad5252Check(t, "store both", m.writes, []byte{0x91}, []byte{0x80}, []byte{0x93}, []byte{0x80})
	m.writes = nil
	pot.Restore(0)
	ad5252Check(t, "restore ch0", m.writes, []byte{0x89}, []byte{0x80})
	m.writes = nil
	pot.Restore(AD5252Both)
	ad5252Check(t, "restore both", m.writes, []byte{0x89}, []byte{0x8B}, []byte{0x80})
	m.writes = nil
	pot.Reset()
	ad5252Check(t, "reset", m.writes, []byte{0xB8}, []byte{0x80})
	m.skipWrites, m.failWrites = 1, 1000
	if err := pot.Store(0); err != ErrAD5252Timeout {
		t.Errorf("timeout: got %v", err)
	}
}

func TestAD5252UserEEMEMAndTolerance(t *testing.T) {
	m := newAD5252Mock()
	m.regs[0x24], m.regs[0x2F] = 0x5A, 0xA5
	m.regs[0x3A], m.regs[0x3B] = 0x1C, 0x0F
	m.regs[0x3E], m.regs[0x3F] = 0x82, 0x80
	pot, _ := NewAD5252Full(m, 10000, 75)
	if v, _ := pot.ReadEEMEM(4); v != 0x5A {
		t.Errorf("eemem 4 = %x", v)
	}
	if v, _ := pot.ReadEEMEM(15); v != 0xA5 {
		t.Errorf("eemem 15 = %x", v)
	}
	m.writes = nil
	pot.WriteEEMEM(9, 0x42)
	ad5252Check(t, "write eemem", m.writes, []byte{0x29, 0x42}, []byte{0x80})
	if _, err := pot.ReadEEMEM(3); err != ErrAD5252InvalidChannel {
		t.Errorf("read range: %v", err)
	}
	if err := pot.WriteEEMEM(16, 0); err != ErrAD5252InvalidChannel {
		t.Errorf("write range: %v", err)
	}
	if v, _ := pot.ReadTolerance(0); math.Abs(float64(v)-28.05859375) > 1e-4 {
		t.Errorf("tolerance 0 = %v", v)
	}
	if v, _ := pot.ReadTolerance(1); math.Abs(float64(v)+2.5) > 1e-5 {
		t.Errorf("tolerance 1 = %v", v)
	}
	if v, _ := pot.RABActual(0); math.Abs(float64(v)-12805.859) > 0.05 {
		t.Errorf("rab actual = %v", v)
	}
}

func TestAD5252SetRAB(t *testing.T) {
	m := newAD5252Mock()
	m.regs[0x01] = 128
	pot, _ := NewAD5252Full(m, 10000, 75)
	pot.SetRAB(50000, 100)
	if len(m.writes) != 0 {
		t.Error("SetRAB made bus traffic")
	}
	if r, _ := pot.GetResistance(0); !ad5252Near(r, 25100) {
		t.Errorf("R = %v", r)
	}
	pot.SetRAB(10000, -1)
	if r, _ := pot.GetResistance(0); !ad5252Near(r, 5100) {
		t.Errorf("R keeps RW = %v", r)
	}
}
