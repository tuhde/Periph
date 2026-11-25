package humidity

import (
	"errors"
	"math"
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// hts221Mock is an in-memory fake connection.RegisterConnection backed by a
// byte-addressable register map. Writes are logged and applied; a write to
// CTRL_REG2 clears BOOT like the real chip (unless stuckBoot is set).
type hts221Mock struct {
	regs      map[uint32]byte
	writes    [][]byte
	stuckBoot bool
	pin       connection.InputPin
}

func newHTS221Mock() *hts221Mock {
	m := &hts221Mock{regs: map[uint32]byte{}}
	set := func(reg uint32, v ...byte) {
		for i, b := range v {
			m.regs[reg+uint32(i)] = b
		}
	}
	// Calibration block at 0xB0 (sub-address with auto-increment):
	// H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
	// H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
	set(0xB0, 40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01)
	set(0x0F, 0xBC)
	set(0x27, 0x03)
	// Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
	set(0xA8, 0x00, 0x50, 0x90, 0x01)
	return m
}

func (m *hts221Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[reg+uint32(i)]
	}
	return out, nil
}

func (m *hts221Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	for i, b := range data {
		m.regs[reg+uint32(i)] = b
	}
	if reg == 0x21 && !m.stuckBoot {
		m.regs[reg] &^= 0x80
	}
	return nil
}

func (m *hts221Mock) Write(data []byte) error                   { return nil }
func (m *hts221Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *hts221Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *hts221Mock) Close() error                              { return nil }
func (m *hts221Mock) Enable()                                   {}
func (m *hts221Mock) Disable()                                  {}
func (m *hts221Mock) IsEnabled() bool                           { return true }
func (m *hts221Mock) IntPin() connection.InputPin               { return m.pin }
func (m *hts221Mock) EnPin() connection.OutputPin               { return nil }

func (m *hts221Mock) wrote(reg byte, value byte) bool {
	for _, w := range m.writes {
		if len(w) == 2 && w[0] == reg && w[1] == value {
			return true
		}
	}
	return false
}

type hts221Pin struct {
	mu      sync.Mutex
	handler func()
	trigger connection.Trigger
}

func (p *hts221Pin) OnEdge(t connection.Trigger, h func()) func() {
	p.mu.Lock()
	p.handler, p.trigger = h, t
	p.mu.Unlock()
	return func() {
		p.mu.Lock()
		p.handler = nil
		p.mu.Unlock()
	}
}
func (p *hts221Pin) IsHigh() (bool, error) { return false, nil }

func hts221Near(a, b, tol float64) bool { return math.Abs(a-b) <= tol }

func TestHTS221InitAndRead(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Minimal(m)
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := d.Init(); err != nil {
		t.Fatal(err)
	}
	if !m.wrote(0x20, 0x84) {
		t.Errorf("init did not write CTRL_REG1=0x84: %v", m.writes)
	}
	temp, rh, err := d.Read()
	if err != nil {
		t.Fatal(err)
	}
	if !hts221Near(float64(temp), 15.0, 1e-3) || !hts221Near(float64(rh), 30.0, 1e-3) {
		t.Errorf("Read = %v, %v, want 15.0, 30.0", temp, rh)
	}
	if !m.wrote(0x21, 0x01) {
		t.Error("read did not start a one-shot conversion")
	}
}

func TestHTS221HumidityClamped(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Minimal(m)
	_ = d.Init()
	d.cal.H0T0Out, d.cal.H1T0Out = 0, 0x1000
	m.regs[0xA9] = 0x7F
	m.regs[0xA8] = 0xFF
	if _, rh, _ := d.Read(); rh != 100 {
		t.Errorf("humidity = %v, want 100", rh)
	}
	d.cal.H0T0Out, d.cal.H1T0Out = 0x4000, 0x6000
	m.regs[0xA9], m.regs[0xA8] = 0x80, 0x00
	if _, rh, _ := d.Read(); rh != 0 {
		t.Errorf("humidity = %v, want 0", rh)
	}
}

func TestHTS221Timeout(t *testing.T) {
	m := newHTS221Mock()
	m.regs[0x27] = 0
	d := NewHTS221Minimal(m)
	_ = d.Init()
	if _, _, err := d.Read(); !errors.Is(err, ErrHTS221Timeout) {
		t.Errorf("err = %v, want ErrHTS221Timeout", err)
	}
}

