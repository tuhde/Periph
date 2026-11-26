package pressure

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
var spl06TestCoef = []byte{0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
						0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA}
var spl06TestTraw = []byte{0x02, 0x5C, 0x28} // 154664
var spl06TestPraw = []byte{0xFE, 0x94, 0xB7} // -93001

const (
	spl06TExp = 25.0054779053
	spl06PExp = 98263.8611522177
)

// spl06Mock is mockConnection with MEAS_CFG keeping its read-only ready flags on write
// and PRS_B2..0 popping queued FIFO entries.
type spl06Mock struct {
	*mockConnection
	flags   byte
	fifo    [][]byte
	useFifo bool
	pin     connection.InputPin
}

func (m *spl06Mock) Write(data []byte) error {
	if err := m.mockConnection.Write(data); err != nil {
		return err
	}
	if len(data) >= 2 && data[0] == spl06RegMeasCfg {
		m.registers[spl06RegMeasCfg] = data[1]&0x07 | m.flags
	}
	return nil
}

func (m *spl06Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *spl06Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	if m.useFifo && byte(reg) == spl06RegFifoSts {
		if len(m.fifo) > 0 {
			m.registers[spl06RegFifoSts] = 0x00
		} else {
			m.registers[spl06RegFifoSts] = 0x01
		}
	}
	if m.useFifo && byte(reg) == spl06RegPrsB2 && length == 3 && len(m.fifo) > 0 {
		m.writes = append(m.writes, []byte{byte(reg)})
		e := m.fifo[0]
		m.fifo = m.fifo[1:]
		return append([]byte(nil), e...), nil
	}
	return m.mockConnection.ReadReg(reg, length)
}

func (m *spl06Mock) IntPin() connection.InputPin { return m.pin }

type spl06FakePin struct {
	handler func()
	trigger connection.Trigger
}

func (p *spl06FakePin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.handler, p.trigger = handler, trigger
	return func() { p.handler = nil }
}

func (p *spl06FakePin) IsHigh() (bool, error) { return true, nil }

func newSPL06Mock() *spl06Mock {
	c := &spl06Mock{mockConnection: newMockConnection(), flags: 0xF0}
	c.setRegister(spl06RegID, 0x10)
	c.setRegister(spl06RegMeasCfg, 0xF0)
	c.setRegister(spl06RegCoef, spl06TestCoef...)
	c.setRegister(spl06RegPrsB2, append(append([]byte{}, spl06TestPraw...), spl06TestTraw...)...)
	return c
}

func spl06Last(c *spl06Mock, reg byte) int {
	v := -1
	for _, w := range c.writes {
		if len(w) >= 2 && w[0] == reg {
			v = int(w[1])
		}
	}
	return v
}

func spl06Count(c *spl06Mock, reg byte) int {
	n := 0
	for _, w := range c.writes {
		if len(w) >= 2 && w[0] == reg {
			n++
		}
	}
	return n
}

func spl06Near(a, b, tol float64) bool { return math.Abs(a-b) < tol }

