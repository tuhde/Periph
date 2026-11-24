package color

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// isl29125Mock is a register-map mock of the ISL29125 with NACK injection.
type isl29125Mock struct {
	regs   map[uint8]uint8
	writes [][]byte
	failN  int
}

func newISL29125Mock(id uint8) *isl29125Mock {
	return &isl29125Mock{regs: map[uint8]uint8{0x00: id, 0x01: 0x00, 0x02: 0x00, 0x03: 0x00, 0x08: 0x02}}
}

func (m *isl29125Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if m.failN > 0 {
		m.failN--
		return nil, errors.New("nack")
	}
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[uint8(reg)+uint8(i)]
	}
	return out, nil
}

func (m *isl29125Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if m.failN > 0 {
		m.failN--
		return errors.New("nack")
	}
	for i, v := range data {
		m.regs[uint8(reg)+uint8(i)] = v
	}
	return nil
}

func (m *isl29125Mock) Write(data []byte) error                   { return nil }
func (m *isl29125Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *isl29125Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *isl29125Mock) Close() error                              { return nil }
func (m *isl29125Mock) Enable()                                   {}
func (m *isl29125Mock) Disable()                                  {}
func (m *isl29125Mock) IsEnabled() bool                           { return true }
func (m *isl29125Mock) IntPin() connection.InputPin               { return nil }
func (m *isl29125Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*isl29125Mock)(nil)

func (m *isl29125Mock) dataWrites() [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			out = append(out, w)
		}
	}
	return out
}

func newISL29125(t *testing.T) (*isl29125Mock, *ISL29125Full) {
	t.Helper()
	isl29125PollStep = 0
	m := newISL29125Mock(0x7D)
	chip, err := NewISL29125Full(m)
	if err != nil {
		t.Fatal(err)
	}
	m.regs[0x08] = 0x02
	return m, chip
}

func TestISL29125Init(t *testing.T) {
	m, _ := newISL29125(t)
	dw := m.dataWrites()
	want := [][2]byte{{0x08, 0x00}, {0x03, 0x00}, {0x02, 0xBF}, {0x01, 0x0D}}
	if len(dw) != len(want) {
		t.Fatalf("init data writes = %v", dw)
	}
	for i, w := range want {
		if dw[i][0] != w[0] || dw[i][1] != w[1] {
			t.Errorf("init write %d = %v, want %v", i, dw[i], w)
		}
	}
}

func TestISL29125RejectsWrongID(t *testing.T) {
	m := newISL29125Mock(0x00)
	if _, err := NewISL29125Minimal(m); !errors.Is(err, ErrISL29125NotFound) {
		t.Errorf("err = %v", err)
	}
	if len(m.dataWrites()) != 0 {
		t.Errorf("wrote %v", m.dataWrites())
	}
}

func TestISL29125ReadDecodesChannels(t *testing.T) {
	m, chip := newISL29125(t)
	for i, v := range []uint8{0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00} {
		m.regs[0x09+uint8(i)] = v
	}
	c, err := chip.Read()
	if err != nil {
		t.Fatal(err)
	}
	if c.Green != 0x1234 || c.Red != 0xFFFF || c.Blue != 1 {
		t.Errorf("counts = %+v", c)
	}
	b, _ := chip.ReadRGB()
	if b != c {
		t.Errorf("ReadRGB = %+v", b)
	}
	r, _ := chip.ReadRed()
	g, _ := chip.ReadGreen()
	bl, _ := chip.ReadBlue()
	if r != 0xFFFF || g != 0x1234 || bl != 1 {
		t.Errorf("single reads = %d %d %d", r, g, bl)
	}
	if id, _ := chip.DeviceID(); id != 0x7D {
		t.Errorf("device id = 0x%02X", id)
	}
}

func TestISL29125Configuration(t *testing.T) {
	m, chip := newISL29125(t)
	m.regs[0x01] = 0x0D
	for mode := uint8(0); mode < 8; mode++ {
		if err := chip.SetMode(mode); err != nil || m.regs[0x01] != 0x08|mode {
			t.Errorf("mode %d: %v reg 0x%02X", mode, err, m.regs[0x01])
		}
	}
	steps := []struct {
		name string
		do   func() error
		want uint8
	}{
		{"range 375", func() error { return chip.SetRange(375) }, 0x07},
		{"range 10000", func() error { return chip.SetRange(10000) }, 0x0F},
		{"resolution 12", func() error { return chip.SetResolution(12) }, 0x1F},
		{"resolution 16", func() error { return chip.SetResolution(16) }, 0x0F},
		{"sync external", func() error { return chip.SetSync(true) }, 0x2F},
		{"sync i2c", func() error { return chip.SetSync(false) }, 0x0F},
	}
	for _, s := range steps {
		if err := s.do(); err != nil || m.regs[0x01] != s.want {
			t.Errorf("%s: %v reg 0x%02X, want 0x%02X", s.name, err, m.regs[0x01], s.want)
		}
	}
	if err := chip.SetIRCompensation(false, 0x3F); err != nil || m.regs[0x02] != 0x3F {
		t.Errorf("ir low: %v reg 0x%02X", err, m.regs[0x02])
	}
	if err := chip.SetIRCompensation(true, 0x3F); err != nil || m.regs[0x02] != 0xBF {
		t.Errorf("ir high: %v reg 0x%02X", err, m.regs[0x02])
	}
}

