package humidity

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// shtc3Mock is an in-memory fake connection.Connection: it logs writes and
// WriteRead requests, serves plain reads from a FIFO queue (optionally NACKing
// reads first) and WriteRead from a map keyed by the request bytes.
type shtc3Mock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
	wr        map[string][]byte
}

func (m *shtc3Mock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *shtc3Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *shtc3Mock) Read(n int) ([]byte, error) {
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

func (m *shtc3Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	copy(out, m.wr[string(data)])
	return out, nil
}
func (m *shtc3Mock) Close() error                { return nil }
func (m *shtc3Mock) Enable()                     {}
func (m *shtc3Mock) Disable()                    {}
func (m *shtc3Mock) IsEnabled() bool             { return true }
func (m *shtc3Mock) IntPin() connection.InputPin { return nil }
func (m *shtc3Mock) EnPin() connection.OutputPin { return nil }

// cmds returns the 16-bit command of every logged write.
func (m *shtc3Mock) cmds() []uint16 {
	var out []uint16
	for _, w := range m.writes {
		out = append(out, uint16(w[0])<<8|uint16(w[1]))
	}
	return out
}

func sameCmds(got []uint16, want ...uint16) bool {
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

func shtc3Word16(code uint16) []byte {
	d := []byte{byte(code >> 8), byte(code)}
	return []byte{d[0], d[1], SHTC3CRC8(d)}
}

func shtc3TRH(t, rh uint16) []byte { return append(shtc3Word16(t), shtc3Word16(rh)...) }

func near32(a, b, tol float32) bool { return float32(math.Abs(float64(a-b))) <= tol }

func TestSHTC3CRC8(t *testing.T) {
	if SHTC3CRC8([]byte{0xBE, 0xEF}) != 0x92 || SHTC3CRC8([]byte{0x00}) != 0xAC {
		t.Fatal("crc8 vectors")
	}
}

func TestSHTC3InitSequence(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3Word16(0x0807))
	s := NewSHTC3Full(m)
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	if !sameCmds(m.cmds(), 0x3517, 0x805D, 0xEFC8, 0xB098) {
		t.Fatalf("init sequence %x", m.cmds())
	}
}

func TestSHTC3InitWrongID(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3Word16(0x0000))
	if err := NewSHTC3Minimal(m).Init(); !errors.Is(err, ErrSHTC3WrongChip) {
		t.Fatalf("want wrong chip, got %v", err)
	}
	m = &shtc3Mock{}
	b := shtc3Word16(0x0807)
	b[2] ^= 0x55
	m.queue(b)
	if err := NewSHTC3Minimal(m).Init(); !errors.Is(err, ErrSHTC3CRC) {
		t.Fatalf("want CRC error, got %v", err)
	}
}

func TestSHTC3ReadConverts(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3TRH(0x6666, 0x6000))
	temp, rh, err := NewSHTC3Minimal(m).Read()
	if err != nil {
		t.Fatal(err)
	}
	if !sameCmds(m.cmds(), 0x7866, 0xB098) {
		t.Fatalf("commands %x", m.cmds())
	}
	if !near32(temp, -45+175*float32(0x6666)/65536, 1e-3) || !near32(rh, 37.5, 1e-3) {
		t.Fatalf("values %v %v", temp, rh)
	}
}

func TestSHTC3WakesAfterSleep(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3TRH(0x6666, 0x6000))
	m.queue(shtc3TRH(0x6666, 0x6000))
	s := NewSHTC3Minimal(m)
	s.Read()
	m.writes = nil
	s.Read()
	if !sameCmds(m.cmds(), 0x3517, 0x7866, 0xB098) {
		t.Fatalf("commands %x", m.cmds())
	}
}

func TestSHTC3Range(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3TRH(0xFFFF, 0xFFFF))
	m.queue(shtc3TRH(0, 0))
	s := NewSHTC3Minimal(m)
	temp, rh, _ := s.Read()
	if !near32(temp, 130, 0.01) || rh > 100 || rh < 99.9 {
		t.Fatalf("max %v %v", temp, rh)
	}
	temp, rh, _ = s.Read()
	if !near32(temp, -45, 1e-3) || rh != 0 {
		t.Fatalf("min %v %v", temp, rh)
	}
}

