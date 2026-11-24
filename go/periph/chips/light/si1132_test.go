package light

import (
	"math"
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// si1132Mock models the Si1132 sequencer: RESPONSE counter, parameter RAM and
// forced measurement, on top of a byte-addressable register map.
type si1132Mock struct {
	mu       sync.Mutex
	regs     map[uint32]byte
	writes   [][]byte
	params   map[byte]byte
	cmds     []byte
	vis, ir  uint16
	aux      uint16
	stuck    bool
	cmdError int
	pin      connection.InputPin
}

func newSi1132Mock(seqID, partID byte) *si1132Mock {
	m := &si1132Mock{regs: map[uint32]byte{}, params: map[byte]byte{}, vis: 1234, ir: 567, aux: 250, cmdError: -1}
	m.regs[0x00] = partID
	m.regs[0x02] = seqID
	return m
}

func (m *si1132Mock) set(reg uint32, v byte) {
	m.mu.Lock()
	m.regs[reg] = v
	m.mu.Unlock()
}

func (m *si1132Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[reg+uint32(i)]
	}
	return out, nil
}

func (m *si1132Mock) count() { m.regs[0x20] = (m.regs[0x20] + 1) & 0x0F }

func (m *si1132Mock) WriteReg(reg uint32, data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if reg == 0x21 && len(data) == 1 {
		m.regs[0x21] &^= data[0]
		return nil
	}
	for i, b := range data {
		m.regs[reg+uint32(i)] = b
	}
	if reg == 0x18 && len(data) == 1 {
		m.cmds = append(m.cmds, data[0])
		m.command(data[0])
	}
	return nil
}

func (m *si1132Mock) command(cmd byte) {
	if m.cmdError >= 0 && cmd != 0x00 {
		m.regs[0x20] = byte(m.cmdError)
		return
	}
	switch {
	case cmd&0xE0 == 0xA0:
		m.params[cmd&0x1F] = m.regs[0x17]
		m.regs[0x2E] = m.params[cmd&0x1F]
		m.count()
	case cmd&0xE0 == 0x80:
		m.regs[0x2E] = m.params[cmd&0x1F]
		m.count()
	case cmd == 0x00:
		m.regs[0x20] = 0
	case cmd == 0x06:
		if !m.stuck {
			m.measure()
		}
		m.count()
	case cmd == 0x12:
		for i := 0; i < 12; i++ {
			m.regs[0x22+uint32(i)] = byte(0x10 + i)
		}
		m.count()
	case cmd != 0x01:
		m.count()
	}
}

func (m *si1132Mock) measure() {
	a := m.aux
	if m.params[0x01] == 0x40 {
		a = 0x2000
		if m.params[0x0F] == 0x65 {
			a = 0x4000
		}
	}
	m.regs[0x22], m.regs[0x23] = byte(m.vis), byte(m.vis>>8)
	m.regs[0x24], m.regs[0x25] = byte(m.ir), byte(m.ir>>8)
	m.regs[0x2C], m.regs[0x2D] = byte(a), byte(a>>8)
	m.regs[0x21] |= 0x01
}

func (m *si1132Mock) Write(data []byte) error                   { return nil }
func (m *si1132Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *si1132Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *si1132Mock) Close() error                              { return nil }
func (m *si1132Mock) Enable()                                   {}
func (m *si1132Mock) Disable()                                  {}
func (m *si1132Mock) IsEnabled() bool                           { return true }
func (m *si1132Mock) IntPin() connection.InputPin               { return m.pin }
func (m *si1132Mock) EnPin() connection.OutputPin               { return nil }

func (m *si1132Mock) wrote(bytes ...byte) bool {
	for _, w := range m.writes {
		if len(w) == len(bytes) {
			eq := true
			for i := range w {
				if w[i] != bytes[i] {
					eq = false
				}
			}
			if eq {
				return true
			}
		}
	}
	return false
}

type si1132Pin struct {
	mu      sync.Mutex
	handler func()
	trigger connection.Trigger
}

func (p *si1132Pin) OnEdge(t connection.Trigger, h func()) func() {
	p.mu.Lock()
	p.handler, p.trigger = h, t
	p.mu.Unlock()
	return func() {
		p.mu.Lock()
		p.handler = nil
		p.mu.Unlock()
	}
}
func (p *si1132Pin) IsHigh() (bool, error) { return false, nil }

func si1132Ready(t *testing.T) (*si1132Mock, *Si1132Full) {
	t.Helper()
	m := newSi1132Mock(0x08, 0x32)
	d := NewSi1132Full(m)
	if err := d.Init(); err != nil {
		t.Fatal(err)
	}
	return m, d
}

