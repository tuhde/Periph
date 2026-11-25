package accelerometer

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// fxosMock is an in-memory fake connection.RegisterConnection — no hardware, no bus.
type fxosMock struct {
	registers map[byte]byte
	writes    [][]byte
	reads     []byte
	// failWrites makes the next n writes (after skip successful ones) fail with an error.
	failWrites int
	failSkip   int
	failReads  int
	// hook lets a test adjust a read result (reg, data).
	hook func(m *fxosMock, reg uint32, data []byte) []byte
}

func newFxosMock() *fxosMock {
	m := &fxosMock{registers: map[byte]byte{}}
	m.registers[0x0D] = 0xC7
	return m
}

func (m *fxosMock) set(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *fxosMock) maybeFail() error {
	if m.failSkip > 0 {
		m.failSkip--
		return nil
	}
	if m.failWrites > 0 {
		m.failWrites--
		return errors.New("NACK (mock)")
	}
	return nil
}

func (m *fxosMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if err := m.maybeFail(); err != nil {
		return err
	}
	for i := 1; i < len(data); i++ {
		m.registers[data[0]+byte(i-1)] = data[i]
	}
	return nil
}

func (m *fxosMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *fxosMock) WriteRead(data []byte, n int) ([]byte, error) { return m.ReadReg(uint32(data[0]), n) }

func (m *fxosMock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if err := m.maybeFail(); err != nil {
		return nil, err
	}
	m.reads = append(m.reads, byte(reg))
	out := make([]byte, length)
	for i := range out {
		out[i] = m.registers[byte(reg)+byte(i)]
	}
	if m.hook != nil {
		out = m.hook(m, reg, out)
	}
	return out, nil
}

func (m *fxosMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *fxosMock) Close() error                { return nil }
func (m *fxosMock) Enable()                     {}
func (m *fxosMock) Disable()                    {}
func (m *fxosMock) IsEnabled() bool             { return true }
func (m *fxosMock) IntPin() connection.InputPin { return nil }
func (m *fxosMock) EnPin() connection.OutputPin { return nil }

// last returns the data bytes of the last write to reg, or nil.
func (m *fxosMock) last(reg byte) []byte {
	var out []byte
	for _, w := range m.writes {
		if len(w) >= 2 && w[0] == reg {
			out = w[1:]
		}
	}
	return out
}

func (m *fxosMock) lastByte(reg byte) int {
	v := m.last(reg)
	if v == nil {
		return -1
	}
	return int(v[0])
}

func (m *fxosMock) wasRead(reg byte) bool {
	for _, r := range m.reads {
		if r == reg {
			return true
		}
	}
	return false
}

func newFxosFull(t *testing.T, m *fxosMock) *FXOS8700Full {
	t.Helper()
	c, err := NewFXOS8700Full(m)
	if err != nil {
		t.Fatalf("NewFXOS8700Full: %v", err)
	}
	return c
}

func closeTo(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-4 }

func TestFXOS8700InitSequence(t *testing.T) {
	m := newFxosMock()
	newFxosFull(t, m)
	var seq [][2]byte
	for _, w := range m.writes {
		if len(w) == 2 {
			seq = append(seq, [2]byte{w[0], w[1]})
		}
	}
	want := [][2]byte{{0x2B, 0x40}, {0x2A, 0x00}, {0x5B, 0x1F}, {0x5C, 0x20}, {0x0E, 0x00}, {0x2B, 0x00}, {0x2A, 0x11}}
	if len(seq) != len(want) {
		t.Fatalf("init writes = %v, want %v", seq, want)
	}
	for i := range want {
		if seq[i] != want[i] {
			t.Fatalf("init write %d = %v, want %v", i, seq[i], want[i])
		}
	}
}

func TestFXOS8700WhoAmI(t *testing.T) {
	m := newFxosMock()
	m.set(0x0D, 0xC4)
	if _, err := NewFXOS8700Minimal(m); err != nil {
		t.Errorf("pre-production WHO_AM_I rejected: %v", err)
	}
	m = newFxosMock()
	m.set(0x0D, 0x1A)
	if _, err := NewFXOS8700Minimal(m); err == nil {
		t.Error("wrong WHO_AM_I accepted")
	}
	if m.last(0x2A) != nil {
		t.Error("registers written after a wrong WHO_AM_I")
	}
}

