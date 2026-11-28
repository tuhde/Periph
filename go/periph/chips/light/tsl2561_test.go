package light

import (
	"bytes"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// tsl2561Mock is a register-map mock keyed by the command byte, with an
// optional light model (counts follow the TIMING register) and an INT pin.
type tsl2561Mock struct {
	regs   map[uint8]uint8
	writes [][]byte
	base   float64 // > 0: counts at 1x / 402 ms (ch1 = 0.2 * ch0)
	pin    connection.InputPin
}

func newTSL2561Mock() *tsl2561Mock {
	return &tsl2561Mock{regs: map[uint8]uint8{0x8A: 0x15}}
}

func (m *tsl2561Mock) setCounts(ch0, ch1 uint16) {
	m.regs[0x9C], m.regs[0x9D] = byte(ch0), byte(ch0>>8)
	m.regs[0x9E], m.regs[0x9F] = byte(ch1), byte(ch1>>8)
}

func (m *tsl2561Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if byte(reg) == 0x9C && m.base > 0 {
		t := m.regs[0x81]
		gain := 1.0
		if t&0x10 != 0 {
			gain = 16
		}
		scale := [3]float64{0.034, 0.252, 1.0}[t&0x03]
		limit := [3]float64{5047, 37177, 65535}[t&0x03]
		m.setCounts(uint16(math.Min(limit, math.Floor(m.base*gain*scale))), uint16(math.Min(limit, math.Floor(m.base*0.2*gain*scale))))
	}
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[uint8(reg)+uint8(i)]
	}
	return out, nil
}

func (m *tsl2561Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	for i, v := range data {
		m.regs[uint8(reg)+uint8(i)] = v
	}
	return nil
}

func (m *tsl2561Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}
func (m *tsl2561Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *tsl2561Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *tsl2561Mock) Close() error                              { return nil }
func (m *tsl2561Mock) Enable()                                   {}
func (m *tsl2561Mock) Disable()                                  {}
func (m *tsl2561Mock) IsEnabled() bool                           { return true }
func (m *tsl2561Mock) IntPin() connection.InputPin               { return m.pin }
func (m *tsl2561Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*tsl2561Mock)(nil)

func (m *tsl2561Mock) wrote(b ...byte) bool {
	for _, w := range m.writes {
		if bytes.Equal(w, b) {
			return true
		}
	}
	return false
}

type tsl2561FakePin struct{ handler func() }

func (p *tsl2561FakePin) OnEdge(t connection.Trigger, h func()) func() {
	p.handler = h
	return func() { p.handler = nil }
}
func (p *tsl2561FakePin) IsHigh() (bool, error) { return true, nil }

func tsl2561Ref(ch0, ch1, gain, scale float64, k [4][3]float64) float64 {
	c0 := ch0 * (16 / gain) / scale
	c1 := ch1 * (16 / gain) / scale
	r := c1 / c0
	if r <= k[0][0] {
		return k[0][1]*c0 - k[0][2]*c0*math.Pow(r, 1.4)
	}
	for _, s := range k[1:] {
		if r <= s[0] {
			return s[1]*c0 - s[2]*c1
		}
	}
	return 0
}

func tsl2561Near(a float32, b float64) bool {
	return math.Abs(float64(a)-b) <= 1e-4*math.Max(1, math.Abs(b))
}

func newTSL2561Test(t *testing.T, m *tsl2561Mock) *TSL2561Full {
	t.Helper()
	tsl2561WaitScale = 0
	chip, err := NewTSL2561Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return chip
}

func TestTSL2561Init(t *testing.T) {
	m := newTSL2561Mock()
	newTSL2561Test(t, m)
	var data [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			data = append(data, w)
		}
	}
	if len(data) != 2 || !bytes.Equal(data[0], []byte{0x80, 0x03}) || !bytes.Equal(data[1], []byte{0x81, 0x02}) {
		t.Fatalf("init writes %x", data)
	}
	if !m.wrote(0x80) {
		t.Fatal("CONTROL not read back")
	}
}

type tsl2561DeadMock struct{ *tsl2561Mock }

func (m tsl2561DeadMock) WriteReg(reg uint32, data []byte) error {
	_ = m.tsl2561Mock.WriteReg(reg, data)
	m.regs[0x80] = 0
	return nil
}

