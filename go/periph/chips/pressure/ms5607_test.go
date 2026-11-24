package pressure

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ms5607Mock is an in-memory fake connection.Connection: it logs writes and
// serves plain reads from a FIFO queue.
type ms5607Mock struct {
	writes    [][]byte
	readQueue [][]byte
	failWrite int
}

func (m *ms5607Mock) queue(b []byte) { m.readQueue = append(m.readQueue, b) }

func (m *ms5607Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.failWrite > 0 {
		m.failWrite--
		return errors.New("NACK")
	}
	return nil
}

func (m *ms5607Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

func (m *ms5607Mock) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return make([]byte, n), nil
}
func (m *ms5607Mock) Close() error                { return nil }
func (m *ms5607Mock) Enable()                     {}
func (m *ms5607Mock) Disable()                    {}
func (m *ms5607Mock) IsEnabled() bool             { return true }
func (m *ms5607Mock) IntPin() connection.InputPin { return nil }
func (m *ms5607Mock) EnPin() connection.OutputPin { return nil }

func (m *ms5607Mock) cmds() []byte {
	var out []byte
	for _, w := range m.writes {
		out = append(out, w[0])
	}
	return out
}

var (
	ms5607Coeffs = [6]uint16{46372, 43981, 29059, 27842, 31553, 28165}
	ms5607D1     = uint32(6465444)
	ms5607D2     = uint32(8077636)
)

func ms5607Prom() [8]uint16 {
	w := [8]uint16{0x3132, ms5607Coeffs[0], ms5607Coeffs[1], ms5607Coeffs[2], ms5607Coeffs[3], ms5607Coeffs[4], ms5607Coeffs[5], 0x5A3 << 4}
	w[7] |= uint16(MS5607CRC4(w))
	return w
}

func (m *ms5607Mock) queueProm(corrupt bool) {
	w := ms5607Prom()
	if corrupt {
		w[3] ^= 0x0100
	}
	for _, x := range w {
		m.queue([]byte{byte(x >> 8), byte(x)})
	}
}

func (m *ms5607Mock) queueADC(v uint32) { m.queue([]byte{byte(v >> 16), byte(v >> 8), byte(v)}) }

func ms5607Ready(t *testing.T) (*ms5607Mock, *MS5607Full) {
	m := &ms5607Mock{}
	m.queueProm(false)
	s := NewMS5607Full(m)
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	m.writes = nil
	return m, s
}

func ms5607Near(a, b, tol float32) bool { return float32(math.Abs(float64(a-b))) <= tol }

func ms5607Bytes(want ...byte) []byte { return want }