func TestFXOS8700ToleratesResetNack(t *testing.T) {
	m := newFxosMock()
	m.failSkip = 1 // the first WHO_AM_I read
	m.failWrites = 1
	if _, err := NewFXOS8700Minimal(m); err != nil {
		t.Errorf("NACK on the reset write not tolerated: %v", err)
	}
}

func TestFXOS8700Measurements(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	m.set(0x01, 0x40, 0x00, 0xC0, 0x00, 0x00, 0x04)
	m.set(0x07, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
	m.set(0x33, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
	x, y, z, _ := c.ReadAccel()
	if !closeTo(x, 1) || !closeTo(y, -1) || !closeTo(z, 1.0/4096) {
		t.Errorf("accel = %v %v %v", x, y, z)
	}
	rx, ry, rz, _ := c.ReadRawAccel()
	if rx != 4096 || ry != -4096 || rz != 1 {
		t.Errorf("raw accel = %d %d %d", rx, ry, rz)
	}
	mx, my, mz, _ := c.ReadMag()
	if !closeTo(mx, 10) || !closeTo(my, -10) || !closeTo(mz, 50) {
		t.Errorf("mag = %v %v %v", mx, my, mz)
	}
	qx, qy, qz, _ := c.ReadRawMag()
	if qx != 100 || qy != -100 || qz != 500 {
		t.Errorf("raw mag = %d %d %d", qx, qy, qz)
	}
	a, mg, err := c.ReadHybrid()
	if err != nil || !closeTo(a[0], 1) || !closeTo(mg[2], 50) {
		t.Errorf("hybrid = %v %v %v", a, mg, err)
	}
	m.set(0x0B, 0)
	_ = c.SetRange(4)
	x, _, _, _ = c.ReadAccel()
	if !closeTo(x, 2) {
		t.Errorf("4 g scale: %v", x)
	}
	_ = c.SetRange(8)
	x, _, _, _ = c.ReadAccel()
	if !closeTo(x, 4) {
		t.Errorf("8 g scale: %v", x)
	}
	if m.lastByte(0x0E) != 0x02 {
		t.Errorf("XYZ_DATA_CFG = %#x", m.lastByte(0x0E))
	}
	if c.SetRange(3) == nil {
		t.Error("range 3 accepted")
	}
	if c.SetLowNoise(true) == nil {
		t.Error("low-noise accepted at 8 g")
	}
	m.set(0x51, 0xFB)
	if v, err := c.ReadTemperature(); err != nil || !closeTo(v, -5*0.96) {
		t.Errorf("temperature = %v %v", v, err)
	}
	_ = c.SetMode(0)
	if _, err := c.ReadTemperature(); err == nil {
		t.Error("temperature accepted in accel-only mode")
	}
	if _, _, err := c.ReadHybrid(); err == nil {
		t.Error("hybrid read accepted in accel-only mode")
	}
}

func TestFXOS8700StandbyHandling(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	n := len(m.writes)
	if err := c.SetDataRate(100); err != nil {
		t.Fatal(err)
	}
	var w []byte
	for _, x := range m.writes[n:] {
		if len(x) == 2 && x[0] == 0x2A {
			w = append(w, x[1])
		}
	}
	if len(w) != 3 || w[0] != 0x10 || w[1] != 0x18 || w[2] != 0x19 {
		t.Errorf("CTRL_REG1 sequence = %v", w)
	}
	if c.SetDataRate(77) == nil {
		t.Error("rate 77 accepted")
	}
	_ = c.SetDataRate(1.56)
	if (m.lastByte(0x2A)>>3)&7 != 7 {
		t.Errorf("1.56 Hz dr = %d", (m.lastByte(0x2A)>>3)&7)
	}
}

func TestFXOS8700Configuration(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.SetLowNoise(true)
	if m.lastByte(0x2A)&0x05 != 0x05 {
		t.Error("lnoise bit")
	}
	_ = c.SetResolution(8)
	if m.lastByte(0x2A)&0x02 != 0x02 {
		t.Error("f_read bit")
	}
	m.set(0x01, 0x40)
	m.set(0x03, 0xC0)
	m.set(0x05, 0x20)
	if x, _, _, _ := c.ReadAccel(); !closeTo(x, 1) {
		t.Errorf("8-bit accel x = %v", x)
	}
	_ = c.SetResolution(14)
	_ = c.SetAccelOversampling(2, 3)
	if m.lastByte(0x2B)&0x1B != 0x1A {
		t.Errorf("mods/smods = %#x", m.lastByte(0x2B))
	}
	_ = c.SetMagOversampling(5, 2)
	if m.lastByte(0x5B)&0x1C != 5<<2 || m.lastByte(0x5D)&0x70 != 2<<4 {
		t.Error("m_os / m_aslp_os")
	}
	_ = c.SetHighPassOutput(true, 2)
	if m.lastByte(0x0E)&0x10 != 0x10 || m.lastByte(0x0F)&0x03 != 2 {
		t.Error("high-pass output")
	}
}

func TestFXOS8700Offsets(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.SetAccelOffset(0.06, -0.06, 1.0)
	got := m.last(0x2F)
	if len(got) != 3 || got[0] != 30 || got[1] != 0xE2 || got[2] != 127 {
		t.Errorf("accel offset = %v", got)
	}
	x, _, z, _ := c.GetAccelOffset()
	if !closeTo(x, 0.06) || !closeTo(z, 0.254) {
		t.Errorf("accel offset readback = %v %v", x, z)
	}
	_ = c.SetMagOffset(60, -60, 0)
	want := []byte{0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00}
	got = m.last(0x3F)
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("mag offset = %v, want %v", got, want)
		}
	}
	ox, oy, _, _ := c.GetMagOffset()
	if !closeTo(ox, 60) || !closeTo(oy, -60) {
		t.Errorf("mag offset readback = %v %v", ox, oy)
	}
}