func TestSHTC3CRCRetry(t *testing.T) {
	bad := shtc3TRH(0x6666, 0x6000)
	bad[2] ^= 0xFF
	m := &shtc3Mock{}
	m.queue(bad)
	m.queue(bad)
	if _, _, err := NewSHTC3Minimal(m).Read(); !errors.Is(err, ErrSHTC3CRC) {
		t.Fatalf("want CRC error, got %v", err)
	}
	bad2 := shtc3TRH(0x6666, 0x6000)
	bad2[5] ^= 0xFF
	m = &shtc3Mock{}
	m.queue(bad2)
	m.queue(shtc3TRH(0x6666, 0x6000))
	_, rh, err := NewSHTC3Minimal(m).Read()
	if err != nil || !near32(rh, 37.5, 1e-3) {
		t.Fatalf("glitch not recovered: %v %v", rh, err)
	}
	if !sameCmds(m.cmds(), 0x7866, 0x7866, 0xB098) {
		t.Fatalf("commands %x", m.cmds())
	}
}

func TestSHTC3BusError(t *testing.T) {
	m := &shtc3Mock{nackReads: 1}
	if _, _, err := NewSHTC3Minimal(m).Read(); err == nil {
		t.Fatal("read error not propagated")
	}
}

func TestSHTC3SingleValues(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3TRH(0x4000, 0x4000))
	m.queue(shtc3TRH(0x4000, 0x4000))
	m.queue(shtc3TRH(0x1234, 0x5678))
	s := NewSHTC3Full(m)
	if h, _ := s.ReadHumidity(); !near32(h, 25, 1e-3) {
		t.Fatalf("humidity %v", h)
	}
	if tc, _ := s.ReadTemperature(); !near32(tc, -45+175*0.25, 1e-3) {
		t.Fatalf("temperature %v", tc)
	}
	if tr, rr, _ := s.ReadRaw(); tr != 0x1234 || rr != 0x5678 {
		t.Fatalf("raw %x %x", tr, rr)
	}
}

func TestSHTC3Modes(t *testing.T) {
	m := &shtc3Mock{}
	for i := 0; i < 3; i++ {
		m.queue(shtc3TRH(0x6666, 0x6000))
	}
	s := NewSHTC3Full(m)
	s.SetLowPower(true)
	s.Read()
	s.SetClockStretching(true)
	s.Read()
	s.SetLowPower(false)
	s.Read()
	if !sameCmds(m.cmds(), 0x609C, 0xB098, 0x3517, 0x6458, 0xB098, 0x3517, 0x7CA2, 0xB098) {
		t.Fatalf("commands %x", m.cmds())
	}
}

func TestSHTC3SleepControl(t *testing.T) {
	m := &shtc3Mock{}
	m.queue(shtc3TRH(0x6666, 0x6000))
	m.queue(shtc3TRH(0x6666, 0x6000))
	s := NewSHTC3Full(m)
	s.SetSleepBetween(false)
	s.Read()
	s.Read()
	s.Sleep()
	s.Wake()
	if !sameCmds(m.cmds(), 0x7866, 0x7866, 0xB098, 0x3517) {
		t.Fatalf("commands %x", m.cmds())
	}
}

func TestSHTC3SoftResetAndID(t *testing.T) {
	m := &shtc3Mock{}
	s := NewSHTC3Full(m)
	s.SoftReset()
	if !sameCmds(m.cmds(), 0x805D) {
		t.Fatalf("reset %x", m.cmds())
	}
	m.writes = nil
	m.queue(shtc3Word16(0xAB07))
	if id, err := s.ReadID(); err != nil || id != 0xAB07 {
		t.Fatalf("id %x %v", id, err)
	}
	s.Sleep()
	m.writes = nil
	m.queue(shtc3Word16(0x0807))
	s.ReadID()
	if !sameCmds(m.cmds(), 0x3517, 0xEFC8, 0xB098) {
		t.Fatalf("id asleep %x", m.cmds())
	}
	bad := shtc3Word16(0x0807)
	bad[2] ^= 0x55
	m.queue(bad)
	if _, err := s.ReadID(); !errors.Is(err, ErrSHTC3CRC) {
		t.Fatalf("want CRC error, got %v", err)
	}
}

func TestSHTC3DewPoint(t *testing.T) {
	m := &shtc3Mock{}
	traw := uint16(math.Round((25.0 + 45.0) * 65536.0 / 175.0))
	m.queue(shtc3TRH(traw, 0x8000))
	m.queue(shtc3TRH(traw, 0))
	s := NewSHTC3Full(m)
	if d, _ := s.DewPoint(); !near32(d, 13.86, 0.15) {
		t.Fatalf("dew point %v", d)
	}
	if d, _ := s.DewPoint(); d > -20 {
		t.Fatalf("dry dew point %v", d)
	}
}
