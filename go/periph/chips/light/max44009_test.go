package light

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// max44009Mock is a register-map mock of the MAX44009 with NACK injection.
type max44009Mock struct {
	regs      map[uint8]uint8
	writes    [][]byte
	failN     int // number of upcoming bus transactions that NACK
	failAfter int // transactions to let through before failing
}

func newMAX44009Mock(high, low uint8) *max44009Mock {
	return &max44009Mock{regs: map[uint8]uint8{0x02: 0x03, 0x03: high, 0x04: low}}
}

func (m *max44009Mock) nack() bool {
	if m.failAfter > 0 {
		m.failAfter--
		return false
	}
	if m.failN > 0 {
		m.failN--
		return true
	}
	return false
}

func (m *max44009Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if m.nack() {
		return nil, errors.New("nack")
	}
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[uint8(reg)+uint8(i)]
	}
	return out, nil
}

func (m *max44009Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if m.nack() {
		return errors.New("nack")
	}
	for i, v := range data {
		m.regs[uint8(reg)+uint8(i)] = v
	}
	return nil
}

func (m *max44009Mock) Write(data []byte) error                   { return nil }
func (m *max44009Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *max44009Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *max44009Mock) Close() error                              { return nil }
func (m *max44009Mock) Enable()                                   {}
func (m *max44009Mock) Disable()                                  {}
func (m *max44009Mock) IsEnabled() bool                           { return true }
func (m *max44009Mock) IntPin() connection.InputPin               { return nil }
func (m *max44009Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*max44009Mock)(nil)

func (m *max44009Mock) dataWrites() [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			out = append(out, w)
		}
	}
	return out
}

func max44009Near(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-3 }

func newMAX44009(t *testing.T, high, low uint8) (*max44009Mock, *MAX44009Full) {
	t.Helper()
	max44009InitWait = 0 // skip the 900 ms first-conversion wait
	m := newMAX44009Mock(high, low)
	chip, err := NewMAX44009Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return m, chip
}

func TestMAX44009Init(t *testing.T) {
	m, _ := newMAX44009(t, 0, 0)
	dw := m.dataWrites()
	want := [][2]byte{{0x01, 0x00}, {0x02, 0x03}, {0x05, 0xFF}, {0x06, 0x00}, {0x07, 0xFF}}
	if len(dw) != len(want) {
		t.Fatalf("init data writes = %v", dw)
	}
	for i, w := range want {
		if dw[i][0] != w[0] || dw[i][1] != w[1] {
			t.Errorf("init write %d = %v, want %v", i, dw[i], w)
		}
	}
	readStatus := false
	for _, w := range m.writes {
		if len(w) == 1 && w[0] == 0x00 {
			readStatus = true
		}
	}
	if !readStatus {
		t.Error("init must read INT_STATUS once")
	}
}

func TestMAX44009Lux(t *testing.T) {
	m, chip := newMAX44009(t, 0, 0)
	if v, _ := chip.Lux(); v != 0 {
		t.Errorf("zero = %v", v)
	}
	m.regs[3], m.regs[4] = 0x2A, 0x05
	if v, _ := chip.Lux(); !max44009Near(v, 4*0xA5*0.045) {
		t.Errorf("E=2 M=0xA5 -> %v", v)
	}
	m.regs[3], m.regs[4] = 0xEF, 0x0F
	if v, _ := chip.Lux(); !max44009Near(v, 188006.4) {
		t.Errorf("max valid -> %v", v)
	}
	m.regs[3] = 0xF3
	if v, _ := chip.Lux(); !max44009Near(v, 188006.4) {
		t.Errorf("overrange -> %v", v)
	}
	m.regs[3], m.regs[4] = 0x01, 0xF7
	if v, _ := chip.Lux(); !max44009Near(v, 0x17*0.045) {
		t.Errorf("unused low bits -> %v", v)
	}
}

func TestMAX44009RawResolution(t *testing.T) {
	m, chip := newMAX44009(t, 0x5B, 0x0C)
	e, mant, over, err := chip.ReadRaw()
	if err != nil || e != 5 || mant != 0xBC || over {
		t.Errorf("ReadRaw = %d %#x %v %v", e, mant, over, err)
	}
	if !max44009Near(chip.Resolution(), 32*0.045) || chip.IsOverrange() {
		t.Errorf("resolution %v overrange %v", chip.Resolution(), chip.IsOverrange())
	}
	m.regs[3] = 0xF0
	if _, _, over, _ := chip.ReadRaw(); !over || !chip.IsOverrange() {
		t.Error("overrange not reported")
	}
}

// max44009FlipMock changes LUX_HIGH between the first and second read.
type max44009FlipMock struct {
	*max44009Mock
	highReads int
}

func (m *max44009FlipMock) ReadReg(reg uint32, length int) ([]byte, error) {
	if reg == 0x03 {
		m.highReads++
		if m.highReads == 2 {
			m.regs[0x03], m.regs[0x04] = 0x20, 0x02
		}
	}
	return m.max44009Mock.ReadReg(reg, length)
}

