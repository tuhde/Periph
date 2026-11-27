package light

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// si1145Sim simulates the command engine: RESPONSE counter, parameter RAM and forced
// measurements filling the data registers and IRQ_STATUS.
type si1145Sim struct {
	regs      [0x40]byte
	params    [32]byte
	ps        [3]uint16
	commands  []byte
	ignore    bool
	failParam int
	pin       connection.InputPin
}

func newSi1145Sim(part, seq byte) *si1145Sim {
	m := &si1145Sim{ps: [3]uint16{1500, 1600, 1700}, failParam: -1}
	m.regs[0], m.regs[2] = part, seq
	m.params[0x00], m.params[0x02], m.params[0x0C], m.params[0x0F] = 0x60, 0x21, 0x04, 0x65
	return m
}

func (m *si1145Sim) last() byte { return m.commands[len(m.commands)-1] }

func (m *si1145Sim) put16(r int, v uint16) { m.regs[r], m.regs[r+1] = byte(v), byte(v>>8) }

func (m *si1145Sim) measure(ps, als bool) {
	ch := m.params[1]
	var st byte
	if als && ch&0xF0 != 0 {
		if ch&0x10 != 0 {
			m.put16(0x22, 260)
		}
		if ch&0x20 != 0 {
			m.put16(0x24, 300)
		}
		if ch&0x80 != 0 {
			m.put16(0x2C, 345)
		} else if ch&0x40 != 0 {
			if m.params[0x0F] == 0x65 {
				m.put16(0x2C, 0x1111)
			} else {
				m.put16(0x2C, 0x2222)
			}
		}
		st |= 0x01
	}
	if ps {
		for i := 0; i < 3; i++ {
			if ch&(1<<i) != 0 {
				m.put16(0x26+2*i, m.ps[i])
				st |= 0x04 << i
			}
		}
	}
	m.regs[0x21] |= st & m.regs[0x04]
}

func (m *si1145Sim) command(cmd byte) {
	m.commands = append(m.commands, cmd)
	if m.ignore {
		return
	}
	resp := m.regs[0x20]
	switch {
	case cmd == 0x00:
		m.regs[0x20] = 0
		return
	case cmd == 0x01:
		m.regs[0x20], m.regs[0x07] = 0, 0
		return
	case resp&0x80 != 0:
		return
	case cmd&0xE0 == 0xA0:
		off := cmd & 0x1F
		if int(off) == m.failParam {
			m.regs[0x20] = 0x80
			return
		}
		m.params[off] = m.regs[0x17]
		m.regs[0x2E] = m.regs[0x17]
	case cmd&0xE0 == 0x80:
		m.regs[0x2E] = m.params[cmd&0x1F]
	case cmd >= 0x05 && cmd <= 0x07:
		m.measure(cmd != 0x06, cmd != 0x05)
	case cmd == 0x12:
		for i := 0; i < 12; i++ {
			m.regs[0x22+i] = byte(0x10 + i)
		}
	case cmd == 0x02 || cmd == 0x09 || cmd == 0x0A || cmd == 0x0B || cmd == 0x0D || cmd == 0x0E || cmd == 0x0F:
	default:
		m.regs[0x20] = 0x80
		return
	}
	m.regs[0x20] = (resp + 1) & 0x0F
}

func (m *si1145Sim) Write(data []byte) error {
	for i, v := range data[1:] {
		switch r := int(data[0]) + i; r {
		case 0x18:
			m.command(v)
		case 0x21:
			m.regs[0x21] &^= v
		default:
			m.regs[r] = v
		}
	}
	return nil
}

func (m *si1145Sim) ReadReg(reg uint32, n int) ([]byte, error) {
	out := make([]byte, n)
	for i := range out {
		out[i] = m.regs[(int(reg)+i)&0x3F]
	}
	return out, nil
}