func TestFXOS8700MagHelpers(t *testing.T) {
	m := newFxosMock()
	m.hook = func(m *fxosMock, reg uint32, data []byte) []byte {
		if reg == 0x5B { // m_ost clears once read, like the hardware
			m.registers[0x5B] &^= 0x20
		}
		return data
	}
	c := newFxosFull(t, m)
	_ = c.SetMagAutoCalibration(true)
	if m.lastByte(0x5B)&0x80 != 0x80 {
		t.Error("m_acal not set")
	}
	_ = c.SetMagAutoCalibration(false)
	if m.lastByte(0x5B)&0x80 != 0 {
		t.Error("m_acal not cleared")
	}
	_ = c.ResetMagMinMax()
	if m.lastByte(0x5C)&0x04 != 0x04 {
		t.Error("m_maxmin_rst")
	}
	_ = c.TriggerMagReset()
	if m.lastByte(0x5B)&0x40 != 0x40 {
		t.Error("m_rst")
	}
	_ = c.SetMagReset(2)
	if m.lastByte(0x5C)&0x03 != 2 {
		t.Error("m_rst_cnt")
	}
	m.set(0x4B, 0xFF, 0x9C)
	m.set(0x45, 0x00, 0x64)
	mn, mx, _ := c.ReadMagMinMax()
	if !closeTo(mn[0], -10) || !closeTo(mx[0], 10) {
		t.Errorf("min/max = %v %v", mn, mx)
	}
	if _, _, _, err := c.MeasureMagOnce(); err != nil {
		t.Errorf("one-shot: %v", err)
	}
}

func TestFXOS8700Heading(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.SetMode(0)
	m.set(0x01, 0, 0, 0, 0, 0x40, 0)
	m.set(0x33, 0x01, 0xF4, 0, 0, 0, 0)
	if h, _ := c.Heading(0); math.Abs(float64(h)) > 1e-3 {
		t.Errorf("north heading = %v", h)
	}
	if h, _ := c.Heading(10); math.Abs(float64(h)-10) > 1e-3 {
		t.Errorf("declination heading = %v", h)
	}
	m.set(0x33, 0x00, 0x00, 0xFE, 0x0C, 0, 0)
	if h, _ := c.Heading(0); math.Abs(float64(h)-90) > 1e-3 {
		t.Errorf("east heading = %v", h)
	}
}