func TestMAX44009ConsistentReadRepeats(t *testing.T) {
	max44009InitWait = 0
	flip := &max44009FlipMock{max44009Mock: newMAX44009Mock(0x10, 0x01)}
	chip, _ := NewMAX44009Minimal(flip)
	flip.highReads = 0
	v, _ := chip.Lux()
	if !max44009Near(v, 4*0x02*0.045) || flip.highReads != 2 {
		t.Errorf("lux = %v after %d high reads", v, flip.highReads)
	}
}

func TestMAX44009Configuration(t *testing.T) {
	m, chip := newMAX44009(t, 0, 0)
	chip.SetContinuous(true)
	if m.regs[2] != 0x83 {
		t.Errorf("continuous: %#x", m.regs[2])
	}
	chip.SetContinuous(false)
	if m.regs[2] != 0x03 {
		t.Errorf("continuous off: %#x", m.regs[2])
	}
	chip.SetManual(50, false)
	if m.regs[2] != 0x44 {
		t.Errorf("manual 50 ms: %#x", m.regs[2])
	}
	chip.SetManual(12, true)
	if m.regs[2] != 0x40|0x08|0x06 {
		t.Errorf("manual nearest 12.5 ms divide: %#x", m.regs[2])
	}
	chip.SetContinuous(true)
	chip.SetManual(1000, false)
	if m.regs[2] != 0xC0 {
		t.Errorf("manual keeps continuous: %#x", m.regs[2])
	}
	chip.SetAutomatic()
	if m.regs[2]&0x40 != 0 || m.regs[2]&0x80 == 0 {
		t.Errorf("automatic: %#x", m.regs[2])
	}
	m.regs[2] = 0xCD
	cfg, _ := chip.GetConfig()
	if !cfg.Continuous || !cfg.Manual || !cfg.DivideBy8 || cfg.IntegrationMs != 25 {
		t.Errorf("GetConfig = %+v", cfg)
	}
}

func TestMAX44009Thresholds(t *testing.T) {
	m, chip := newMAX44009(t, 0, 0)
	chip.SetThresholds(0, 188006.4)
	if m.regs[6] != 0x00 || m.regs[5] != 0xEF {
		t.Errorf("extremes: %#x %#x", m.regs[6], m.regs[5])
	}
	chip.SetThresholds(5, 100)
	if m.regs[6] != 0x07 || m.regs[5]>>4 != 4 || m.regs[5]&0x08 == 0 {
		t.Errorf("5/100 lux: %#x %#x", m.regs[6], m.regs[5])
	}
	lo, hi, _ := chip.GetThresholds()
	if math.Abs(float64(lo-5)) > 0.5 || math.Abs(float64(hi-100))/100 > 0.13 {
		t.Errorf("roundtrip: %v %v", lo, hi)
	}
	if !max44009Near(max44009DecodeThreshold(0x25, true), 4*0x5F*0.045) || !max44009Near(max44009DecodeThreshold(0x25, false), 4*0x50*0.045) {
		t.Error("implied nibble decode")
	}
	n := len(m.writes)
	if err := chip.SetThresholds(10, 5); !errors.Is(err, ErrMAX44009Thresholds) || len(m.writes) != n {
		t.Errorf("order check: %v, %d new writes", err, len(m.writes)-n)
	}
}

func TestMAX44009Delay(t *testing.T) {
	m, chip := newMAX44009(t, 0, 0)
	chip.SetThresholdDelay(2.0)
	if v, _ := chip.GetThresholdDelay(); m.regs[7] != 20 || !max44009Near(v, 2.0) {
		t.Errorf("delay: %d %v", m.regs[7], v)
	}
	chip.SetThresholdDelay(99)
	if m.regs[7] != 255 {
		t.Errorf("clamp high: %d", m.regs[7])
	}
	chip.SetThresholdDelay(-1)
	if m.regs[7] != 0 {
		t.Errorf("clamp low: %d", m.regs[7])
	}
}

func TestMAX44009Interrupt(t *testing.T) {
	m, chip := newMAX44009(t, 0, 0)
	chip.EnableInterrupt()
	if m.regs[1] != 0x01 {
		t.Errorf("enable: %d", m.regs[1])
	}
	chip.DisableInterrupt()
	if m.regs[1] != 0x00 {
		t.Errorf("disable: %d", m.regs[1])
	}
	m.regs[0] = 0x01
	if v, _ := chip.PollInterrupt(); v != 1 {
		t.Errorf("poll = %d", v)
	}
	m.regs[0] = 0x00
	if v, _ := chip.PollInterrupt(); v != 0 {
		t.Errorf("poll clear = %d", v)
	}

	var got []uint8
	chip.callback = func(s uint8) { got = append(got, s) }
	m.regs[0] = 0x01
	chip.handleEdge()
	m.regs[0] = 0x00
	chip.handleEdge()
	if len(got) != 1 || got[0] != 1 {
		t.Errorf("callback calls = %v", got)
	}
	chip.OffInterrupt()
	if chip.callback != nil {
		t.Error("OffInterrupt must clear the callback")
	}
}