func TestSi1132InitAndRead(t *testing.T) {
	m := newSi1132Mock(0x08, 0x32)
	d := NewSi1132Minimal(m)
	if len(m.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := d.Init(); err != nil {
		t.Fatal(err)
	}
	if !m.wrote(0x07, 0x17) || !m.wrote(0x13, 0x7B, 0x6B, 0x01, 0x00) {
		t.Errorf("init did not write HW_KEY/UCOEF: %v", m.writes)
	}
	if m.params[0x01] != 0xB0 || m.regs[0x04] != 0x01 {
		t.Errorf("CHLIST = 0x%02X, IRQ_ENABLE = 0x%02X", m.params[0x01], m.regs[0x04])
	}
	uv, vis, ir, err := d.Read()
	if err != nil {
		t.Fatal(err)
	}
	if math.Abs(float64(uv)-2.5) > 1e-4 || vis != 1234 || ir != 567 {
		t.Errorf("Read = %v, %v, %v", uv, vis, ir)
	}
	if m.regs[0x21] != 0 {
		t.Error("status not cleared")
	}
}

func TestSi1132WrongPartID(t *testing.T) {
	d := NewSi1132Minimal(newSi1132Mock(0x08, 0x45))
	if err := d.Init(); err != ErrSi1132NotFound {
		t.Errorf("Init error = %v", err)
	}
}

func TestSi1132MeasurementTimeout(t *testing.T) {
	m := newSi1132Mock(0x08, 0x32)
	m.stuck = true
	d := NewSi1132Minimal(m)
	_ = d.Init()
	if _, _, _, err := d.Read(); err != ErrSi1132Timeout {
		t.Errorf("Read error = %v", err)
	}
}

func TestSi1132CommandError(t *testing.T) {
	m, d := si1132Ready(t)
	m.cmdError = 0x80
	if err := d.SetGain(1, 1); err == nil {
		t.Error("expected command error")
	}
	if m.cmds[len(m.cmds)-1] != 0x00 {
		t.Error("NOP not sent after error")
	}
}

func TestSi1132SingleChannels(t *testing.T) {
	_, d := si1132Ready(t)
	if uv, _ := d.ReadUVIndex(); math.Abs(float64(uv)-2.5) > 1e-4 {
		t.Errorf("uv = %v", uv)
	}
	if v, _ := d.ReadVisible(); v != 1234 {
		t.Errorf("visible = %v", v)
	}
	if ir, _ := d.ReadInfrared(); ir != 567 {
		t.Errorf("infrared = %v", ir)
	}
	v, ir, aux, _ := d.ReadRaw()
	if v != 1234 || ir != 567 || aux != 250 {
		t.Errorf("raw = %v %v %v", v, ir, aux)
	}
}

func TestSi1132AuxChannels(t *testing.T) {
	m, d := si1132Ready(t)
	if v, _ := d.ReadTemperatureRaw(); v != 0x4000 || m.params[0x0F] != 0x65 {
		t.Errorf("temperature raw = 0x%X mux 0x%X", v, m.params[0x0F])
	}
	if m.params[0x01] != 0xB0 {
		t.Error("CHLIST not restored")
	}
	if v, _ := d.ReadVddRaw(); v != 0x2000 || m.params[0x0F] != 0x75 {
		t.Errorf("vdd raw = 0x%X mux 0x%X", v, m.params[0x0F])
	}
}

func TestSi1132Configuration(t *testing.T) {
	m, d := si1132Ready(t)
	if err := d.SetChannels(true, false, true, false); err != nil || m.params[0x01] != 0xA0 {
		t.Errorf("SetChannels: %v 0x%02X", err, m.params[0x01])
	}
	if err := d.SetChannels(false, true, false, true); err != nil || m.params[0x01] != 0x50 {
		t.Errorf("SetChannels aux: %v 0x%02X", err, m.params[0x01])
	}
	if d.SetChannels(false, false, false, false) == nil || d.SetChannels(true, true, true, true) == nil {
		t.Error("invalid channel selection accepted")
	}
	if err := d.SetGain(3, 5); err != nil {
		t.Fatal(err)
	}
	if m.params[0x11] != 3 || m.params[0x10] != 0x40 || m.params[0x1E] != 5 || m.params[0x1D] != 0x20 {
		t.Errorf("gain params: %v", m.params)
	}
	if d.SetGain(8, 0) == nil {
		t.Error("gain 8 accepted")
	}
	_ = d.SetHighRange(true, false)
	if m.params[0x12] != 0x20 || m.params[0x1F] != 0x00 {
		t.Errorf("high range params: %v", m.params)
	}
	_ = d.SetAlignment(true, true)
	if m.params[0x06] != 0x30 {
		t.Errorf("alignment = 0x%02X", m.params[0x06])
	}
	_ = d.SetUVCoefficients([4]byte{1, 2, 3, 4})
	if !m.wrote(0x13, 1, 2, 3, 4) {
		t.Error("UCOEF not written")
	}
}

func TestSi1132Calibration(t *testing.T) {
	_, d := si1132Ready(t)
	cal, err := d.ReadCalibration()
	if err != nil || cal[0] != 0x10 || cal[11] != 0x1B {
		t.Errorf("calibration = %v, %v", cal, err)
	}
}

func TestSi1132Autonomous(t *testing.T) {
	m, d := si1132Ready(t)
	if err := d.StartAutonomous(0.5); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x08] != 0x80 || m.regs[0x09] != 0x3E || m.cmds[len(m.cmds)-1] != 0x0E {
		t.Errorf("autonomous regs 0x%02X 0x%02X cmd 0x%02X", m.regs[0x08], m.regs[0x09], m.cmds[len(m.cmds)-1])
	}
	_ = d.StartAutonomous(0)
	if m.cmds[len(m.cmds)-1] != 0x0A {
		t.Error("interval 0 did not pause")
	}
	if d.StartAutonomous(3.0) == nil || d.StartAutonomous(-1) == nil {
		t.Error("invalid interval accepted")
	}
	m2 := newSi1132Mock(0x01, 0x32)
	d2 := NewSi1132Full(m2)
	_ = d2.Init()
	_ = d2.StartAutonomous(0.5)
	if m2.regs[0x0A] != 0x80 || m2.regs[0x08] != 0x3E {
		t.Error("erratum byte order not applied")
	}
}