func sameBytes(a, b []byte) bool {
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

func TestMS5607CRC4(t *testing.T) {
	if MS5607CRC4([8]uint16{}) != 0 {
		t.Fatal("zero PROM")
	}
	p := ms5607Prom()
	if MS5607CRC4(p) != uint8(p[7]&0xF) {
		t.Fatal("roundtrip")
	}
	p[3] ^= 0x0100
	if MS5607CRC4(p) == uint8(p[7]&0xF) {
		t.Fatal("corruption undetected")
	}
}

func TestMS5607InitSequence(t *testing.T) {
	m := &ms5607Mock{}
	m.queueProm(false)
	s := NewMS5607Full(m)
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	if !sameBytes(m.cmds(), ms5607Bytes(0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE)) {
		t.Fatalf("init sequence %x", m.cmds())
	}
	if s.Coefficients() != ms5607Coeffs || !s.PROMCRCOK() || s.SerialCode() != 0x5A3 {
		t.Fatal("prom data")
	}
}

func TestMS5607InitBadCRC(t *testing.T) {
	m := &ms5607Mock{}
	m.queueProm(true)
	if err := NewMS5607Minimal(m).Init(); !errors.Is(err, ErrMS5607CRC) {
		t.Fatalf("want crc error, got %v", err)
	}
}

func TestMS5607BusError(t *testing.T) {
	m := &ms5607Mock{failWrite: 1}
	if err := NewMS5607Minimal(m).Init(); err == nil {
		t.Fatal("bus error swallowed")
	}
}

func TestMS5607Compensate(t *testing.T) {
	_, s := ms5607Ready(t)
	cases := []struct {
		d2     uint32
		second bool
		t, p   float32
	}{
		{ms5607D2, true, 20.0, 110002},
		{ms5607D2, false, 20.0, 110002},
		{ms5607D2 - 300000, true, 9.52, 107505},
		{ms5607D2 - 300000, false, 9.93, 107577},
		{ms5607D2 - 1200000, true, -26.98, 99055},
		{ms5607D2 - 1200000, false, -20.28, 100302},
	}
	for _, c := range cases {
		tt, p := s.Compensate(ms5607D1, c.d2, c.second)
		if !ms5607Near(tt, c.t, 0.001) || !ms5607Near(p, c.p, 0.5) {
			t.Fatalf("d2=%d second=%v: got %v %v want %v %v", c.d2, c.second, tt, p, c.t, c.p)
		}
	}
}

func TestMS5607ReadCommands(t *testing.T) {
	m := &ms5607Mock{}
	m.queueProm(false)
	s := NewMS5607Minimal(m)
	_ = s.Init()
	m.writes = nil
	m.queueADC(ms5607D2)
	m.queueADC(ms5607D1)
	tt, p, err := s.Read()
	if err != nil || !ms5607Near(tt, 20, 0.001) || !ms5607Near(p, 110002, 0.5) {
		t.Fatalf("read %v %v %v", tt, p, err)
	}
	if !sameBytes(m.cmds(), ms5607Bytes(0x58, 0x00, 0x48, 0x00)) {
		t.Fatalf("commands %x", m.cmds())
	}
	m.queueADC(0)
	if _, _, err := s.Read(); !errors.Is(err, ErrMS5607NotReady) {
		t.Fatalf("want not ready, got %v", err)
	}
}

func TestMS5607SingleValuesAndRaw(t *testing.T) {
	m, s := ms5607Ready(t)
	m.queueADC(ms5607D2)
	m.queueADC(ms5607D1)
	if p, err := s.ReadPressure(); err != nil || !ms5607Near(p, 110002, 0.5) {
		t.Fatal("pressure")
	}
	m.writes = nil
	m.queueADC(ms5607D2)
	if tt, err := s.ReadTemperature(); err != nil || !ms5607Near(tt, 20, 0.001) {
		t.Fatal("temperature")
	}
	if !sameBytes(m.cmds(), ms5607Bytes(0x58, 0x00)) {
		t.Fatal("temperature is D2 only")
	}
	m.queueADC(ms5607D2)
	m.queueADC(ms5607D1)
	d1, d2, err := s.ReadRaw()
	if err != nil || d1 != ms5607D1 || d2 != ms5607D2 {
		t.Fatal("raw")
	}
}

func TestMS5607Oversampling(t *testing.T) {
	for i, osr := range []uint16{256, 512, 1024, 2048, 4096} {
		m, s := ms5607Ready(t)
		if err := s.SetOversampling(osr); err != nil || s.Oversampling() != osr {
			t.Fatal("set/get")
		}
		m.queueADC(ms5607D2)
		m.queueADC(ms5607D1)
		if _, _, err := s.Read(); err != nil {
			t.Fatal(err)
		}
		if m.writes[0][0] != byte(0x50|i<<1) || m.writes[2][0] != byte(0x40|i<<1) {
			t.Fatalf("osr %d commands %x", osr, m.cmds())
		}
	}
	_, s := ms5607Ready(t)
	if err := s.SetOversampling(300); !errors.Is(err, ErrMS5607InvalidOSR) {
		t.Fatal("invalid osr accepted")
	}
	if s.Oversampling() != 4096 {
		t.Fatal("default osr")
	}
}

func TestMS5607ConversionTime(t *testing.T) {
	_, s := ms5607Ready(t)
	want := map[uint16]int64{256: 600, 512: 1170, 1024: 2280, 2048: 4540, 4096: 9040}
	for osr, us := range want {
		d, err := s.ConversionTime(osr)
		if err != nil || d.Microseconds() != us {
			t.Fatalf("osr %d: %v %v", osr, d, err)
		}
	}
	if d, _ := s.ConversionTime(0); d.Microseconds() != 9040 {
		t.Fatal("current")
	}
	if _, err := s.ConversionTime(100); !errors.Is(err, ErrMS5607InvalidOSR) {
		t.Fatal("invalid")
	}
}

func TestMS5607NonBlocking(t *testing.T) {
	m, s := ms5607Ready(t)
	if err := s.StartConversion(MS5607Temperature, 0); err != nil {
		t.Fatal(err)
	}
	if err := s.StartConversion(MS5607Pressure, 512); err != nil {
		t.Fatal(err)
	}
	if !sameBytes(m.cmds(), ms5607Bytes(0x58, 0x42)) {
		t.Fatalf("commands %x", m.cmds())
	}
	if err := s.StartConversion(MS5607Pressure, 99); !errors.Is(err, ErrMS5607InvalidOSR) {
		t.Fatal("invalid osr")
	}
	m.queueADC(ms5607D1)
	if v, err := s.ReadADC(); err != nil || v != ms5607D1 {
		t.Fatal("adc")
	}
	m.queueADC(0)
	if _, err := s.ReadADC(); !errors.Is(err, ErrMS5607NotReady) {
		t.Fatal("zero adc")
	}
}

func TestMS5607ResetAndReadPROM(t *testing.T) {
	m, s := ms5607Ready(t)
	m.queueProm(false)
	if err := s.Reset(); err != nil {
		t.Fatal(err)
	}
	if m.writes[0][0] != 0x1E || len(m.writes) != 9 {
		t.Fatal("reset commands")
	}
	m.queueProm(false)
	w, err := s.ReadPROM()
	if err != nil || w != ms5607Prom() {
		t.Fatal("read prom")
	}
}

func TestMS5607Altitude(t *testing.T) {
	m, s := ms5607Ready(t)
	m.queueADC(ms5607D2)
	m.queueADC(ms5607D1)
	want := float32(44330.0 * (1.0 - math.Pow(110002.0/101325.0, 1.0/5.255)))
	h, err := s.Altitude(101325)
	if err != nil || !ms5607Near(h, want, 0.01) || h >= 0 {
		t.Fatalf("altitude %v", h)
	}
	m.queueADC(ms5607D2)
	m.queueADC(ms5607D1)
	if h, _ := s.Altitude(110002); !ms5607Near(h, 0, 0.01) {
		t.Fatalf("reference altitude %v", h)
	}
}