func TestISL29125InvalidArguments(t *testing.T) {
	m, chip := newISL29125(t)
	before := len(m.writes)
	errs := []error{
		chip.SetMode(8), chip.SetRange(100), chip.SetResolution(8), chip.SetIRCompensation(false, 64),
		chip.SetPersistence(3), chip.SetInterruptChannel(4), chip.SetThresholds(5, 4),
	}
	for i, err := range errs {
		if !errors.Is(err, ErrISL29125Range) {
			t.Errorf("call %d: err = %v", i, err)
		}
	}
	if len(m.writes) != before {
		t.Errorf("invalid arguments wrote %v", m.writes[before:])
	}
}

func TestISL29125Reset(t *testing.T) {
	m, chip := newISL29125(t)
	m.writes = nil
	if err := chip.SoftReset(); err != nil {
		t.Fatal(err)
	}
	dw := m.dataWrites()
	if len(dw) != 1 || dw[0][0] != 0x00 || dw[0][1] != 0x46 {
		t.Errorf("reset writes = %v", dw)
	}
}

func TestISL29125Thresholds(t *testing.T) {
	m, chip := newISL29125(t)
	if err := chip.SetThresholds(0x1234, 0xABCD); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x04] != 0x34 || m.regs[0x05] != 0x12 || m.regs[0x06] != 0xCD || m.regs[0x07] != 0xAB {
		t.Errorf("threshold bytes = %02X %02X %02X %02X", m.regs[0x04], m.regs[0x05], m.regs[0x06], m.regs[0x07])
	}
	lo, hi, err := chip.GetThresholds()
	if err != nil || lo != 0x1234 || hi != 0xABCD {
		t.Errorf("round trip = %d %d %v", lo, hi, err)
	}
}

func TestISL29125InterruptConfiguration(t *testing.T) {
	m, chip := newISL29125(t)
	steps := []struct {
		name string
		do   func() error
		want uint8
	}{
		{"channel red", func() error { return chip.SetInterruptChannel(ISL29125ChannelRed) }, 0x02},
		{"channel blue", func() error { return chip.SetInterruptChannel(ISL29125ChannelBlue) }, 0x03},
		{"channel none", func() error { return chip.SetInterruptChannel(ISL29125ChannelNone) }, 0x00},
		{"persistence 8", func() error { return chip.SetPersistence(8) }, 0x0C},
		{"persistence 4", func() error { return chip.SetPersistence(4) }, 0x08},
		{"channel green", func() error { return chip.SetInterruptChannel(ISL29125ChannelGreen) }, 0x09},
		{"enable conversion", func() error { return chip.EnableInterrupt(ISL29125SourceConversion) }, 0x19},
		{"disable conversion", func() error { return chip.DisableInterrupt(ISL29125SourceConversion) }, 0x09},
		{"disable threshold", func() error { return chip.DisableInterrupt(ISL29125SourceThreshold) }, 0x08},
		{"enable threshold defaults green", func() error { return chip.EnableInterrupt(ISL29125SourceThreshold) }, 0x09},
		{"channel red again", func() error { return chip.SetInterruptChannel(ISL29125ChannelRed) }, 0x0A},
		{"enable threshold keeps channel", func() error { return chip.EnableInterrupt(ISL29125SourceThreshold) }, 0x0A},
	}
	for _, s := range steps {
		if err := s.do(); err != nil || m.regs[0x03] != s.want {
			t.Errorf("%s: %v reg 0x%02X, want 0x%02X", s.name, err, m.regs[0x03], s.want)
		}
	}
}

func TestISL29125StatusLatching(t *testing.T) {
	m, chip := newISL29125(t)
	m.regs[0x08] = 0x03
	if ok, _ := chip.DataReady(); !ok {
		t.Error("DataReady should be true")
	}
	m.regs[0x08] = 0x00
	if ok, _ := chip.DataReady(); ok {
		t.Error("DataReady should be false")
	}
	if st, _ := chip.PollInterrupt(); st != 0x03 {
		t.Errorf("flags kept after DataReady: 0x%02X", st)
	}
	if st, _ := chip.PollInterrupt(); st != 0 {
		t.Errorf("latch not cleared: 0x%02X", st)
	}
	m.regs[0x08] = 0x04
	if st, _ := chip.Status(); st != 0x04 {
		t.Errorf("brown-out status = 0x%02X", st)
	}
}

func TestISL29125Callback(t *testing.T) {
	m, chip := newISL29125(t)
	var seen []uint8
	chip.mu.Lock()
	chip.callback = func(s uint8) { seen = append(seen, s) }
	chip.mu.Unlock()
	m.regs[0x08] = 0x01
	chip.handleEdge()
	m.regs[0x08] = 0x00
	chip.handleEdge()
	if len(seen) != 1 || seen[0] != 0x01 {
		t.Errorf("seen = %v", seen)
	}
	_ = chip.OffInterrupt()
}

func TestISL29125BusErrors(t *testing.T) {
	m, chip := newISL29125(t)
	m.failN = 1
	if _, err := chip.Read(); err == nil {
		t.Error("Read should fail on NACK")
	}
	m.failN = 1
	if err := chip.SetRange(375); err == nil {
		t.Error("SetRange should fail on NACK")
	}
}