func (m *si1145Sim) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *si1145Sim) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *si1145Sim) WriteRead(data []byte, n int) ([]byte, error) {
	return m.ReadReg(uint32(data[0]), n)
}
func (m *si1145Sim) Close() error                { return nil }
func (m *si1145Sim) Enable()                     {}
func (m *si1145Sim) Disable()                    {}
func (m *si1145Sim) IsEnabled() bool             { return true }
func (m *si1145Sim) IntPin() connection.InputPin { return m.pin }
func (m *si1145Sim) EnPin() connection.OutputPin { return nil }

type si1145FakePin struct {
	handler func()
	trigger connection.Trigger
}

func (p *si1145FakePin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.handler, p.trigger = handler, trigger
	return func() { p.handler = nil }
}
func (p *si1145FakePin) IsHigh() (bool, error) { return true, nil }

func newSi1145(t *testing.T, part, seq byte) (*Si1145Full, *si1145Sim) {
	t.Helper()
	m := newSi1145Sim(part, seq)
	s, _ := NewSi1145Full(m)
	if err := s.Init(); err != nil {
		t.Fatalf("init: %v", err)
	}
	return s, m
}

func TestSi1145InitAndRead(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	if m.regs[0x07] != 0x17 || m.regs[0x13] != 0x7B || m.regs[0x14] != 0x6B || m.regs[0x15] != 0x01 {
		t.Fatalf("HW_KEY/UCOEF not written")
	}
	if m.params[1] != 0xB1 || m.params[2] != 0x01 || m.regs[0x0F] != 0x03 || m.regs[0x04] != 0x05 {
		t.Fatalf("defaults: chlist 0x%02X psled 0x%02X led 0x%02X irq 0x%02X", m.params[1], m.params[2], m.regs[0x0F], m.regs[0x04])
	}
	r, err := s.Read()
	if err != nil || r.Proximity != 1500 || r.Visible != 260 || r.Infrared != 300 || math.Abs(r.UVIndex-3.45) > 1e-9 {
		t.Fatalf("read %+v %v", r, err)
	}
	if m.regs[0x21] != 0 {
		t.Fatalf("IRQ_STATUS not cleared")
	}
	bad := newSi1145Sim(0x32, 0)
	b, _ := NewSi1145Minimal(bad)
	if err := b.Init(); !errors.Is(err, ErrSi1145NotFound) {
		t.Fatalf("wrong part: %v", err)
	}
}

func TestSi1145SingleReads(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	if v, err := s.ReadProximity(1); err != nil || v != 1500 || m.last() != 0x05 {
		t.Fatalf("proximity %d %v", v, err)
	}
	if _, err := s.ReadProximity(2); !errors.Is(err, ErrSi1145InvalidArgument) {
		t.Fatalf("ps2 on Si1145: %v", err)
	}
	if v, err := s.ReadUVIndex(); err != nil || math.Abs(v-3.45) > 1e-9 || m.last() != 0x06 {
		t.Fatalf("uv %v %v", v, err)
	}
	if v, _ := s.ReadVisible(); v != 260 {
		t.Fatalf("visible %d", v)
	}
	if v, _ := s.ReadInfrared(); v != 300 {
		t.Fatalf("infrared %d", v)
	}
	if raw, _ := s.ReadRaw(); raw != (Si1145Raw{Visible: 260, Infrared: 300, PS1: 1500, Aux: 345}) {
		t.Fatalf("raw %+v", raw)
	}
	if v, _ := s.ReadTemperatureRaw(); v != 0x1111 || m.params[1] != 0xB1 {
		t.Fatalf("temperature %d chlist 0x%02X", v, m.params[1])
	}
	if v, _ := s.ReadVDDRaw(); v != 0x2222 || m.params[0x0F] != 0x75 {
		t.Fatalf("vdd %d", v)
	}
	if err := s.SetChannels(false, true, false, true, false, false, false); err != nil || m.params[1] != 0x11 {
		t.Fatalf("set channels 0x%02X", m.params[1])
	}
	if _, err := s.ReadUVIndex(); !errors.Is(err, ErrSi1145ChannelDisabled) {
		t.Fatalf("uv disabled: %v", err)
	}
	if err := s.SetChannels(true, false, false, false, false, false, true); !errors.Is(err, ErrSi1145InvalidArgument) {
		t.Fatalf("uv+aux: %v", err)
	}
	if err := s.SetChannels(false, false, false, true, true, false, false); !errors.Is(err, ErrSi1145InvalidArgument) {
		t.Fatalf("ps2 on Si1145: %v", err)
	}
}

