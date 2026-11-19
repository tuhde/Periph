package color

import (
	"encoding/binary"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// as7262Sim simulates the AS7262 STATUS/WRITE/READ handshake over virtual registers.
type as7262Sim struct {
	v       [0x2C]byte
	pending int
	out     int
	vwrites [][2]byte
	stall   bool
}

func newAS7262Sim() *as7262Sim {
	s := &as7262Sim{pending: -1, out: -1}
	s.v[0], s.v[1], s.v[2], s.v[3] = 0x40, 0x3E, 0x41, 0x21
	s.v[4], s.v[5], s.v[6] = 0x08, 0xFF, 25
	return s
}

func (s *as7262Sim) ReadReg(reg uint32, length int) ([]byte, error) {
	switch reg {
	case 0x00:
		if s.stall {
			return []byte{0x02}, nil
		}
		if s.out >= 0 {
			return []byte{1}, nil
		}
		return []byte{0}, nil
	case 0x02:
		v := s.out
		s.out = -1
		if v < 0 {
			v = 0
		}
		return []byte{byte(v)}, nil
	}
	return make([]byte, length), nil
}

func (s *as7262Sim) WriteReg(reg uint32, data []byte) error {
	b := data[0]
	if s.pending >= 0 {
		r := s.pending
		s.pending = -1
		s.vwrites = append(s.vwrites, [2]byte{byte(r), b})
		if r == 4 {
			if b&0x80 != 0 {
				s.v[4], s.v[5], s.v[7] = 0x08, 0xFF, 0
				return nil
			}
			s.v[4] = b | (s.v[4] & 0x02)
			if (b>>2)&3 == 3 {
				s.v[4] |= 0x02
			}
		} else {
			s.v[r] = b
		}
	} else if b&0x80 != 0 {
		s.pending = int(b & 0x7F)
	} else {
		s.out = int(s.v[b])
		if b >= 0x08 {
			s.v[4] &^= 0x02
		}
	}
	return nil
}

func (s *as7262Sim) Write(data []byte) error                   { return nil }
func (s *as7262Sim) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (s *as7262Sim) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (s *as7262Sim) Close() error                              { return nil }
func (s *as7262Sim) Enable()                                   {}
func (s *as7262Sim) Disable()                                  {}
func (s *as7262Sim) IsEnabled() bool                           { return true }
func (s *as7262Sim) IntPin() connection.InputPin               { return nil }
func (s *as7262Sim) EnPin() connection.OutputPin               { return nil }

func (s *as7262Sim) loadCal(vals ...float32) {
	for i, f := range vals {
		binary.BigEndian.PutUint32(s.v[0x14+4*i:], math.Float32bits(f))
	}
}

func (s *as7262Sim) wrote(r, v byte) bool {
	for _, w := range s.vwrites {
		if w[0] == r && w[1] == v {
			return true
		}
	}
	return false
}

func as7262Near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func TestAS7262InitDefaults(t *testing.T) {
	s := newAS7262Sim()
	if _, err := NewAS7262Minimal(s); err != nil {
		t.Fatal(err)
	}
	if s.v[5] != 0x3B || (s.v[4]>>4)&3 != 2 || s.v[7] != 0 || !s.wrote(4, 0x80) {
		t.Errorf("init regs: %x", s.v[:8])
	}
}

func TestAS7262NotFound(t *testing.T) {
	s := newAS7262Sim()
	s.v[0] = 0
	if testing.Short() {
		t.Skip("slow")
	}
	if _, err := NewAS7262Minimal(s); !errors.Is(err, ErrAS7262NotFound) {
		t.Errorf("err = %v", err)
	}
}

func TestAS7262ReadCalibrated(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Minimal(s)
	s.loadCal(1, 2.5, 3, 4, 5, 6.5)
	c, err := d.Read()
	if err != nil || c.Violet != 1 || c.Blue != 2.5 || c.Red != 6.5 {
		t.Fatalf("Read = %v %v", c, err)
	}
	if !s.wrote(4, 0x2C) {
		t.Error("one-shot not started")
	}
	if temp, _ := d.Temperature(); temp != 25 {
		t.Errorf("temp = %v", temp)
	}
}

func TestAS7262ReadRawAndChannel(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	for i := 0; i < 12; i++ {
		s.v[8+i] = byte(1 + i%2)
	}
	r, err := d.ReadRaw()
	if err != nil || r.Violet != 0x0102 {
		t.Fatalf("ReadRaw = %v %v", r, err)
	}
	if v, _ := d.ReadChannelRaw(5); v != 0x0102 {
		t.Errorf("raw ch = %x", v)
	}
	s.loadCal(0, 0, 0, 0, 0, 7.25)
	if v, _ := d.ReadChannel(5); v != 7.25 {
		t.Errorf("cal ch = %v", v)
	}
	if _, err := d.ReadChannel(6); !errors.Is(err, ErrAS7262Argument) {
		t.Error("range not checked")
	}
}

func TestAS7262GainIntegrationMode(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	for _, c := range []struct {
		in   float32
		want float32
	}{{64, 64}, {3.7, 3.7}, {1, 1}, {16, 16}, {0, 1}, {3, 64}} {
		if err := d.SetGain(c.in); err != nil {
			t.Fatal(err)
		}
		if g, _ := d.GetGain(); g != c.want {
			t.Errorf("gain %v -> %v", c.in, g)
		}
	}
	if d.SetGain(5) == nil {
		t.Error("gain 5 accepted")
	}
	if d.SetIntegrationTime(50) != nil || s.v[5] != 18 {
		t.Errorf("INT_T = %d", s.v[5])
	}
	if ms, _ := d.GetIntegrationTime(); !as7262Near(ms, 50.4, 1e-6) {
		t.Errorf("ms = %v", ms)
	}
	if d.SetIntegrationTime(1) == nil || d.SetIntegrationTime(800) == nil {
		t.Error("range not checked")
	}
	d.SetMode(2)
	if m, _ := d.GetMode(); m != 2 {
		t.Errorf("mode %d ctrl %x", m, s.v[4])
	}
	if d.SetContinuous(3) == nil || d.SetMode(4) == nil {
		t.Error("mode range not checked")
	}
	d.SetInterrupt(true)
	if s.v[4]&0x40 == 0 {
		t.Error("INT not set")
	}
	d.SetInterrupt(false)
	if s.v[4]&0x40 != 0 {
		t.Error("INT not cleared")
	}
}

func TestAS7262LEDs(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	d.SetLED(true, 25)
	if s.v[7] != 0x18 {
		t.Errorf("LED = %x", s.v[7])
	}
	d.SetIndicatorLED(true, 4)
	if s.v[7] != 0x18|0x05 {
		t.Errorf("LED = %x", s.v[7])
	}
	d.SetLED(false, 100)
	if s.v[7] != 0x30|0x05 {
		t.Errorf("LED = %x", s.v[7])
	}
	if d.SetLED(true, 200) == nil || d.SetIndicatorLED(true, 9) == nil {
		t.Error("current range not checked")
	}
}

func TestAS7262VersionsAndVirtual(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	if a, b, _ := d.HardwareVersion(); a != 0x40 || b != 0x3E {
		t.Errorf("hw %x %x", a, b)
	}
	if v, _ := d.DeviceType(); v != 0x40 {
		t.Errorf("type %x", v)
	}
	if a, b, c, _ := d.FirmwareVersion(); a != 2 || b != 0x21&0x0F<<2|1 || c != 1 {
		t.Errorf("fw %d %d %d", a, b, c)
	}
	d.WriteVirtual(0x05, 0x10)
	if v, _ := d.ReadVirtual(0x05); v != 0x10 {
		t.Errorf("virtual = %x", v)
	}
	d.SoftReset()
	if s.v[5] != 0xFF {
		t.Error("reset defaults")
	}
}

func TestAS7262DataReadyAndPoll(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	d.Measure()
	if ok, _ := d.DataReady(); !ok {
		t.Error("data not ready")
	}
	if st, _ := d.PollInterrupt(); st != 1 {
		t.Error("poll")
	}
}

func TestAS7262HandshakeTimeout(t *testing.T) {
	s := newAS7262Sim()
	d, _ := NewAS7262Full(s)
	s.stall = true
	if testing.Short() {
		t.Skip("slow")
	}
	if _, err := d.ReadVirtual(0); !errors.Is(err, ErrAS7262Timeout) {
		t.Errorf("err = %v", err)
	}
}
