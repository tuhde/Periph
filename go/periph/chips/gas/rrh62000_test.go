package gas

import (
	"bytes"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// rrh62000Mock is a register-addressed fake with 1-byte register addresses.
type rrh62000Mock struct {
	registers map[uint32]byte
	writes    [][]byte
	nack      int
}

func newRRH62000Mock() *rrh62000Mock { return &rrh62000Mock{registers: map[uint32]byte{}} }

func (m *rrh62000Mock) set(reg uint32, values ...byte) {
	for i, v := range values {
		m.registers[reg+uint32(i)] = v
	}
}

func (m *rrh62000Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.nack > 0 {
		m.nack--
		return errors.New("NACK")
	}
	for i, v := range data[1:] {
		m.registers[uint32(data[0])+uint32(i)] = v
	}
	return nil
}

func (m *rrh62000Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *rrh62000Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.nack > 0 {
		m.nack--
		return nil, errors.New("NACK")
	}
	out := make([]byte, n)
	for i := range out {
		out[i] = m.registers[uint32(data[0])+uint32(i)]
	}
	return out, nil
}

func (m *rrh62000Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *rrh62000Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *rrh62000Mock) Close() error                { return nil }
func (m *rrh62000Mock) Enable()                     {}
func (m *rrh62000Mock) Disable()                    {}
func (m *rrh62000Mock) IsEnabled() bool             { return true }
func (m *rrh62000Mock) IntPin() connection.InputPin { return nil }
func (m *rrh62000Mock) EnPin() connection.OutputPin { return nil }

var rrh62000Words = [17]uint16{1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230, 2345, 4567, 30, 812, 215, 0}

func rrh62000Block(status uint16, words [17]uint16) []byte {
	b := []byte{byte(status), byte(status >> 8)}
	for _, w := range words {
		b = append(b, byte(w>>8), byte(w))
	}
	return append(b, RRH62000CRC8(b))
}

func rrh62000Near(a, b float64) bool { return math.Abs(a-b) < 1e-9 }

func newRRH62000(t *testing.T) (*rrh62000Mock, *RRH62000Full) {
	t.Helper()
	m := newRRH62000Mock()
	m.set(0x75, 2, 7)
	s, err := NewRRH62000Full(m)
	if err != nil {
		t.Fatal(err)
	}
	s.AccessInterval = 0
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	return m, s
}

func TestRRH62000CRC(t *testing.T) {
	if RRH62000CRC8([]byte{0xBE, 0xEF}) != 0x92 || RRH62000CRC8([]byte{0, 0}) != 0x81 {
		t.Fatal("crc8")
	}
}

func TestRRH62000InitAndRead(t *testing.T) {
	m, s := newRRH62000(t)
	if !bytes.Equal(m.writes[len(m.writes)-1], []byte{0x75}) {
		t.Fatal("init must read FWVER")
	}
	m.set(0x40, 1)
	m.set(0x00, rrh62000Block(5, rrh62000Words)...)
	r, err := s.Read()
	if err != nil {
		t.Fatal(err)
	}
	if !rrh62000Near(r.PM1UgM3, 5.2) || !rrh62000Near(r.PM2_5UgM3, 12.5) || !rrh62000Near(r.PM10UgM3, 20.3) {
		t.Fatalf("pm %+v", r)
	}
	if !rrh62000Near(r.TemperatureC, 23.45) || !rrh62000Near(r.HumidityRH, 45.67) || r.TVOC != 300 || r.ECO2Ppm != 812 || !rrh62000Near(r.IAQ, 2.15) {
		t.Fatalf("values %+v", r)
	}
	n := len(m.writes)
	if !bytes.Equal(m.writes[n-2], []byte{0x40}) || !bytes.Equal(m.writes[n-1], []byte{0x00}) {
		t.Fatal("read must poll DATA then read the block")
	}
}

func TestRRH62000FullBlock(t *testing.T) {
	m, s := newRRH62000(t)
	m.set(0x40, 1)
	m.set(0x00, rrh62000Block(5, rrh62000Words)...)
	d, err := s.ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	if d.Status != (RRH62000Status{Raw: 5, HighConcentration: true, FanSpeed: true}) {
		t.Fatalf("status %+v", d.Status)
	}
	if !rrh62000Near(d.Particles.NC0_3, 123.4) || !rrh62000Near(d.Particles.NC4, 2) || !rrh62000Near(d.Particles.PM1SmokeUgM3, 6.1) || !rrh62000Near(d.Particles.PM10SmokeUgM3, 23) {
		t.Fatalf("particles %+v", d.Particles)
	}
	var neg [17]uint16
	neg[11] = uint16(0xFE00) // -512
	m.set(0x00, rrh62000Block(2, neg)...)
	m.writes = nil
	st, err := s.ReadStatus()
	if err != nil || !st.DustAccumulation || st.HighConcentration || st.Raw != 2 || len(m.writes) != 1 {
		t.Fatalf("status %+v %v", st, err)
	}
	if d, _ := s.ReadAll(); !rrh62000Near(d.TemperatureC, -5.12) {
		t.Fatalf("negative temperature %v", d.TemperatureC)
	}
	m.set(0x00, rrh62000Block(0, rrh62000Words)...)
	p, err := s.ReadParticles()
	if err != nil || !rrh62000Near(p.PM2_5SmokeUgM3, 14) || !rrh62000Near(p.NC1, 50) {
		t.Fatalf("particles %+v %v", p, err)
	}
}

func TestRRH62000Errors(t *testing.T) {
	m, s := newRRH62000(t)
	m.set(0x40, 1)
	bad := rrh62000Block(0, rrh62000Words)
	bad[36] ^= 0xFF
	m.set(0x00, bad...)
	if _, err := s.Read(); err != ErrRRH62000CRC {
		t.Fatalf("crc: %v", err)
	}
	m.set(0x00, bytes.Repeat([]byte{0xFF}, 37)...)
	if _, err := s.Read(); err != ErrRRH62000Invalid {
		t.Fatalf("invalid: %v", err)
	}
	m.set(0x40, 0)
	m.set(0x00, rrh62000Block(0, rrh62000Words)...)
	s.DataTimeout = 0
	if _, err := s.Read(); err != ErrRRH62000Timeout {
		t.Fatalf("timeout: %v", err)
	}
	m.nack = 1
	if _, err := s.ReadStatus(); err == nil {
		t.Fatal("bus error expected")
	}
}

func TestRRH62000Commands(t *testing.T) {
	m, s := newRRH62000(t)
	last := func() []byte { return m.writes[len(m.writes)-1] }
	if r, _ := s.DataReady(); r {
		t.Fatal("data ready")
	}
	m.set(0x40, 1)
	if r, _ := s.DataReady(); !r {
		t.Fatal("data ready true")
	}
	checks := []struct {
		f    func() error
		want []byte
	}{
		{s.Sleep, []byte{0x50, 0x00}},
		{s.Wake, []byte{0x50, 0x80}},
		{s.StartCleaning, []byte{0x51, 0x01}},
		{s.SoftReset, []byte{0x52, 0x81}},
	}
	for _, c := range checks {
		if err := c.f(); err != nil || !bytes.Equal(last(), c.want) {
			t.Fatalf("command %X: %v", c.want, err)
		}
	}
	m.nack = 1
	if err := s.SoftReset(); err != nil {
		t.Fatal("soft reset must ignore a NACK")
	}
	m.set(0x74, 1)
	if done, _ := s.CleaningDone(); !done {
		t.Fatal("cleaning done")
	}
}

func TestRRH62000Settings(t *testing.T) {
	m, s := newRRH62000(t)
	if s.SetMovingAverage(30) != nil {
		t.Fatal("set moving average")
	}
	if v, _ := s.GetMovingAverage(); v != 30 {
		t.Fatal("moving average")
	}
	if s.SetFanSpeed(70) != nil {
		t.Fatal("set fan")
	}
	if v, _ := s.GetFanSpeed(); v != 70 {
		t.Fatal("fan speed")
	}
	if s.SetCleaningInterval(86400) != nil || m.registers[0x5A] != 0x0B || m.registers[0x5B] != 0x40 {
		t.Fatal("cleaning interval bytes")
	}
	if v, _ := s.GetCleaningInterval(); v != 86400 {
		t.Fatal("cleaning interval")
	}
	if s.SetCleaningTime(20) != nil {
		t.Fatal("set cleaning time")
	}
	if v, _ := s.GetCleaningTime(); v != 20 {
		t.Fatal("cleaning time")
	}
	for _, err := range []error{s.SetMovingAverage(0), s.SetMovingAverage(61), s.SetFanSpeed(59), s.SetFanSpeed(101),
		s.SetCleaningInterval(31), s.SetCleaningInterval(1814430), s.SetCleaningTime(61)} {
		if err != ErrRRH62000Argument {
			t.Fatalf("range check: %v", err)
		}
	}
	m.set(0x71, 0x00, 0x01, 0xE2, 0x40)
	if v, _ := s.ReadMoxResistance(); v != 123456 {
		t.Fatal("mox")
	}
	m.set(0x72, 1, 2, 3, 4, 5, 6)
	if v, _ := s.UniqueID(); !bytes.Equal(v, []byte{1, 2, 3, 4, 5, 6}) {
		t.Fatal("uid")
	}
	m.set(0x73, 3, 1, 4)
	if a, b, c, _ := s.AlgorithmVersion(); a != 3 || b != 1 || c != 4 {
		t.Fatal("algorithm version")
	}
	m.set(0x75, 2, 7)
	if a, b, _ := s.FirmwareVersion(); a != 2 || b != 7 {
		t.Fatal("firmware version")
	}
}