func TestFXOS8700Fifo(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	m.set(0x00, 0x42)
	m.set(0x01, 0x40)
	_ = c.SetFifo(1, 16)
	if m.lastByte(0x09) != 0x50 {
		t.Errorf("F_SETUP = %#x", m.lastByte(0x09))
	}
	n := len(m.writes)
	_ = c.SetFifo(2, 8)
	var f []byte
	for _, x := range m.writes[n:] {
		if len(x) == 2 && x[0] == 0x09 {
			f = append(f, x[1])
		}
	}
	if len(f) != 2 || f[0] != 0x00 || f[1] != 0x88 {
		t.Errorf("mode change = %v", f)
	}
	if l, _ := c.FifoLevel(); l != 2 {
		t.Errorf("level = %d", l)
	}
	if ovf, wm, cnt, _ := c.ReadFifoStatus(); ovf || !wm || cnt != 2 {
		t.Errorf("status = %v %v %d", ovf, wm, cnt)
	}
	samples, _ := c.ReadFifo(0)
	if len(samples) != 2 || !closeTo(samples[0][0], 1) {
		t.Errorf("fifo = %v", samples)
	}
	if c.SetResolution(8) == nil {
		t.Error("resolution accepted with FIFO on")
	}
	_ = c.SetFifoTrigger(true, false, false, true)
	if m.lastByte(0x0A) != 0x24 {
		t.Error("TRIG_CFG")
	}
	if c.SetFifo(4, 0) == nil {
		t.Error("bad FIFO mode accepted")
	}
}

func TestFXOS8700Detectors(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.SetFreefallMotion(true, 7, 0.3, 60, true, false)
	if m.lastByte(0x15) != 0xF8 || m.lastByte(0x17) != 5 || m.lastByte(0x18) != 6 {
		t.Errorf("FFMT = %#x %d %d", m.lastByte(0x15), m.lastByte(0x17), m.lastByte(0x18))
	}
	_ = c.SetFreefallMotion(false, 3, 0.2, 0, false, true)
	if m.lastByte(0x15) != 0x18 || m.lastByte(0x17) != 0x83 {
		t.Error("freefall config")
	}
	_ = c.SetTransient(6, 0.5, 40, true, true)
	if m.lastByte(0x1D) != 0x1D || m.lastByte(0x1F) != 8 || m.lastByte(0x20) != 4 {
		t.Error("transient config")
	}
	cfg := DefaultFXOS8700PulseConfig()
	cfg.Single, cfg.Double, cfg.ThresholdG, cfg.Latch = 1, 4, [3]float32{0.5, 0.5, 1.0}, true
	_ = c.SetPulse(cfg)
	th := m.last(0x23)
	if m.lastByte(0x21) != 0x61 || len(th) != 3 || th[0] != 8 || th[2] != 16 ||
		m.lastByte(0x26) != 3 || m.lastByte(0x27) != 5 || m.lastByte(0x28) != 15 ||
		m.lastByte(0x0F)&0x30 != 0x10 {
		t.Errorf("pulse config = %#x %v", m.lastByte(0x21), th)
	}
	cfg = DefaultFXOS8700PulseConfig()
	cfg.Single, cfg.LpfOff, cfg.HpfOff = 1, true, true
	_ = c.SetPulse(cfg)
	if m.lastByte(0x26) != 12 || m.lastByte(0x0F)&0x30 != 0x20 {
		t.Error("pulse without filters")
	}
	if err := c.SetOrientation(true, 100, 45, 4, 4, 2); err != nil {
		t.Fatal(err)
	}
	if m.lastByte(0x11) != 0xC0 || m.lastByte(0x12) != 10 || m.lastByte(0x14) != 0x84 || m.lastByte(0x13) != 0x84 {
		t.Error("orientation registers")
	}
	if c.SetOrientation(true, 0, 75, 7, 4, 2) == nil {
		t.Error("pl_ths + hys overflow accepted")
	}
	if c.SetOrientation(true, 0, 50, 4, 4, 2) == nil {
		t.Error("bad angle accepted")
	}
	m.set(0x10, 0x80|0x40|3<<1|1)
	ch, lo, pl, back, _ := c.ReadOrientation()
	if !ch || !lo || pl != 3 || !back {
		t.Error("orientation decode")
	}
	if err := c.SetAutoSleep(true, 12.5, 10, FXOS8700SourceFfMt|FXOS8700SourcePulse); err != nil {
		t.Fatal(err)
	}
	if m.lastByte(0x2A)>>6 != 1 || m.lastByte(0x29) != 31 || m.lastByte(0x2B)&0x04 != 0x04 || m.lastByte(0x2C)&0x7C != 0x18 {
		t.Error("auto-sleep registers")
	}
	if c.SetAutoSleep(true, 50, 10, 0x01) == nil {
		t.Error("bad wake source accepted")
	}
	_ = c.SetFifoGate(true)
	if m.lastByte(0x2C)&0x80 != 0x80 {
		t.Error("fifo gate")
	}
	_ = c.SetInterruptPins(true, true)
	if m.lastByte(0x2C)&0x03 != 0x03 {
		t.Error("ipol / pp_od")
	}
}