func TestHTS221CalibrationMsbBits(t *testing.T) {
	m := newHTS221Mock()
	m.regs[0xB5] = 0x0D // T0 bits 9:8 = 01, T1 bits 9:8 = 11
	d := NewHTS221Full(m)
	_ = d.Init()
	cal := d.ReadCalibration()
	if !hts221Near(float64(cal.T0degC), (256+80)/8.0, 1e-6) || !hts221Near(float64(cal.T1degC), (768+160)/8.0, 1e-6) {
		t.Errorf("calibration T = %v, %v", cal.T0degC, cal.T1degC)
	}
}

func TestHTS221SingleAndRaw(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	if v, _ := d.ReadHumidity(); !hts221Near(float64(v), 30, 1e-3) {
		t.Errorf("ReadHumidity = %v", v)
	}
	if v, _ := d.ReadTemperature(); !hts221Near(float64(v), 15, 1e-3) {
		t.Errorf("ReadTemperature = %v", v)
	}
	if v, _ := d.ReadRawHumidity(); v != 0x5000 {
		t.Errorf("ReadRawHumidity = %v", v)
	}
	if v, _ := d.ReadRawTemperature(); v != 400 {
		t.Errorf("ReadRawTemperature = %v", v)
	}
	m.regs[0xA9], m.regs[0xA8] = 0xF0, 0x00
	if v, _ := d.ReadRawHumidity(); v != -4096 {
		t.Errorf("negative raw = %v, want -4096", v)
	}
	m.regs[0xA9], m.regs[0xA8] = 0x50, 0x00
	temp, rh, err := d.ReadContinuous()
	if err != nil || !hts221Near(float64(temp), 15, 1e-3) || !hts221Near(float64(rh), 30, 1e-3) {
		t.Errorf("ReadContinuous = %v, %v, %v", temp, rh, err)
	}
}

func TestHTS221OutputDataRate(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	for _, c := range []struct {
		hz   float32
		ctrl byte
	}{{12.5, 0x87}, {7, 0x86}, {1, 0x85}, {0, 0x84}} {
		if err := d.SetOutputDataRate(c.hz); err != nil {
			t.Fatal(err)
		}
		if m.regs[0x20] != c.ctrl {
			t.Errorf("rate %v: CTRL_REG1 = 0x%02X, want 0x%02X", c.hz, m.regs[0x20], c.ctrl)
		}
		if got, _ := d.GetOutputDataRate(); got != c.hz {
			t.Errorf("GetOutputDataRate = %v, want %v", got, c.hz)
		}
	}
	if err := d.SetOutputDataRate(5); !errors.Is(err, ErrHTS221InvalidArgument) {
		t.Errorf("invalid rate err = %v", err)
	}
}

func TestHTS221Averaging(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	for _, c := range []struct {
		t, h int
		reg  byte
	}{{256, 512, 0x3F}, {2, 4, 0x00}, {16, 32, 0x1B}} {
		if err := d.SetAveraging(c.t, c.h); err != nil {
			t.Fatal(err)
		}
		if m.regs[0x10] != c.reg {
			t.Errorf("AV_CONF = 0x%02X, want 0x%02X", m.regs[0x10], c.reg)
		}
		if gt, gh, _ := d.GetAveraging(); gt != c.t || gh != c.h {
			t.Errorf("GetAveraging = %d, %d", gt, gh)
		}
	}
	for _, bad := range [][2]int{{3, 32}, {16, 2}, {1, 32}, {16, 1024}} {
		if err := d.SetAveraging(bad[0], bad[1]); !errors.Is(err, ErrHTS221InvalidArgument) {
			t.Errorf("SetAveraging%v err = %v", bad, err)
		}
	}
}