func TestSPL06Full(t *testing.T) {
	c := newSPL06Mock()
	s, err := NewSPL06Full(c)
	if err != nil || len(c.writes) != 0 {
		t.Fatalf("constructor: err=%v writes=%d", err, len(c.writes))
	}
	if err := s.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	if spl06Last(c, spl06RegTmpCfg) != 0x80 || spl06Last(c, spl06RegPrsCfg) != 0x04 || spl06Last(c, spl06RegCfgReg) != 0x04 {
		t.Errorf("init defaults: TMP_CFG=%#x PRS_CFG=%#x CFG_REG=%#x",
			spl06Last(c, spl06RegTmpCfg), spl06Last(c, spl06RegPrsCfg), spl06Last(c, spl06RegCfgReg))
	}
	want := SPL06Coefficients{204, -261, 80469, -54769, -2659, 1258, -10529, 94, -1350}
	if s.Coefficients() != want {
		t.Errorf("coefficients = %+v, want %+v", s.Coefficients(), want)
	}

	m, err := s.Read()
	if err != nil || !spl06Near(m.TemperatureC, spl06TExp, 1e-6) || !spl06Near(m.PressurePa, spl06PExp, 1e-6) {
		t.Errorf("Read = %+v, %v", m, err)
	}
	var meas []byte
	for _, w := range c.writes {
		if len(w) >= 2 && w[0] == spl06RegMeasCfg {
			meas = append(meas, w[1])
		}
	}
	if n := len(meas); n < 2 || meas[n-2] != 0x02 || meas[n-1] != 0x01 {
		t.Errorf("command order %v, want temperature then pressure", meas)
	}
	if p, err := s.ReadPressure(); err != nil || !spl06Near(p, spl06PExp, 1e-6) {
		t.Errorf("ReadPressure = %v, %v", p, err)
	}
	if tc, err := s.ReadTemperature(); err != nil || !spl06Near(tc, spl06TExp, 1e-6) || spl06Last(c, spl06RegMeasCfg) != 0x02 {
		t.Errorf("ReadTemperature = %v, %v", tc, err)
	}
	if praw, traw, err := s.ReadRaw(); err != nil || praw != -93001 || traw != 154664 {
		t.Errorf("ReadRaw = %d %d %v", praw, traw, err)
	}

	r, err := s.Compensate(-93001, 154664, 16, 1)
	if err != nil || !spl06Near(r.PressurePa, spl06PExp, 1e-6) || !spl06Near(r.TemperatureC, spl06TExp, 1e-6) {
		t.Errorf("Compensate = %+v %v", r, err)
	}
	r, _ = s.Compensate(-93001, 154664, 8, 2)
	if !spl06Near(r.PressurePa, 80852.2826419888, 1e-6) || !spl06Near(r.TemperatureC, 76.3351593018, 1e-6) {
		t.Errorf("Compensate 8/2 = %+v", r)
	}
	if _, err := s.Compensate(0, 0, 3, 1); err != ErrSPL06Argument {
		t.Errorf("Compensate invalid oversampling: %v", err)
	}
	if a, err := s.Altitude(101325); err != nil || !spl06Near(a, 258.0291543011, 1e-3) {
		t.Errorf("Altitude = %v %v", a, err)
	}
	if a, _ := s.Altitude(spl06PExp); !spl06Near(a, 0, 1e-6) {
		t.Errorf("Altitude at reference = %v", a)
	}

	if err := s.SetPressureConfig(128, 8); err != nil || spl06Last(c, spl06RegPrsCfg) != 0x37 || spl06Last(c, spl06RegCfgReg)&0x04 == 0 {
		t.Errorf("SetPressureConfig(128, 8): PRS_CFG=%#x CFG=%#x", spl06Last(c, spl06RegPrsCfg), spl06Last(c, spl06RegCfgReg))
	}
	if _ = s.SetPressureConfig(8, 1); spl06Last(c, spl06RegPrsCfg) != 0x03 || spl06Last(c, spl06RegCfgReg)&0x04 != 0 {
		t.Errorf("SetPressureConfig(8, 1) did not clear P_SHIFT")
	}
	if _ = s.SetTemperatureConfig(32, 4); spl06Last(c, spl06RegTmpCfg) != 0xA5 || spl06Last(c, spl06RegCfgReg)&0x08 == 0 {
		t.Errorf("SetTemperatureConfig(32, 4): TMP_CFG=%#x", spl06Last(c, spl06RegTmpCfg))
	}
	if _ = s.SetTemperatureConfig(2, 1); spl06Last(c, spl06RegTmpCfg) != 0x81 || spl06Last(c, spl06RegCfgReg)&0x08 != 0 {
		t.Errorf("SetTemperatureConfig(2, 1): TMP_CFG=%#x", spl06Last(c, spl06RegTmpCfg))
	}
	if m, _ := s.Read(); !spl06Near(m.PressurePa, 80852.2826419888, 1e-6) {
		t.Errorf("Read with new scale = %v", m.PressurePa)
	}
	if s.SetPressureConfig(3, 1) != ErrSPL06Argument || s.SetPressureConfig(16, 3) != ErrSPL06Argument ||
		s.SetTemperatureConfig(0, 1) != ErrSPL06Argument {
		t.Errorf("invalid configuration accepted")
	}
	_ = s.SetPressureConfig(16, 1)
	_ = s.SetTemperatureConfig(1, 1)

	for _, tc := range []struct {
		p, t bool
		want int
	}{{true, true, 0x07}, {true, false, 0x05}, {false, true, 0x06}} {
		if err := s.StartContinuous(tc.p, tc.t); err != nil || spl06Last(c, spl06RegMeasCfg) != tc.want {
			t.Errorf("StartContinuous(%v, %v) wrote %#x", tc.p, tc.t, spl06Last(c, spl06RegMeasCfg))
		}
	}
	if s.StartContinuous(false, false) != ErrSPL06Argument {
		t.Errorf("StartContinuous(false, false) accepted")
	}
	_ = s.StartContinuous(true, true)
	n := spl06Count(c, spl06RegMeasCfg)
	if m, err := s.ReadContinuous(); err != nil || !spl06Near(m.PressurePa, spl06PExp, 1e-6) || spl06Count(c, spl06RegMeasCfg) != n {
		t.Errorf("ReadContinuous = %+v %v", m, err)
	}
	if m, _ := s.Read(); !spl06Near(m.PressurePa, spl06PExp, 1e-6) || spl06Count(c, spl06RegMeasCfg) != n {
		t.Errorf("Read in background mode started a measurement")
	}
	c.registers[spl06RegMeasCfg] = 0xD7
	if pr, tr, _ := s.DataReady(); !pr || tr {
		t.Errorf("DataReady = %v %v", pr, tr)
	}
	c.registers[spl06RegMeasCfg] = 0xF7
	if _ = s.Stop(); spl06Last(c, spl06RegMeasCfg) != 0x00 {
		t.Errorf("Stop did not write MEAS_CTRL = 0")
	}

	if _ = s.EnableFIFO(); spl06Last(c, spl06RegCfgReg)&0x02 == 0 {
		t.Errorf("EnableFIFO")
	}
	c.useFifo = true
	c.fifo = [][]byte{spl06TestTraw, spl06TestPraw}
	entries, err := s.ReadFIFO()
	if err != nil || len(entries) != 2 ||
		entries[0].Kind != SPL06KindTemperature || !spl06Near(entries[0].Value, spl06TExp, 1e-6) ||
		entries[1].Kind != SPL06KindPressure || !spl06Near(entries[1].Value, 98264.0472093194, 1e-6) {
		t.Errorf("ReadFIFO = %+v %v", entries, err)
	}
	if e, _ := s.ReadFIFO(); len(e) != 0 {
		t.Errorf("ReadFIFO on empty FIFO = %+v", e)
	}
	c.useFifo = false
	c.setRegister(spl06RegFifoSts, 0x02)
	if empty, full, _ := s.FIFOStatus(); empty || !full {
		t.Errorf("FIFOStatus = %v %v", empty, full)
	}
	if _ = s.FlushFIFO(); spl06Last(c, spl06RegReset) != 0x80 {
		t.Errorf("FlushFIFO")
	}
	if _ = s.DisableFIFO(); spl06Last(c, spl06RegCfgReg)&0x02 != 0 {
		t.Errorf("DisableFIFO")
	}

	_ = s.EnableInterrupt(SPL06SourcePressure)
	if spl06Last(c, spl06RegCfgReg)&0x20 == 0 {
		t.Errorf("EnableInterrupt(pressure)")
	}
	_ = s.EnableInterrupt(SPL06SourceTemperature | SPL06SourceFIFOFull)
	if spl06Last(c, spl06RegCfgReg)&0x74 != 0x74 {
		t.Errorf("EnableInterrupt(combined) CFG=%#x", spl06Last(c, spl06RegCfgReg))
	}
	_ = s.DisableInterrupt(SPL06SourcePressure | SPL06SourceFIFOFull)
	if spl06Last(c, spl06RegCfgReg)&0x70 != 0x10 {
		t.Errorf("DisableInterrupt CFG=%#x", spl06Last(c, spl06RegCfgReg))
	}
	c.setRegister(spl06RegIntSts, 0xFA)
	if st, _ := s.PollInterrupt(); st != 0x02 {
		t.Errorf("PollInterrupt = %#x", st)
	}

	pin := &spl06FakePin{}
	c.pin = pin
	var seen []uint8
	if err := s.OnInterrupt(func(st uint8) { seen = append(seen, st) }); err != nil || pin.handler == nil || pin.trigger != connection.Falling {
		t.Fatalf("OnInterrupt: %v", err)
	}
	c.setRegister(spl06RegIntSts, 0x01)
	pin.handler()
	c.setRegister(spl06RegIntSts, 0x00)
	pin.handler()
	if len(seen) != 1 || seen[0] != 0x01 {
		t.Errorf("interrupt dispatch = %v", seen)
	}
	_ = s.OffInterrupt()
	if pin.handler != nil {
		t.Errorf("OffInterrupt did not unsubscribe")
	}

	_ = s.SetPressureConfig(64, 2)
	if err := s.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if spl06Last(c, spl06RegReset) != 0x09 || spl06Last(c, spl06RegPrsCfg) != 0x04 ||
		spl06Last(c, spl06RegTmpCfg) != 0x80 || spl06Last(c, spl06RegCfgReg) != 0x04 {
		t.Errorf("Reset did not restore defaults")
	}
}

func TestSPL06Minimal(t *testing.T) {
	s, _ := NewSPL06Minimal(newSPL06Mock())
	if err := s.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	m, err := s.Read()
	if err != nil || !spl06Near(m.PressurePa, spl06PExp, 1e-6) || !spl06Near(m.TemperatureC, spl06TExp, 1e-6) {
		t.Errorf("Read = %+v %v", m, err)
	}

	bad := newSPL06Mock()
	bad.setRegister(spl06RegID, 0x58)
	s, _ = NewSPL06Minimal(bad)
	if s.Init() != ErrSPL06ChipID {
		t.Errorf("wrong ID accepted")
	}

	slow := newSPL06Mock()
	slow.setRegister(spl06RegMeasCfg, 0x40)
	s, _ = NewSPL06Minimal(slow)
	if s.Init() != ErrSPL06Timeout {
		t.Errorf("missing COEF_RDY accepted")
	}
}
