package humidity

import (
	"errors"
	"math"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// hs30xxMock is an in-memory fake connection.Connection: it logs writes and
// WriteRead requests, serves plain reads from a FIFO queue (optionally NACKing
// reads first) and WriteRead from a map keyed by the request bytes.
type hs30xxMock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
	wr        map[string][]byte
}

func (m *hs30xxMock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *hs30xxMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *hs30xxMock) Read(n int) ([]byte, error) {
	if m.nackReads > 0 {
		m.nackReads--
		return nil, errors.New("NACK")
	}
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *hs30xxMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	copy(out, m.wr[string(data)])
	return out, nil
}
func (m *hs30xxMock) Close() error                { return nil }
func (m *hs30xxMock) Enable()                     {}
func (m *hs30xxMock) Disable()                    {}
func (m *hs30xxMock) IsEnabled() bool             { return true }
func (m *hs30xxMock) IntPin() connection.InputPin { return nil }
func (m *hs30xxMock) EnPin() connection.OutputPin { return nil }

func (m *hs30xxMock) first() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

func (m *hs30xxMock) lastWrite(want ...byte) bool {
	w := m.writes[len(m.writes)-1]
	if len(w) != len(want) {
		return false
	}
	for i := range w {
		if w[i] != want[i] {
			return false
		}
	}
	return true
}

func hsFrame(rh, t uint16, status byte) []byte {
	return []byte{status<<6 | byte(rh>>8), byte(rh), byte(t >> 6), byte(t&0x3F) << 2}
}