func TestHTS221BduPowerHeaterDrdy(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	_ = d.SetBlockDataUpdate(false)
	if m.regs[0x20] != 0x80 {
		t.Errorf("BDU off: 0x%02X", m.regs[0x20])
	}
	_ = d.SetBlockDataUpdate(true)
	_ = d.PowerDown()
	if m.regs[0x20] != 0x04 {
		t.Errorf("power down: 0x%02X", m.regs[0x20])
	}
	_ = d.PowerUp()
	if m.regs[0x20] != 0x84 {
		t.Errorf("power up: 0x%02X", m.regs[0x20])
	}
	_ = d.SetHeater(true)
	if on, _ := d.IsHeaterOn(); !on || m.regs[0x21]&0x02 == 0 {
		t.Error("heater should be on")
	}
	_ = d.SetHeater(false)
	if on, _ := d.IsHeaterOn(); on {
		t.Error("heater should be off")
	}
	_ = d.ConfigureDRDY(true, true, false)
	if m.regs[0x22] != 0x04 {
		t.Errorf("DRDY default: 0x%02X", m.regs[0x22])
	}
	_ = d.ConfigureDRDY(true, false, true)
	if m.regs[0x22] != 0xC4 {
		t.Errorf("DRDY low/open-drain: 0x%02X", m.regs[0x22])
	}
	_ = d.ConfigureDRDY(false, true, false)
	if m.regs[0x22] != 0x00 {
		t.Errorf("DRDY off: 0x%02X", m.regs[0x22])
	}
}

func TestHTS221RebootAndTimeout(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	m.regs[0xB0] = 60
	if err := d.Reboot(); err != nil {
		t.Fatal(err)
	}
	if d.ReadCalibration().H0rH != 30 {
		t.Errorf("calibration not re-read: %v", d.ReadCalibration().H0rH)
	}
	m.stuckBoot = true
	if err := d.Reboot(); !errors.Is(err, ErrHTS221Timeout) {
		t.Errorf("stuck BOOT err = %v", err)
	}
}

func TestHTS221IdentityStatusDewPoint(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	if id, _ := d.WhoAmI(); id != 0xBC {
		t.Errorf("WhoAmI = 0x%02X", id)
	}
	if s, _ := d.Status(); s != 0x03 {
		t.Errorf("Status = 0x%02X", s)
	}
	if ok, _ := d.DataReady(); !ok {
		t.Error("DataReady should be true")
	}
	if s, _ := d.PollInterrupt(); s != 0x03 {
		t.Errorf("PollInterrupt = 0x%02X", s)
	}
	g := math.Log(0.30) + 17.62*15.0/(243.12+15.0)
	want := 243.12 * g / (17.62 - g)
	if dew, _ := d.DewPoint(); !hts221Near(float64(dew), want, 1e-3) {
		t.Errorf("DewPoint = %v, want %v", dew, want)
	}
}

func TestHTS221InterruptPin(t *testing.T) {
	m := newHTS221Mock()
	pin := &hts221Pin{}
	m.pin = pin
	d := NewHTS221Full(m)
	_ = d.Init()
	got := make(chan uint8, 1)
	if err := d.OnInterrupt(func(s uint8) { got <- s }); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x22] != 0x04 {
		t.Errorf("DRDY not enabled: 0x%02X", m.regs[0x22])
	}
	if pin.trigger != connection.Rising {
		t.Error("expected rising edge subscription")
	}
	pin.handler()
	select {
	case s := <-got:
		if s != 0x03 {
			t.Errorf("callback status = 0x%02X", s)
		}
	case <-time.After(time.Second):
		t.Fatal("callback not called")
	}
	_ = d.OffInterrupt()
	if pin.handler != nil {
		t.Error("handler not removed")
	}
	if m.regs[0x22] != 0x00 {
		t.Errorf("DRDY not disabled: 0x%02X", m.regs[0x22])
	}
}

func TestHTS221InterruptPollingFallback(t *testing.T) {
	m := newHTS221Mock()
	d := NewHTS221Full(m)
	_ = d.Init()
	got := make(chan uint8, 8)
	if err := d.OnInterrupt(func(s uint8) { got <- s }); err != nil {
		t.Fatal(err)
	}
	select {
	case s := <-got:
		if s != 0x03 {
			t.Errorf("callback status = 0x%02X", s)
		}
	case <-time.After(time.Second):
		t.Fatal("polling fallback did not deliver")
	}
	_ = d.OffInterrupt()
}