func TestSi1132Status(t *testing.T) {
	m, d := si1132Ready(t)
	_ = d.Pause()
	if m.cmds[len(m.cmds)-1] != 0x0A {
		t.Error("pause")
	}
	_ = d.Force()
	if m.cmds[len(m.cmds)-1] != 0x06 {
		t.Error("force")
	}
	if ok, _ := d.MeasurementReady(); !ok {
		t.Error("measurement should be ready")
	}
	m.regs[0x21] = 0
	if ok, _ := d.MeasurementReady(); ok {
		t.Error("measurement should not be ready")
	}
	m.regs[0x20] = 0x05
	if c, e, _ := d.ReadResponse(); c != 5 || e != 0 {
		t.Errorf("response = %d %d", c, e)
	}
	m.regs[0x20] = 0x8C
	if c, e, _ := d.ReadResponse(); c != 0 || e != 0x8C {
		t.Errorf("response error = %d %d", c, e)
	}
	_ = d.SetI2CAddress(0x61)
	if m.params[0x00] != 0x61 || m.cmds[len(m.cmds)-1] != 0x02 {
		t.Error("set address")
	}
	_ = d.Reset()
	last := m.writes[len(m.writes)-1]
	if !m.wrote(0x18, 0x01) || len(last) != 2 || last[0] != 0x07 || last[1] != 0x17 {
		t.Errorf("reset writes: %v", m.writes)
	}
}

func TestSi1132InterruptRegisters(t *testing.T) {
	m, d := si1132Ready(t)
	_ = d.EnableInterrupt()
	if m.regs[0x03] != 0x01 || m.regs[0x04] != 0x01 {
		t.Error("EnableInterrupt")
	}
	_ = d.DisableInterrupt()
	if m.regs[0x03] != 0x00 {
		t.Error("DisableInterrupt")
	}
	m.regs[0x21] = 0x01
	if s, _ := d.PollInterrupt(); s != 0x01 || m.regs[0x21] != 0 {
		t.Errorf("PollInterrupt = 0x%02X, status 0x%02X", s, m.regs[0x21])
	}
	if s, _ := d.PollInterrupt(); s != 0 {
		t.Error("second poll should be empty")
	}
}

func TestSi1132InterruptPin(t *testing.T) {
	m, d := si1132Ready(t)
	pin := &si1132Pin{}
	m.pin = pin
	got := make(chan uint8, 1)
	if err := d.OnInterrupt(func(s uint8) { got <- s }); err != nil {
		t.Fatal(err)
	}
	if m.regs[0x03] != 0x01 {
		t.Error("INT not enabled")
	}
	if pin.trigger != connection.Falling {
		t.Error("expected falling edge subscription")
	}
	m.regs[0x21] = 0x01
	pin.handler()
	select {
	case s := <-got:
		if s != 0x01 {
			t.Errorf("callback status = 0x%02X", s)
		}
	case <-time.After(time.Second):
		t.Fatal("callback not called")
	}
	_ = d.OffInterrupt()
	if pin.handler != nil || m.regs[0x03] != 0x00 {
		t.Error("not unsubscribed / released")
	}
}

func TestSi1132InterruptPollingFallback(t *testing.T) {
	m, d := si1132Ready(t)
	got := make(chan uint8, 8)
	if err := d.OnInterrupt(func(s uint8) { got <- s }); err != nil {
		t.Fatal(err)
	}
	m.set(0x21, 0x01)
	select {
	case s := <-got:
		if s != 0x01 {
			t.Errorf("callback status = 0x%02X", s)
		}
	case <-time.After(time.Second):
		t.Fatal("polling fallback did not deliver")
	}
	_ = d.OffInterrupt()
}