func hsNear(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func hsWrites(m *hs30xxMock, want ...[]byte) bool {
	if len(m.writes) != len(want) {
		return false
	}
	for i := range want {
		if string(m.writes[i]) != string(want[i]) {
			return false
		}
	}
	return true
}

func TestHS30xxConstructorNoBusAccess(t *testing.T) {
	m := &hs30xxMock{}
	NewHS30xxFull(m)
	NewHS30xxMinimal(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
}

func TestHS30xxAliases(t *testing.T) {
	m := &hs30xxMock{}
	var a *HS3001Full = NewHS3001Full(m)
	var b *HS30xxFull = NewHS3002Full(m)
	var c *HS3003Minimal = NewHS3003Minimal(m)
	var d *HS30xxMinimal = NewHS3001Minimal(m)
	if a == nil || b == nil || c == nil || d == nil {
		t.Error("alias constructors")
	}
}

func TestHS30xxReadConversion(t *testing.T) {
	m := &hs30xxMock{}
	m.queue(hsFrame(0x2000, 0x1000, 0))
	temp, rh, err := NewHS30xxMinimal(m).Read()
	if err != nil || !hsWrites(m, []byte{}) {
		t.Fatalf("Read: %v writes %x", err, m.writes)
	}
	if !hsNear(float64(rh), 50, 0.01) || !hsNear(float64(temp), 1.25, 0.01) {
		t.Errorf("Read = %v %v", temp, rh)
	}
	m.queue(hsFrame(0x3FFF, 0x3FFF, 0))
	temp, rh, _ = NewHS30xxMinimal(m).Read()
	if !hsNear(float64(rh), 100, 1e-3) || !hsNear(float64(temp), 125, 1e-3) {
		t.Errorf("full scale = %v %v", temp, rh)
	}
	m.queue(hsFrame(0, 0, 0))
	temp, rh, _ = NewHS30xxMinimal(m).Read()
	if rh != 0 || !hsNear(float64(temp), -40, 1e-3) {
		t.Errorf("zero = %v %v", temp, rh)
	}
}

func TestHS30xxMasksUndeterminedBits(t *testing.T) {
	m := &hs30xxMock{}
	m.queue([]byte{0x20, 0x00, 0x10, 0x03})
	temp, _, _ := NewHS30xxMinimal(m).Read()
	if !hsNear(float64(temp), 0x400/16383.0*165.0-40.0, 1e-3) {
		t.Errorf("temperature %v", temp)
	}
}

func TestHS30xxStale(t *testing.T) {
	m := &hs30xxMock{}
	m.queue(hsFrame(0x2000, 0x1000, 1))
	m.queue(hsFrame(0x2000, 0x1000, 0))
	_, rh, err := NewHS30xxMinimal(m).Read()
	if err != nil || !hsNear(float64(rh), 50, 0.01) || len(m.writes) != 1 {
		t.Errorf("retry: %v %v writes %d", rh, err, len(m.writes))
	}
	m = &hs30xxMock{}
	m.queue(hsFrame(0, 0, 1))
	m.queue(hsFrame(0, 0, 1))
	if _, _, err := NewHS30xxMinimal(m).Read(); !errors.Is(err, ErrHS30xxStale) {
		t.Errorf("stale twice: %v", err)
	}
}

func TestHS30xxFullSingleValuesAndRaw(t *testing.T) {
	m := &hs30xxMock{}
	s := NewHS30xxFull(m)
	m.queue(hsFrame(0x2000, 0x1000, 0))
	if v, err := s.ReadTemperature(); err != nil || !hsNear(float64(v), 1.25, 0.01) {
		t.Errorf("ReadTemperature %v %v", v, err)
	}
	m.queue([]byte{0x20, 0x00})
	if v, err := s.ReadHumidity(); err != nil || !hsNear(float64(v), 50, 0.01) {
		t.Errorf("ReadHumidity %v %v", v, err)
	}
	m.queue(hsFrame(0x1234, 0x0ABC, 0))
	if rh, tr, st, err := s.ReadRaw(); err != nil || rh != 0x1234 || tr != 0x0ABC || st != 0 {
		t.Errorf("ReadRaw %x %x %d %v", rh, tr, st, err)
	}
	m.queue(hsFrame(0x1234, 0x0ABC, 1))
	if _, _, st, _ := s.ReadRaw(); st != 1 {
		t.Errorf("stale status %d", st)
	}
	if s.SetMeasurementWait(0) == nil || s.SetMeasurementWait(5*time.Millisecond) != nil {
		t.Error("SetMeasurementWait validation")
	}
}

func TestHS30xxProgrammingAndNVM(t *testing.T) {
	m := &hs30xxMock{}
	s := NewHS30xxFull(m)
	s.EnterProgrammingMode()
	s.ExitProgrammingMode()
	if !hsWrites(m, []byte{0xA0, 0, 0}, []byte{0x80, 0, 0}) {
		t.Errorf("programming commands %x", m.writes)
	}
	m = &hs30xxMock{}
	s = NewHS30xxFull(m)
	m.queue([]byte{0x81, 0x12, 0x34})
	if v, err := s.ReadNVM(0x1E); err != nil || v != 0x1234 {
		t.Errorf("ReadNVM %x %v", v, err)
	}
	m.queue([]byte{0x80, 0x12, 0x34})
	if _, err := s.ReadNVM(0x1E); !errors.Is(err, ErrHS30xxNVM) {
		t.Errorf("bad status %v", err)
	}
}

func TestHS30xxNVMNeedsPowerCycle(t *testing.T) {
	m := &hs30xxMock{}
	s := NewHS30xxFull(m)
	if _, _, err := s.GetResolution(nil); !errors.Is(err, ErrHS30xxPowerCycle) {
		t.Error("GetResolution")
	}
	if err := s.SetResolution(12, 12, nil); !errors.Is(err, ErrHS30xxPowerCycle) {
		t.Error("SetResolution")
	}
	if _, err := s.ReadID(nil); !errors.Is(err, ErrHS30xxPowerCycle) {
		t.Error("ReadID")
	}
	if len(m.writes) != 0 {
		t.Error("bus traffic without power cycle")
	}
}

func TestHS30xxGetResolution(t *testing.T) {
	m := &hs30xxMock{}
	s := NewHS30xxFull(m)
	m.queue([]byte{0x81, 0x0C, 0x00})
	m.queue([]byte{0x81, 0x08, 0x05})
	cycles := 0
	rh, tb, err := s.GetResolution(func() { cycles++ })
	if err != nil || rh != 14 || tb != 12 || cycles != 1 {
		t.Errorf("GetResolution %d %d %v cycles %d", rh, tb, err, cycles)
	}
	if !hsWrites(m, []byte{0xA0, 0, 0}, []byte{0x06, 0, 0}, []byte{0x11, 0, 0}, []byte{0x80, 0, 0}) {
		t.Errorf("sequence %x", m.writes)
	}
}

func TestHS30xxSetResolution(t *testing.T) {
	m := &hs30xxMock{}
	s := NewHS30xxFull(m)
	m.queue([]byte{0x81, 0x0C, 0x07})
	m.queue([]byte{0x81, 0x0C, 0x21})
	if err := s.SetResolution(8, 12, func() {}); err != nil {
		t.Fatal(err)
	}
	if !hsWrites(m, []byte{0xA0, 0, 0}, []byte{0x06, 0, 0}, []byte{0x46, 0x00, 0x07},
		[]byte{0x11, 0, 0}, []byte{0x51, 0x08, 0x21}, []byte{0x80, 0, 0}) {
		t.Errorf("sequence %x", m.writes)
	}
	m = &hs30xxMock{}
	s = NewHS30xxFull(m)
	m.queue([]byte{0x81, 0x00, 0x00})
	s.SetResolution(0, 10, func() {})
	if !hsWrites(m, []byte{0xA0, 0, 0}, []byte{0x11, 0, 0}, []byte{0x51, 0x04, 0x00}, []byte{0x80, 0, 0}) {
		t.Errorf("one channel %x", m.writes)
	}
	m = &hs30xxMock{}
	if err := NewHS30xxFull(m).SetResolution(9, 0, func() {}); !errors.Is(err, ErrHS30xxArgument) || len(m.writes) != 0 {
		t.Error("invalid value must fail before any bus traffic")
	}
}

func TestHS30xxFailedNVMReadLeavesProgrammingMode(t *testing.T) {
	m := &hs30xxMock{}
	m.queue([]byte{0x00, 0, 0})
	_, _, err := NewHS30xxFull(m).GetResolution(func() {})
	if !errors.Is(err, ErrHS30xxNVM) || string(m.writes[len(m.writes)-1]) != string([]byte{0x80, 0, 0}) {
		t.Errorf("err %v writes %x", err, m.writes)
	}
}

func TestHS30xxReadID(t *testing.T) {
	m := &hs30xxMock{}
	m.queue([]byte{0x81, 0xDE, 0xAD})
	m.queue([]byte{0x81, 0xBE, 0xEF})
	if id, err := NewHS30xxFull(m).ReadID(func() {}); err != nil || id != 0xDEADBEEF {
		t.Errorf("ReadID %x %v", id, err)
	}
}

func TestHS30xxDewPoint(t *testing.T) {
	m := &hs30xxMock{}
	m.queue(hsFrame(uint16(8191), uint16(6453), 0))
	if v, err := NewHS30xxFull(m).DewPoint(); err != nil || !hsNear(float64(v), 13.86, 0.1) {
		t.Errorf("DewPoint %v %v", v, err)
	}
	m.queue(hsFrame(0, 0x1000, 0))
	if v, _ := NewHS30xxFull(m).DewPoint(); !hsNear(float64(v), -273.15, 1e-3) {
		t.Errorf("DewPoint at 0 %%RH %v", v)
	}
}
