package light

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// bh1750Mock is an in-memory fake connection.Connection: it logs writes,
// serves plain reads from a FIFO queue and can fail the next write.
type bh1750Mock struct {
	writes    [][]byte
	readQueue [][]byte
	failWrite bool
}

func (m *bh1750Mock) queue(raw uint16) {
	m.readQueue = append(m.readQueue, []byte{byte(raw >> 8), byte(raw)})
}

func (m *bh1750Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.failWrite {
		m.failWrite = false
		return errors.New("NACK")
	}
	return nil
}

func (m *bh1750Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *bh1750Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *bh1750Mock) Close() error                { return nil }
func (m *bh1750Mock) Enable()                     {}
func (m *bh1750Mock) Disable()                    {}
func (m *bh1750Mock) IsEnabled() bool             { return true }
func (m *bh1750Mock) IntPin() connection.InputPin { return nil }
func (m *bh1750Mock) EnPin() connection.OutputPin { return nil }

func (m *bh1750Mock) cmds() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

func bh1750Eq(a []byte, b ...byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func bh1750Near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func TestBH1750InitAndRead(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Minimal(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
	if err := s.Init(); err != nil || !bh1750Eq(m.cmds(), 0x01) {
		t.Fatalf("Init: %v cmds %x", err, m.cmds())
	}
	m.writes = nil
	m.queue(0x8342)
	lux, err := s.Read()
	if err != nil || !bh1750Near(float64(lux), 0x8342/1.2, 0.01) || !bh1750Eq(m.cmds(), 0x20) {
		t.Errorf("Read: %v %v cmds %x", lux, err, m.cmds())
	}
}

func TestBH1750MeasureOnceModes(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	for _, c := range []struct {
		mode BH1750Mode
		op   byte
		lux  float64
	}{{BH1750ModeH, 0x20, 15}, {BH1750ModeH2, 0x21, 7.5}, {BH1750ModeL, 0x23, 15}} {
		m.writes = nil
		m.queue(0x12)
		lux, err := s.MeasureOnce(c.mode)
		if err != nil || !bh1750Near(float64(lux), c.lux, 1e-3) || !bh1750Eq(m.cmds(), c.op) {
			t.Errorf("mode %d: %v %v cmds %x", c.mode, lux, err, m.cmds())
		}
	}
	if _, err := s.MeasureOnce(7); err != ErrBH1750Mode {
		t.Error("bad mode accepted")
	}
}

func TestBH1750ReadFollowsCurrentMode(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	m.queue(0x12)
	s.MeasureOnce(BH1750ModeH2)
	m.writes = nil
	m.queue(0x12)
	lux, _ := s.Read()
	if !bh1750Near(float64(lux), 7.5, 1e-3) || !bh1750Eq(m.cmds(), 0x21) {
		t.Errorf("Read: %v cmds %x", lux, m.cmds())
	}
	m.writes = nil
	m.queue(0x12)
	raw, err := s.ReadRaw()
	if err != nil || raw != 0x12 || !bh1750Eq(m.cmds(), 0x21) {
		t.Errorf("ReadRaw: %v %v cmds %x", raw, err, m.cmds())
	}
}

func TestBH1750Continuous(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	s.StartContinuous(BH1750ModeH)
	s.StartContinuous(BH1750ModeH2)
	s.StartContinuous(BH1750ModeL)
	if !bh1750Eq(m.cmds(), 0x10, 0x11, 0x13) {
		t.Errorf("opcodes %x", m.cmds())
	}
	s.StartContinuous(BH1750ModeH2)
	m.writes = nil
	m.queue(0x12)
	lux, err := s.ReadContinuous()
	if err != nil || !bh1750Near(float64(lux), 7.5, 1e-3) || len(m.writes) != 0 {
		t.Errorf("ReadContinuous: %v %v", lux, err)
	}
	if s.StartContinuous(9) != ErrBH1750Mode {
		t.Error("bad mode accepted")
	}
}

func TestBH1750PowerAndReset(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	s.PowerDown()
	s.PowerOn()
	if !bh1750Eq(m.cmds(), 0x00, 0x01) {
		t.Errorf("power opcodes %x", m.cmds())
	}
	m.writes = nil
	s.Reset()
	if !bh1750Eq(m.cmds(), 0x01, 0x07) {
		t.Errorf("reset opcodes %x", m.cmds())
	}
}

func TestBH1750MeasurementTimeRegister(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	if s.GetMeasurementTimeRegister() != 69 {
		t.Error("default MTreg")
	}
	for _, c := range []struct {
		mtreg  uint8
		hi, lo byte
	}{{69, 0x42, 0x65}, {254, 0x47, 0x7E}, {31, 0x40, 0x7F}} {
		m.writes = nil
		if err := s.SetMeasurementTime(c.mtreg); err != nil || !bh1750Eq(m.cmds(), c.hi, c.lo) || s.GetMeasurementTimeRegister() != c.mtreg {
			t.Errorf("MTreg %d: %v cmds %x", c.mtreg, err, m.cmds())
		}
	}
	m.writes = nil
	if s.SetMeasurementTime(30) != ErrBH1750MTreg || s.SetMeasurementTime(255) != ErrBH1750MTreg || len(m.writes) != 0 || s.GetMeasurementTimeRegister() != 31 {
		t.Error("out-of-range MTreg accepted")
	}
}

func TestBH1750Sensitivity(t *testing.T) {
	m := &bh1750Mock{}
	s := NewBH1750Full(m)
	if !bh1750Near(float64(s.LuxPerCount(BH1750ModeH)), 1/1.2, 1e-5) || !bh1750Near(float64(s.LuxPerCount(BH1750ModeH2)), 0.5/1.2, 1e-5) {
		t.Error("lux per count default")
	}
	if !bh1750Near(float64(s.MeasurementTime(BH1750ModeH)), 0.180, 1e-5) || !bh1750Near(float64(s.MeasurementTime(BH1750ModeL)), 0.024, 1e-5) {
		t.Error("measurement time default")
	}
	if err := s.SetSensitivity(2.0); err != nil || s.GetMeasurementTimeRegister() != 138 {
		t.Fatalf("SetSensitivity: %v mtreg %d", err, s.GetMeasurementTimeRegister())
	}
	if !bh1750Near(float64(s.LuxPerCount(BH1750ModeH)), 1/1.2/2, 1e-5) || !bh1750Near(float64(s.MeasurementTime(BH1750ModeH)), 0.360, 1e-5) {
		t.Error("scaling with MTreg")
	}
	m.queue(0x12)
	if lux, _ := s.MeasureOnce(BH1750ModeH); !bh1750Near(float64(lux), 7.5, 1e-3) {
		t.Errorf("scaled read %v", lux)
	}
	if s.SetSensitivity(0.4) != ErrBH1750MTreg || s.SetSensitivity(3.7) != ErrBH1750MTreg {
		t.Error("sensitivity limits")
	}
	s.SetSensitivity(0.45)
	if s.GetMeasurementTimeRegister() != 31 {
		t.Error("sensitivity min")
	}
	s.SetSensitivity(3.68)
	if s.GetMeasurementTimeRegister() != 254 {
		t.Error("sensitivity max")
	}
}

func TestBH1750BusError(t *testing.T) {
	m := &bh1750Mock{failWrite: true}
	s := NewBH1750Full(m)
	if _, err := s.MeasureOnce(BH1750ModeH); err == nil {
		t.Error("bus error swallowed")
	}
}
