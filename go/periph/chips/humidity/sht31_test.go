package humidity

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// sht31Mock is an in-memory fake connection.Connection: it logs 2-byte command
// writes and serves reads from a FIFO queue, optionally NACKing reads first.
type sht31Mock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
}

func (m *sht31Mock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *sht31Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *sht31Mock) Read(n int) ([]byte, error) {
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

func (m *sht31Mock) WriteRead(data []byte, n int) ([]byte, error) { return m.Read(n) }
func (m *sht31Mock) Close() error                                 { return nil }
func (m *sht31Mock) Enable()                                      {}
func (m *sht31Mock) Disable()                                     {}
func (m *sht31Mock) IsEnabled() bool                              { return true }
func (m *sht31Mock) IntPin() connection.InputPin                  { return nil }
func (m *sht31Mock) EnPin() connection.OutputPin                  { return nil }

func (m *sht31Mock) cmds() []uint16 {
	var out []uint16
	for _, w := range m.writes {
		out = append(out, uint16(w[0])<<8|uint16(w[1]))
	}
	return out
}

func shtFrame(rawT, rawRH uint16) []byte {
	t := []byte{byte(rawT >> 8), byte(rawT)}
	h := []byte{byte(rawRH >> 8), byte(rawRH)}
	return []byte{t[0], t[1], SHT31CRC8(t), h[0], h[1], SHT31CRC8(h)}
}

func shtCmdsEqual(got []uint16, want ...uint16) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func shtNear(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func newSHT(t *testing.T) (*sht31Mock, *SHT31Full) {
	t.Helper()
	m := &sht31Mock{}
	s, err := NewSHT31Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return m, s
}

func TestSHT31CRCReference(t *testing.T) {
	if SHT31CRC8([]byte{0xBE, 0xEF}) != 0x92 {
		t.Errorf("CRC of 0xBEEF = 0x%02X, want 0x92", SHT31CRC8([]byte{0xBE, 0xEF}))
	}
}

func TestSHT31InitAndRead(t *testing.T) {
	m, s := newSHT(t)
	if !shtCmdsEqual(m.cmds(), 0x30A2) {
		t.Errorf("init commands = %x", m.cmds())
	}
	m.writes = nil
	m.queue(shtFrame(0x6666, 0x8000))
	temp, rh, err := s.Read()
	if err != nil || !shtCmdsEqual(m.cmds(), 0x2400) {
		t.Fatalf("Read: %v cmds %x", err, m.cmds())
	}
	if !shtNear(float64(temp), -45+175*float64(0x6666)/65535, 1e-3) || !shtNear(float64(rh), 100*float64(0x8000)/65535, 1e-3) {
		t.Errorf("Read = %v %v", temp, rh)
	}
	// minimal driver standalone
	if _, err := NewSHT31Minimal(&sht31Mock{}); err != nil {
		t.Error(err)
	}
}

func TestSHT31ReadPollsAndRetries(t *testing.T) {
	m, s := newSHT(t)
	m.nackReads = 3
	m.queue(shtFrame(0, 65535))
	temp, rh, err := s.Read()
	if err != nil || !shtNear(float64(temp), -45, 1e-3) || !shtNear(float64(rh), 100, 1e-3) {
		t.Errorf("read after NACKs = %v %v %v", temp, rh, err)
	}
	// CRC mismatch: one retry
	m.writes = nil
	bad := shtFrame(0x1234, 0x5678)
	bad[2] ^= 0xFF
	m.queue(bad)
	m.queue(shtFrame(0x1234, 0x5678))
	if _, _, err := s.Read(); err != nil || !shtCmdsEqual(m.cmds(), 0x2400, 0x2400) {
		t.Errorf("retry: %v cmds %x", err, m.cmds())
	}
	m.queue(bad)
	m.queue(bad)
	if _, _, err := s.Read(); err != ErrSHT31CRC {
		t.Errorf("persistent CRC error = %v", err)
	}
	// never ready
	m.nackReads = 1000
	if _, _, err := s.Read(); err != ErrSHT31Timeout {
		t.Errorf("timeout err = %v", err)
	}
}

func TestSHT31RawAndSingleValues(t *testing.T) {
	m, s := newSHT(t)
	m.queue(shtFrame(1000, 2000))
	if rt, rr, err := s.ReadRaw(); err != nil || rt != 1000 || rr != 2000 {
		t.Errorf("ReadRaw = %d %d %v", rt, rr, err)
	}
	m.queue(shtFrame(0, 0))
	if v, _ := s.ReadTemperature(); !shtNear(float64(v), -45, 1e-3) {
		t.Errorf("ReadTemperature = %v", v)
	}
	m.queue(shtFrame(0, 65535))
	if v, _ := s.ReadHumidity(); !shtNear(float64(v), 100, 1e-3) {
		t.Errorf("ReadHumidity = %v", v)
	}
}

func TestSHT31RepeatabilityAndStretching(t *testing.T) {
	m, s := newSHT(t)
	m.writes = nil
	for _, step := range []struct {
		level   string
		stretch bool
	}{{SHT31RepeatabilityLow, false}, {SHT31RepeatabilityMedium, false}, {SHT31RepeatabilityMedium, true}, {SHT31RepeatabilityHigh, true}} {
		if err := s.SetRepeatability(step.level); err != nil {
			t.Fatal(err)
		}
		s.SetClockStretching(step.stretch)
		m.queue(shtFrame(0, 0))
		if _, _, err := s.Read(); err != nil {
			t.Fatal(err)
		}
	}
	if !shtCmdsEqual(m.cmds(), 0x2416, 0x240B, 0x2C0D, 0x2C06) {
		t.Errorf("commands = %x", m.cmds())
	}
	if s.SetRepeatability("ultra") == nil {
		t.Error("invalid repeatability accepted")
	}
}

func TestSHT31Periodic(t *testing.T) {
	m, s := newSHT(t)
	m.writes = nil
	_ = s.StartPeriodic(2, SHT31RepeatabilityHigh, false)
	_ = s.StartPeriodic(0.5, SHT31RepeatabilityLow, false)
	_ = s.StartPeriodic(10, SHT31RepeatabilityMedium, false)
	_ = s.StartPeriodic(1, SHT31RepeatabilityHigh, true)
	if !shtCmdsEqual(m.cmds(), 0x2236, 0x202F, 0x2721, 0x2B32) {
		t.Errorf("periodic commands = %x", m.cmds())
	}
	if s.StartPeriodic(3, SHT31RepeatabilityHigh, false) == nil || s.StartPeriodic(1, "x", false) == nil {
		t.Error("invalid periodic arguments accepted")
	}
	m.writes = nil
	m.queue(shtFrame(0x4000, 0x4000))
	temp, _, ok, err := s.Fetch()
	if !ok || err != nil || !shtCmdsEqual(m.cmds(), 0xE000) || !shtNear(float64(temp), -45+175*float64(0x4000)/65535, 1e-3) {
		t.Errorf("Fetch = %v %v %v cmds %x", temp, ok, err, m.cmds())
	}
	m.nackReads = 1
	if _, _, ok, err := s.Fetch(); ok || err != nil {
		t.Errorf("Fetch NACKed = %v %v", ok, err)
	}
	m.writes = nil
	_ = s.StopPeriodic()
	if !shtCmdsEqual(m.cmds(), 0x3093) {
		t.Errorf("StopPeriodic commands = %x", m.cmds())
	}
}

func TestSHT31HeaterStatusReset(t *testing.T) {
	m, s := newSHT(t)
	m.writes = nil
	_ = s.SetHeater(true)
	_ = s.SetHeater(false)
	_ = s.ClearStatus()
	if !shtCmdsEqual(m.cmds(), 0x306D, 0x3066, 0x3041) {
		t.Errorf("commands = %x", m.cmds())
	}
	m.queue([]byte{0x80, 0x10, SHT31CRC8([]byte{0x80, 0x10})})
	if v, err := s.ReadStatus(); err != nil || v != 0x8010 {
		t.Errorf("ReadStatus = %x %v", v, err)
	}
	m.queue([]byte{0x80, 0x10, 0x00})
	if _, err := s.ReadStatus(); err != ErrSHT31CRC {
		t.Errorf("ReadStatus CRC err = %v", err)
	}
	m.writes = nil
	_ = s.SoftReset()
	if !shtCmdsEqual(m.cmds(), 0x30A2) {
		t.Errorf("SoftReset commands = %x", m.cmds())
	}
}

func TestSHT31DewPoint(t *testing.T) {
	m, s := newSHT(t)
	rawT := uint16(math.Round((25.0 + 45.0) * 65535.0 / 175.0))
	rawRH := uint16(math.Round(50.0 * 65535.0 / 100.0))
	m.queue(shtFrame(rawT, rawRH))
	dp, err := s.DewPoint()
	if err != nil || !shtNear(float64(dp), 13.86, 0.05) {
		t.Errorf("DewPoint = %v %v", dp, err)
	}
}
