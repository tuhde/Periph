package light

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// apds9250Mock is a register-map mock of the APDS-9250 with NACK injection.
type apds9250Mock struct {
	regs   map[uint8]uint8
	writes [][]byte
	failN  int
}

func newAPDS9250Mock(part uint8) *apds9250Mock {
	return &apds9250Mock{regs: map[uint8]uint8{0x06: part, 0x07: 0x08, 0x04: 0x22, 0x05: 0x01, 0x19: 0x10}}
}

func (m *apds9250Mock) ReadReg(reg uint32, length int) ([]byte, error) {
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

func (m *apds9250Mock) WriteReg(reg uint32, data []byte) error {
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

func (m *apds9250Mock) Write(data []byte) error                   { return nil }
func (m *apds9250Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *apds9250Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *apds9250Mock) Close() error                              { return nil }
func (m *apds9250Mock) Enable()                                   {}
func (m *apds9250Mock) Disable()                                  {}
func (m *apds9250Mock) IsEnabled() bool                           { return true }
func (m *apds9250Mock) IntPin() connection.InputPin               { return nil }
func (m *apds9250Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*apds9250Mock)(nil)

func (m *apds9250Mock) dataWrites() [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			out = append(out, w)
		}
	}
	return out
}

func newAPDS9250(t *testing.T) (*apds9250Mock, *APDS9250Full) {
	t.Helper()
	apds9250StartWait = 0
	apds9250PollStep = 0
	m := newAPDS9250Mock(0xB5)
	chip, err := NewAPDS9250Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return m, chip
}

func TestAPDS9250Init(t *testing.T) {
	m, _ := newAPDS9250(t)
	dw := m.dataWrites()
	want := [][2]byte{{0x04, 0x22}, {0x05, 0x01}, {0x19, 0x10}, {0x00, 0x06}}
	if len(dw) != len(want) {
		t.Fatalf("init data writes = %v", dw)
	}
	for i, w := range want {
		if dw[i][0] != w[0] || dw[i][1] != w[1] {
			t.Errorf("init write %d = %v, want %v", i, dw[i], w)
		}
	}
}

func TestAPDS9250RejectsWrongPart(t *testing.T) {
	apds9250StartWait = 0
	for _, part := range []uint8{0x00, 0xC5} {
		m := newAPDS9250Mock(part)
		if _, err := NewAPDS9250Minimal(m); !errors.Is(err, ErrAPDS9250NotFound) {
			t.Errorf("part 0x%02X: err = %v", part, err)
		}
		if len(m.dataWrites()) != 0 {
			t.Errorf("part 0x%02X: wrote %v", part, m.dataWrites())
		}
	}
}

func TestAPDS9250ReadDecodesChannels(t *testing.T) {
	m, chip := newAPDS9250(t)
	for i, v := range []uint8{0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4} {
		m.regs[0x0A+uint8(i)] = v
	}
	c, err := chip.Read()
	if err != nil {
		t.Fatal(err)
	}
	if c.IR != 0x030201 || c.Green != 16 || c.Blue != 0xFFFFF || c.Red != 0x40000 {
		t.Errorf("counts = %+v", c)
	}
	b, _ := chip.ReadChannels()
	if b != c {
		t.Errorf("ReadChannels = %+v", b)
	}
	r, _ := chip.ReadRed()
	g, _ := chip.ReadGreen()
	bl, _ := chip.ReadBlue()
	ir, _ := chip.ReadIR()
	if r != 0x40000 || g != 16 || bl != 0xFFFFF || ir != 0x030201 {
		t.Errorf("single reads = %d %d %d %d", r, g, bl, ir)
	}
}

func TestAPDS9250Configuration(t *testing.T) {
	m, chip := newAPDS9250(t)
	if err := chip.SetResolution(20); err != nil || m.regs[0x04] != 0x02 {
		t.Errorf("resolution 20: %v reg 0x%02X", err, m.regs[0x04])
	}
	if err := chip.SetResolution(13); err != nil || m.regs[0x04] != 0x52 {
		t.Errorf("resolution 13: %v reg 0x%02X", err, m.regs[0x04])
	}
	if v, _ := chip.IntegrationTime(); math.Abs(float64(v)-0.003125) > 1e-6 {
		t.Errorf("integration = %v", v)
	}
	_ = chip.SetResolution(16)
	if err := chip.SetMeasurementRate(2000); err != nil || m.regs[0x04] != 0x46 {
		t.Errorf("rate 2000: %v reg 0x%02X", err, m.regs[0x04])
	}
	if err := chip.SetMeasurementRate(25); err != nil || m.regs[0x04] != 0x40 {
		t.Errorf("rate 25: %v reg 0x%02X", err, m.regs[0x04])
	}
	for code, gain := range []uint8{1, 3, 6, 9, 18} {
		if err := chip.SetGain(gain); err != nil || m.regs[0x05] != uint8(code) {
			t.Errorf("gain %d: %v reg %d", gain, err, m.regs[0x05])
		}
		if got, _ := chip.GetGain(); got != gain {
			t.Errorf("GetGain = %d, want %d", got, gain)
		}
	}
	_ = chip.SetResolution(18)
	_ = chip.SetGain(3)
	if n, _ := chip.Normalized(3000); math.Abs(float64(n)-10000) > 1 {
		t.Errorf("normalized = %v", n)
	}
}