func TestTSL2561InitRejectsBadControl(t *testing.T) {
	tsl2561WaitScale = 0
	if _, err := NewTSL2561Minimal(tsl2561DeadMock{newTSL2561Mock()}); !errors.Is(err, ErrTSL2561Init) {
		t.Fatalf("got %v", err)
	}
}

func TestTSL2561LuxFormula(t *testing.T) {
	m := newTSL2561Mock()
	chip := newTSL2561Test(t, m)
	m.setCounts(1000, 200)
	lx, _ := chip.ReadLux()
	if !tsl2561Near(lx, tsl2561Ref(1000, 200, 1, 1, tsl2561CoeffTFNCL)) || math.Abs(float64(lx)-382.2) > 0.5 {
		t.Fatalf("segment1 %v", lx)
	}
	for _, c1 := range []uint16{550, 700, 1000} {
		m.setCounts(1000, c1)
		if lx, _ := chip.ReadLux(); !tsl2561Near(lx, tsl2561Ref(1000, float64(c1), 1, 1, tsl2561CoeffTFNCL)) {
			t.Fatalf("ch1 %d: %v", c1, lx)
		}
	}
	m.setCounts(1000, 1400)
	if lx, _ := chip.ReadLux(); lx != 0 {
		t.Fatal("ratio above 1.30")
	}
	m.setCounts(0, 0)
	if lx, _ := chip.ReadLux(); lx != 0 {
		t.Fatal("dark")
	}
	if !tsl2561Near(TSL2561CalculateLux(500, 100, 16, 101, "T"), tsl2561Ref(500, 100, 16, 0.252, tsl2561CoeffTFNCL)) {
		t.Fatal("16x 101 ms")
	}
	if !tsl2561Near(TSL2561CalculateLux(500, 300, 1, 14, "FN"), tsl2561Ref(500, 300, 1, 0.034, tsl2561CoeffTFNCL)) {
		t.Fatal("14 ms")
	}
	for _, c1 := range []uint16{100, 600, 700, 1000} {
		if !tsl2561Near(TSL2561CalculateLux(1000, c1, 1, 402, "CS"), tsl2561Ref(1000, float64(c1), 1, 1, tsl2561CoeffCS)) {
			t.Fatalf("CS %d", c1)
		}
	}
}

func TestTSL2561RawAndSaturation(t *testing.T) {
	m := newTSL2561Mock()
	chip := newTSL2561Test(t, m)
	m.setCounts(0x1234, 0x0567)
	if c0, c1, _ := chip.ReadRaw(); c0 != 0x1234 || c1 != 0x0567 || !bytes.Equal(m.writes[len(m.writes)-1], []byte{0x9C}) {
		t.Fatal("read raw")
	}
	if lx, c0, _, _ := chip.ReadLuxRaw(); c0 != 0x1234 || !tsl2561Near(lx, tsl2561Ref(0x1234, 0x0567, 1, 1, tsl2561CoeffTFNCL)) {
		t.Fatal("read lux raw")
	}
	m.setCounts(65535, 100)
	var sat *TSL2561SaturatedError
	if _, err := chip.ReadLux(); !errors.As(err, &sat) || sat.Ch0 != 65535 {
		t.Fatalf("saturation %v", err)
	}
	_ = chip.SetIntegrationTime(101)
	m.setCounts(37177, 10)
	if _, err := chip.ReadLux(); !errors.As(err, &sat) {
		t.Fatal("101 ms saturation")
	}
	m.setCounts(37176, 10)
	if lx, err := chip.ReadLux(); err != nil || lx <= 0 {
		t.Fatal("below limit")
	}
}