func TestFXOS8700Interrupts(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.EnableInterrupt(FXOS8700SourceFfMt|FXOS8700SourceOrientation, 1)
	if m.lastByte(0x2D) != 0x14 || m.lastByte(0x2E) != 0x14 {
		t.Error("INT1 routing")
	}
	_ = c.EnableInterrupt(FXOS8700SourceFfMt, 2)
	if m.lastByte(0x2E) != 0x10 {
		t.Error("INT2 routing")
	}
	_ = c.DisableInterrupt(FXOS8700SourceOrientation)
	if m.lastByte(0x2D) != 0x04 {
		t.Error("disable")
	}
	if c.EnableInterrupt(1, 3) == nil {
		t.Error("bad pin accepted")
	}
	m.set(0x0C, 0x14)
	s, _ := c.PollInterrupt()
	if s != 0x14 || !m.wasRead(0x16) || !m.wasRead(0x10) {
		t.Errorf("poll = %#x", s)
	}
}

func TestFXOS8700RegistersAndReset(t *testing.T) {
	m := newFxosMock()
	c := newFxosFull(t, m)
	_ = c.WriteRegister(0x5F, []byte{1, 2})
	if v, _ := c.ReadRegister(0x5F, 2); v[0] != 1 || v[1] != 2 {
		t.Error("raw registers")
	}
	m.set(0x00, 0x08)
	if ok, _ := c.NewAccelData(); !ok {
		t.Error("new accel data")
	}
	if ok, _ := c.NewMagData(); ok {
		t.Error("new mag data")
	}
	m.set(0x0B, 0x02)
	if v, _ := c.ReadSystemMode(); v != 2 {
		t.Error("system mode")
	}
	m.set(0x0B, 0x00)
	_ = c.Reset()
	if _, _, err := c.ReadHybrid(); err == nil {
		t.Error("hybrid read after reset")
	}
	if err := c.Init(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := c.ReadHybrid(); err != nil {
		t.Errorf("hybrid read after init: %v", err)
	}
}

func TestFXOS8700SelfTest(t *testing.T) {
	run := func(delta [3]int) (bool, int, int, int, *fxosMock) {
		m := newFxosMock()
		m.hook = func(m *fxosMock, reg uint32, data []byte) []byte {
			if reg == 0x01 && len(data) == 6 && m.registers[0x2B]&0x80 != 0 {
				for axis, d := range delta {
					raw := uint16(d << 2)
					data[2*axis] = byte(raw >> 8)
					data[2*axis+1] = byte(raw)
				}
			}
			return data
		}
		c := newFxosFull(t, m)
		ok, dx, dy, dz, err := c.SelfTest()
		if err != nil {
			t.Fatal(err)
		}
		return ok, dx, dy, dz, m
	}
	ok, dx, dy, dz, m := run([3]int{200, 300, 1300})
	if !ok || dx != 200 || dy != 300 || dz != 1300 {
		t.Errorf("self-test = %v %d %d %d", ok, dx, dy, dz)
	}
	if m.lastByte(0x2A) != 0x11 {
		t.Errorf("CTRL_REG1 restored to %#x", m.lastByte(0x2A))
	}
	if ok, _, _, _, _ = run([3]int{10, 10, 10}); ok {
		t.Error("weak response passed")
	}
}