func TestSi1145Configuration(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	if err := s.SetLEDCurrent(1, 15); err != nil || m.regs[0x0F] != 0x0F {
		t.Fatalf("led current")
	}
	if s.SetLEDCurrent(2, 1) == nil || s.SetLEDCurrent(1, 16) == nil || s.SetPSLED(1, 2) == nil {
		t.Fatalf("Si1145 LED limits not enforced")
	}
	if err := s.SetPSLED(1, 0); err != nil || m.params[2] != 0 {
		t.Fatalf("ps led")
	}
	if err := s.SetPSPhotodiode(1, false); err != nil || m.params[7] != 0 {
		t.Fatalf("photodiode")
	}
	if err := s.SetGain(3, 2, 1); err != nil {
		t.Fatal(err)
	}
	p := m.params
	if p[0x11] != 3 || p[0x10] != 0x40 || p[0x1E] != 2 || p[0x1D] != 0x50 || p[0x0B] != 1 || p[0x0A] != 0x60 {
		t.Fatalf("gain params % X", p)
	}
	if s.SetGain(8, 0, 0) == nil {
		t.Fatalf("gain range")
	}
	if err := s.SetHighRange(true, true, true); err != nil || m.params[0x12] != 0x20 || m.params[0x1F] != 0x20 || m.params[0x0C] != 0x24 {
		t.Fatalf("high range")
	}
	if err := s.SetAlignment(true, false, true, false, false); err != nil || m.params[6] != 0x10 || m.params[5] != 0x10 {
		t.Fatalf("alignment")
	}
	if err := s.SetUVCoefficients([4]uint8{1, 2, 3, 4}); err != nil || m.regs[0x13] != 1 || m.regs[0x16] != 4 {
		t.Fatalf("ucoef")
	}
	if cal, err := s.ReadCalibration(); err != nil || cal[0] != 0x10 || cal[11] != 0x1B || m.last() != 0x12 {
		t.Fatalf("calibration % X %v", cal, err)
	}

	s47, m47 := newSi1145(t, 0x47, 0x08)
	_ = s47.SetLEDCurrent(2, 5)
	_ = s47.SetLEDCurrent(3, 9)
	_ = s47.SetPSLED(2, 2)
	_ = s47.SetPSLED(3, 4)
	if m47.regs[0x0F] != 0x53 || m47.regs[0x10] != 0x09 || m47.params[2] != 0x21 || m47.params[3] != 0x04 {
		t.Fatalf("Si1147 LEDs")
	}
	_ = s47.SetChannels(false, false, false, true, true, true, false)
	if v, err := s47.ReadProximity(3); err != nil || v != 1700 {
		t.Fatalf("ps3 %d %v", v, err)
	}
}