func TestTSL2561Configuration(t *testing.T) {
	m := newTSL2561Mock()
	chip := newTSL2561Test(t, m)
	_ = chip.SetGain(16)
	if m.regs[0x81] != 0x12 || chip.Gain() != 16 {
		t.Fatal("gain 16")
	}
	_ = chip.SetIntegrationTime(14)
	if m.regs[0x81] != 0x10 || chip.IntegrationTime() != 14 {
		t.Fatal("14 ms")
	}
	_ = chip.SetGain(1)
	_ = chip.SetIntegrationTime(101)
	if m.regs[0x81] != 0x01 || chip.IntegrationTime() != 101 {
		t.Fatal("101 ms")
	}
	if chip.SetGain(4) != ErrTSL2561Argument || chip.SetIntegrationTime(50) != ErrTSL2561Argument || chip.SetPackage("X") != ErrTSL2561Argument {
		t.Fatal("argument checks")
	}
	_ = chip.SetIntegrationTime(402)
	m.setCounts(1000, 100)
	_ = chip.SetPackage("CS")
	if lx, _ := chip.ReadLux(); !tsl2561Near(lx, tsl2561Ref(1000, 100, 1, 1, tsl2561CoeffCS)) {
		t.Fatal("package CS")
	}
	_ = chip.SetGain(16)
	_ = chip.StartManual()
	if m.regs[0x81] != 0x1B {
		t.Fatal("start manual")
	}
	_ = chip.StopManual()
	if m.regs[0x81] != 0x13 || chip.IntegrationTime() != 0 {
		t.Fatal("stop manual")
	}
}

func TestTSL2561AutoRange(t *testing.T) {
	for _, c := range []struct {
		base  float64
		gain  uint8
		integ uint16
	}{{20, 16, 402}, {148000, 1, 14}, {5000, 1, 402}} {
		m := newTSL2561Mock()
		m.base = c.base
		chip := newTSL2561Test(t, m)
		lx, err := chip.AutoRange()
		if err != nil || chip.Gain() != c.gain || chip.IntegrationTime() != c.integ || lx <= 0 {
			t.Fatalf("base %v: %v gain %d integ %d err %v", c.base, lx, chip.Gain(), chip.IntegrationTime(), err)
		}
	}
}

func TestTSL2561PowerThresholdsInterrupt(t *testing.T) {
	m := newTSL2561Mock()
	chip := newTSL2561Test(t, m)
	if on, _ := chip.IsPowered(); !on {
		t.Fatal("is powered")
	}
	_ = chip.PowerDown()
	if on, _ := chip.IsPowered(); on || m.regs[0x80] != 0 {
		t.Fatal("power down")
	}
	_ = chip.PowerUp()
	if m.regs[0x80] != 0x03 {
		t.Fatal("power up")
	}
	_ = chip.SetThresholds(0x0102, 0x0A0B)
	if !m.wrote(0xA2, 0x02, 0x01) || !m.wrote(0xA4, 0x0B, 0x0A) {
		t.Fatal("threshold word writes")
	}
	if lo, hi, _ := chip.Thresholds(); lo != 0x0102 || hi != 0x0A0B {
		t.Fatal("thresholds")
	}
	_ = chip.ConfigureInterrupt(1, 2)
	if m.regs[0x86] != 0x12 || chip.ConfigureInterrupt(3, 0) != ErrTSL2561Argument || chip.ConfigureInterrupt(1, 16) != ErrTSL2561Argument {
		t.Fatal("configure interrupt")
	}
	if id, _ := chip.ReadID(); id>>4 != 1 {
		t.Fatal("id")
	}
	_ = chip.SetThresholds(100, 1000)
	for _, c := range []struct {
		ch0  uint16
		want bool
	}{{500, false}, {100, true}, {1001, true}} {
		m.setCounts(c.ch0, 0)
		if got, _ := chip.PollInterrupt(); got != c.want {
			t.Fatalf("poll ch0 %d", c.ch0)
		}
	}
	_ = chip.ClearInterrupt()
	if !bytes.Equal(m.writes[len(m.writes)-1], []byte{0xC0}) {
		t.Fatal("clear interrupt")
	}

	pin := &tsl2561FakePin{}
	m.pin = pin
	var seen []uint16
	_ = chip.OnInterrupt(func(ch0 uint16) { seen = append(seen, ch0) })
	m.setCounts(4242, 0)
	pin.handler()
	if len(seen) != 1 || seen[0] != 4242 || !bytes.Equal(m.writes[len(m.writes)-1], []byte{0xC0}) {
		t.Fatalf("on interrupt %v", seen)
	}
	_ = chip.OffInterrupt()
	if pin.handler != nil {
		t.Fatal("off interrupt")
	}
}