func TestAPDS9250InvalidArguments(t *testing.T) {
	_, chip := newAPDS9250(t)
	checks := map[string]error{
		"gain":        chip.SetGain(2),
		"resolution":  chip.SetResolution(12),
		"rate":        chip.SetMeasurementRate(30),
		"persistence": chip.SetPersistence(0),
		"persist17":   chip.SetPersistence(17),
		"variance":    chip.SetVariance(12),
		"channel":     chip.SetInterruptChannel(4),
		"thresholds":  chip.SetThresholds(5, 4),
		"threshold20": chip.SetThresholds(0, 0x100000),
	}
	for name, err := range checks {
		if !errors.Is(err, ErrAPDS9250Range) {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}

func TestAPDS9250ModeAndReset(t *testing.T) {
	m, chip := newAPDS9250(t)
	m.regs[0x00] = 0x06
	_ = chip.SetMode(false)
	if m.regs[0x00] != 0x02 {
		t.Errorf("ALS mode reg 0x%02X", m.regs[0x00])
	}
	_ = chip.SetMode(true)
	_ = chip.Disable()
	if m.regs[0x00] != 0x04 {
		t.Errorf("disable reg 0x%02X", m.regs[0x00])
	}
	_ = chip.Enable()
	if m.regs[0x00] != 0x06 {
		t.Errorf("enable reg 0x%02X", m.regs[0x00])
	}
	_ = chip.SoftReset()
	last := m.writes[len(m.writes)-1]
	if len(last) != 2 || last[0] != 0x00 || last[1] != 0x10 {
		t.Errorf("reset write = %v", last)
	}
	if p, r, _ := chip.PartID(); p != 0xB || r != 0x5 {
		t.Errorf("PartID = %x %x", p, r)
	}
}

func TestAPDS9250Thresholds(t *testing.T) {
	m, chip := newAPDS9250(t)
	if err := chip.SetThresholds(0x12345, 0xABCDE); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x24] != 0x45 || m.regs[0x25] != 0x23 || m.regs[0x26] != 0x01 {
		t.Errorf("low bytes = %x %x %x", m.regs[0x24], m.regs[0x25], m.regs[0x26])
	}
	if m.regs[0x21] != 0xDE || m.regs[0x22] != 0xBC || m.regs[0x23] != 0x0A {
		t.Errorf("high bytes = %x %x %x", m.regs[0x21], m.regs[0x22], m.regs[0x23])
	}
	lo, hi, _ := chip.GetThresholds()
	if lo != 0x12345 || hi != 0xABCDE {
		t.Errorf("thresholds = %x %x", lo, hi)
	}
}

func TestAPDS9250InterruptConfiguration(t *testing.T) {
	m, chip := newAPDS9250(t)
	_ = chip.SetInterruptChannel(APDS9250ChannelRed)
	if m.regs[0x19] != 0x20 {
		t.Errorf("red reg 0x%02X", m.regs[0x19])
	}
	_ = chip.SetInterruptChannel(APDS9250ChannelBlue)
	_ = chip.EnableInterrupt(APDS9250SourceVariation)
	if m.regs[0x19] != 0x3C {
		t.Errorf("variation reg 0x%02X", m.regs[0x19])
	}
	_ = chip.EnableInterrupt(APDS9250SourceThreshold)
	if m.regs[0x19] != 0x34 {
		t.Errorf("threshold reg 0x%02X", m.regs[0x19])
	}
	_ = chip.DisableInterrupt()
	if m.regs[0x19] != 0x30 {
		t.Errorf("disable reg 0x%02X", m.regs[0x19])
	}
	_ = chip.SetPersistence(16)
	_ = chip.SetVariance(1024)
	if m.regs[0x1A] != 0xF0 || m.regs[0x27] != 7 {
		t.Errorf("persistence/variance regs 0x%02X 0x%02X", m.regs[0x1A], m.regs[0x27])
	}
}

func TestAPDS9250StatusLatching(t *testing.T) {
	m, chip := newAPDS9250(t)
	m.regs[0x07] = 0x18
	if ok, _ := chip.DataReady(); !ok {
		t.Error("DataReady false")
	}
	m.regs[0x07] = 0x00
	if ok, _ := chip.DataReady(); ok {
		t.Error("DataReady true")
	}
	if s, _ := chip.PollInterrupt(); s != 0x10 {
		t.Errorf("PollInterrupt = 0x%02X", s)
	}
	if s, _ := chip.PollInterrupt(); s != 0 {
		t.Errorf("second PollInterrupt = 0x%02X", s)
	}
	m.regs[0x07] = 0x20
	if s, _ := chip.Status(); s != 0x20 {
		t.Errorf("Status = 0x%02X", s)
	}
}

func TestAPDS9250Callback(t *testing.T) {
	m, chip := newAPDS9250(t)
	var seen []uint8
	chip.mu.Lock()
	chip.callback = func(s uint8) { seen = append(seen, s) }
	chip.mu.Unlock()
	m.regs[0x07] = 0x10
	chip.handleEdge()
	m.regs[0x07] = 0x00
	chip.handleEdge()
	if len(seen) != 1 || seen[0] != 0x10 {
		t.Errorf("seen = %v", seen)
	}
	_ = chip.OffInterrupt()
}

func TestAPDS9250BusErrors(t *testing.T) {
	m, chip := newAPDS9250(t)
	m.failN = 1
	if _, err := chip.Read(); err == nil {
		t.Error("Read should fail on NACK")
	}
	m.failN = 1
	if err := chip.SetGain(3); err == nil {
		t.Error("SetGain should fail on NACK")
	}
}