func TestSi1145AutonomousAndResponse(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	if err := s.StartAutonomous(0.1, true, true); err != nil || m.regs[0x08] != 0x80 || m.regs[0x09] != 0x0C || m.last() != 0x0F {
		t.Fatalf("autonomous %v", err)
	}
	if _ = s.StartAutonomous(0.01, false, true); m.last() != 0x0E {
		t.Fatalf("ALS_AUTO")
	}
	if _ = s.StartAutonomous(0.01, true, false); m.last() != 0x0D {
		t.Fatalf("PS_AUTO")
	}
	if s.StartAutonomous(3, true, true) == nil || s.StartAutonomous(0.1, false, false) == nil {
		t.Fatalf("autonomous range")
	}
	if _ = s.Pause(); m.last() != 0x0B {
		t.Fatalf("pause")
	}
	if _ = s.Force(true, true); m.last() != 0x07 {
		t.Fatalf("force")
	}
	if _ = s.Force(false, true); m.last() != 0x06 {
		t.Fatalf("force als")
	}
	sw, mw := newSi1145(t, 0x45, 0x01)
	_ = sw.StartAutonomous(0.1, true, true)
	if mw.regs[0x0A] != 0x80 || mw.regs[0x08] != 0x0C {
		t.Fatalf("swapped MEAS_RATE")
	}
	if r, _ := s.ReadResponse(); r.Error != 0 || r.Counter != m.regs[0x20] {
		t.Fatalf("response %+v", r)
	}
	m.failParam = 0x0B
	if err := s.SetGain(0, 0, 1); !errors.Is(err, ErrSi1145Command) || m.last() != 0x00 || m.regs[0x20] != 0 {
		t.Fatalf("param error: %v", err)
	}
	m.failParam = -1
	m.regs[0x20] = 0x8C
	if r, _ := s.ReadResponse(); r != (Si1145Response{Error: 0x8C}) {
		t.Fatalf("error response %+v", r)
	}
	if err := s.SetGain(0, 0, 0); err != nil || m.regs[0x20]&0x80 != 0 {
		t.Fatalf("pending error not cleared: %v", err)
	}
}

func TestSi1145InterruptAddressReset(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	if _ = s.EnableInterrupt(Si1145SourceCmd); m.regs[0x03] != 0x01 || m.regs[0x04] != 0x25 {
		t.Fatalf("enable interrupt")
	}
	if _ = s.DisableInterrupt(Si1145SourceCmd); m.regs[0x04] != 0x05 {
		t.Fatalf("disable interrupt")
	}
	m.regs[0x21] = 0x05
	if st, _ := s.PollInterrupt(); st != 0x05 {
		t.Fatalf("poll %02X", st)
	}
	if st, _ := s.PollInterrupt(); st != 0 {
		t.Fatalf("poll not cleared")
	}
	pin := &si1145FakePin{}
	m.pin = pin
	var got []uint8
	_ = s.OnInterrupt(func(st uint8) { got = append(got, st) })
	if pin.handler == nil || pin.trigger != connection.Falling {
		t.Fatalf("not subscribed on falling edge")
	}
	m.regs[0x21] = 0x04
	pin.handler()
	pin.handler()
	if len(got) != 1 || got[0] != 0x04 {
		t.Fatalf("callback %v", got)
	}
	_ = s.OffInterrupt()
	if pin.handler != nil {
		t.Fatalf("off interrupt")
	}
	_ = s.DisableInterrupt(0x3D)
	if r, err := s.Read(); err != nil || r.Proximity != 1500 {
		t.Fatalf("read without irq %v", err)
	}
	if p, r, q, _ := s.ReadID(); p != 0x45 || r != 0 || q != 0x08 {
		t.Fatalf("id")
	}
	if err := s.SetI2CAddress(0x30); err != nil || m.params[0] != 0x30 || m.last() != 0x02 {
		t.Fatalf("address")
	}
	if s.SetI2CAddress(0x80) == nil {
		t.Fatalf("address range")
	}
	m.regs[0x0F] = 0x0F
	if err := s.Reset(); err != nil || m.regs[0x07] != 0x17 || m.params[1] != 0xB1 || m.regs[0x0F] != 0x03 {
		t.Fatalf("reset %v", err)
	}
}

func TestSi1145Timeouts(t *testing.T) {
	s, m := newSi1145(t, 0x45, 0x08)
	m.ignore = true
	if _, err := s.Read(); !errors.Is(err, ErrSi1145Timeout) {
		t.Fatalf("measure timeout: %v", err)
	}
	if err := s.Pause(); !errors.Is(err, ErrSi1145Timeout) {
		t.Fatalf("command timeout: %v", err)
	}
}
