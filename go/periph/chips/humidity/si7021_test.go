package humidity

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// si7021Mock is an in-memory fake connection.Connection: it logs writes and
// WriteRead requests, serves plain reads from a FIFO queue (optionally NACKing
// reads first) and WriteRead from a map keyed by the request bytes.
type si7021Mock struct {
	writes    [][]byte
	readQueue [][]byte
	nackReads int
	wr        map[string][]byte
}

func (m *si7021Mock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *si7021Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *si7021Mock) Read(n int) ([]byte, error) {
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

func (m *si7021Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	copy(out, m.wr[string(data)])
	return out, nil
}
func (m *si7021Mock) Close() error                { return nil }
func (m *si7021Mock) Enable()                     {}
func (m *si7021Mock) Disable()                    {}
func (m *si7021Mock) IsEnabled() bool             { return true }
func (m *si7021Mock) IntPin() connection.InputPin { return nil }
func (m *si7021Mock) EnPin() connection.OutputPin { return nil }

func (m *si7021Mock) first() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

func (m *si7021Mock) lastWrite(want ...byte) bool {
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

func siFrame(code uint16) []byte {
	d := []byte{byte(code >> 8), byte(code)}
	return []byte{d[0], d[1], Si7021CRC8(d)}
}

func siRH(rh float64) uint16 { return uint16(math.Round((rh + 6) * 65536 / 125)) }
func siT(t float64) []byte {
	c := uint16(math.Round((t + 46.85) * 65536 / 175.72))
	return []byte{byte(c >> 8), byte(c)}
}

func siNear(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func siBytesEq(a []byte, b ...byte) bool {
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

func TestSi7021CRCReference(t *testing.T) {
	if Si7021CRC8([]byte{0x00, 0x00}) != 0x00 || Si7021CRC8([]byte{0x01}) != 0x31 {
		t.Error("CRC reference values wrong")
	}
}

func TestSi7021ReadCommands(t *testing.T) {
	m := &si7021Mock{}
	s := NewSi7021Full(m)
	if len(m.writes) != 0 {
		t.Error("constructor accessed the bus")
	}
	m.queue(siFrame(siRH(50)))
	m.queue(siT(25))
	temp, rh, err := s.Read()
	if err != nil || !siBytesEq(m.first(), 0xF5, 0xE0) {
		t.Fatalf("Read: %v cmds %x", err, m.first())
	}
	if !siNear(float64(rh), 50, 0.02) || !siNear(float64(temp), 25, 0.02) {
		t.Errorf("Read = %v %v", temp, rh)
	}
	if NewSi7021Minimal(&si7021Mock{}) == nil {
		t.Error("minimal constructor")
	}
}

func TestSi7021PollsAfterNack(t *testing.T) {
	m := &si7021Mock{nackReads: 3}
	s := NewSi7021Full(m)
	m.queue(siFrame(siRH(40)))
	m.queue([]byte{0x66, 0x66})
	_, rh, err := s.Read()
	if err != nil || !siNear(float64(rh), 40, 0.02) {
		t.Errorf("Read after NACKs: %v %v", rh, err)
	}
}

func TestSi7021Timeout(t *testing.T) {
	m := &si7021Mock{nackReads: 1000}
	if _, _, err := NewSi7021Full(m).Read(); !errors.Is(err, ErrSi7021Timeout) {
		t.Errorf("err = %v, want timeout", err)
	}
}

func TestSi7021CRCMismatch(t *testing.T) {
	m := &si7021Mock{}
	bad := siFrame(0x6000)
	bad[2] ^= 0xFF
	m.queue(bad)
	if _, _, err := NewSi7021Full(m).Read(); !errors.Is(err, ErrSi7021CRC) {
		t.Errorf("err = %v, want CRC", err)
	}
}

func TestSi7021Clamp(t *testing.T) {
	m := &si7021Mock{}
	s := NewSi7021Full(m)
	m.queue(siFrame(0x0000))
	if _, rh, _ := s.Read(); rh != 0 {
		t.Errorf("low clamp = %v", rh)
	}
	m.queue(siFrame(0xFFFC))
	if _, rh, _ := s.Read(); rh != 100 {
		t.Errorf("high clamp = %v", rh)
	}
}

func TestSi7021SingleValuesAndRaw(t *testing.T) {
	m := &si7021Mock{}
	s := NewSi7021Full(m)
	m.queue(siFrame(siRH(30)))
	if v, err := s.ReadHumidity(); err != nil || !siNear(float64(v), 30, 0.02) {
		t.Errorf("ReadHumidity = %v %v", v, err)
	}
	m.queue(siFrame(uint16(math.Round((-10 + 46.85) * 65536 / 175.72))))
	if v, err := s.ReadTemperature(); err != nil || !siNear(float64(v), -10, 0.02) {
		t.Errorf("ReadTemperature = %v %v", v, err)
	}
	m.queue(siT(20))
	if v, err := s.ReadTemperaturePrevious(); err != nil || !siNear(float64(v), 20, 0.02) {
		t.Errorf("ReadTemperaturePrevious = %v %v", v, err)
	}
	if !siBytesEq(m.first(), 0xF5, 0xF3, 0xE0) {
		t.Errorf("commands = %x", m.first())
	}
	m.queue(siFrame(0x1234))
	if v, _ := s.ReadRawHumidity(); v != 0x1234 {
		t.Errorf("ReadRawHumidity = %x", v)
	}
	m.queue(siFrame(0x4321))
	if v, _ := s.ReadRawTemperature(); v != 0x4321 {
		t.Errorf("ReadRawTemperature = %x", v)
	}
}

func TestSi7021HoldMaster(t *testing.T) {
	m := &si7021Mock{}
	s := NewSi7021Full(m)
	s.SetClockStretching(true)
	m.queue(siFrame(siRH(50)))
	m.queue(siT(25))
	s.Read()
	m.queue(siFrame(0x6666))
	s.ReadTemperature()
	if !siBytesEq(m.first(), 0xE5, 0xE0, 0xE3) {
		t.Errorf("hold-master commands = %x", m.first())
	}
}

func TestSi7021Resolution(t *testing.T) {
	m := &si7021Mock{wr: map[string][]byte{"\xE7": {0x3A}}}
	s := NewSi7021Full(m)
	for _, c := range []struct {
		rh, t int
		want  byte
	}{{11, 11, 0xBB}, {8, 12, 0x3B}, {10, 13, 0xBA}} {
		if err := s.SetResolution(c.rh, c.t); err != nil || !m.lastWrite(0xE6, c.want) {
			t.Errorf("SetResolution(%d, %d) = %v, last write %x", c.rh, c.t, err, m.writes[len(m.writes)-1])
		}
	}
	if err := s.SetResolution(12, 12); !errors.Is(err, ErrSi7021Resolution) {
		t.Errorf("invalid pair err = %v", err)
	}
	for _, c := range []struct {
		reg   byte
		rh, t int
	}{{0xBB, 11, 11}, {0x3B, 8, 12}, {0xBA, 10, 13}, {0x3A, 12, 14}} {
		m.wr["\xE7"] = []byte{c.reg}
		if rh, tb, err := s.GetResolution(); err != nil || rh != c.rh || tb != c.t {
			t.Errorf("GetResolution(0x%02X) = %d/%d %v", c.reg, rh, tb, err)
		}
	}
}

func TestSi7021HeaterVDDAndUserRegister(t *testing.T) {
	m := &si7021Mock{wr: map[string][]byte{"\xE7": {0x3A}}}
	s := NewSi7021Full(m)
	s.SetHeater(true)
	if !m.lastWrite(0xE6, 0x3E) {
		t.Errorf("heater on write = %x", m.writes[len(m.writes)-1])
	}
	m.wr["\xE7"] = []byte{0x3E}
	if on, _ := s.IsHeaterOn(); !on {
		t.Error("IsHeaterOn = false")
	}
	s.SetHeater(false)
	if !m.lastWrite(0xE6, 0x3A) {
		t.Errorf("heater off write = %x", m.writes[len(m.writes)-1])
	}
	m.wr["\xE7"] = []byte{0x3A}
	if low, _ := s.VDDLow(); low {
		t.Error("VDDLow = true")
	}
	m.wr["\xE7"] = []byte{0x7A}
	if low, _ := s.VDDLow(); !low {
		t.Error("VDDLow = false")
	}
	m.wr["\xE7"] = []byte{0x3A}
	if v, _ := s.ReadUserRegister(); v != 0x3A {
		t.Errorf("ReadUserRegister = %x", v)
	}
	s.WriteUserRegister(0x00)
	if !m.lastWrite(0xE6, 0x3A) {
		t.Errorf("reserved bits not preserved: %x", m.writes[len(m.writes)-1])
	}
	if ct, _ := s.ConversionTime(); !siNear(float64(ct), 0.017, 1e-6) {
		t.Errorf("ConversionTime = %v", ct)
	}
}

func TestSi7021ResetAndIdentification(t *testing.T) {
	m := &si7021Mock{wr: map[string][]byte{
		"\xFA\x0F": {0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0},
		"\xFC\xC9": {0x15, 0x66, 0, 0x77, 0x88, 0},
		"\x84\xB8": {0xFF},
	}}
	s := NewSi7021Full(m)
	s.SoftReset()
	if !m.lastWrite(0xFE) {
		t.Errorf("reset write = %x", m.writes[len(m.writes)-1])
	}
	if sn, _ := s.SerialNumber(); sn != 0x1122334415667788 {
		t.Errorf("SerialNumber = %x", sn)
	}
	if dt, _ := s.DeviceType(); dt != 0x15 {
		t.Errorf("DeviceType = %x", dt)
	}
	if fw, _ := s.FirmwareRevision(); fw != 0xFF {
		t.Errorf("FirmwareRevision = %x", fw)
	}
}

func TestSi7021DewPoint(t *testing.T) {
	m := &si7021Mock{}
	s := NewSi7021Full(m)
	m.queue(siFrame(siRH(50)))
	m.queue(siT(25))
	if dp, err := s.DewPoint(); err != nil || !siNear(float64(dp), 13.86, 0.05) {
		t.Errorf("DewPoint = %v %v", dp, err)
	}
}
