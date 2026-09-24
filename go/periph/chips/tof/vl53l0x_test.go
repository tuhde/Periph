package tof

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// simConnection is a page-aware VL53L0X simulator implementing Connection.
// Registers written while 0xFF != 0 go to a separate per-page store, so the
// private-bank tuning writes don't clobber page-0 registers. Starting a
// ranging (or calibration) raises RESULT_INTERRUPT_STATUS; the interrupt
// clear drops it unless continuous mode is active. The SPAD-info handshake
// (page 7, 0x83) completes immediately.
type simConnection struct {
	regs       map[uint8]uint8
	pages      map[[2]uint8]uint8
	log        []simWrite
	page       uint8
	continuous bool
}

type simWrite struct {
	page uint8
	data []byte
}

func newSim() *simConnection {
	s := &simConnection{regs: map[uint8]uint8{}, pages: map[[2]uint8]uint8{}}
	s.set(0xC0, 0xEE, 0xAA, 0x10)
	s.set(0x84, 0x11)
	s.set(0xB0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
	s.set(0xF8, 0x00, 0x10)
	// Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
	s.set(0x14, 11<<3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA)
	s.pages[[2]uint8{1, 0x91}] = 0x3C
	s.pages[[2]uint8{7, 0x92}] = 0x85
	return s
}

func (s *simConnection) set(reg uint8, values ...uint8) {
	for i, v := range values {
		s.regs[reg+uint8(i)] = v
	}
}

func (s *simConnection) reg16(reg uint8) uint16 {
	return uint16(s.regs[reg])<<8 | uint16(s.regs[reg+1])
}

func (s *simConnection) page0Writes(reg uint8) [][]byte {
	var out [][]byte
	for _, w := range s.log {
		if w.page == 0 && len(w.data) >= 2 && w.data[0] == reg {
			out = append(out, w.data)
		}
	}
	return out
}

func (s *simConnection) logged(data ...byte) bool {
	for _, w := range s.log {
		if bytes.Equal(w.data, data) {
			return true
		}
	}
	return false
}

func (s *simConnection) Write(data []byte) error {
	reg := data[0]
	if reg == 0xFF {
		s.page = data[1]
	}
	s.log = append(s.log, simWrite{s.page, append([]byte(nil), data...)})
	if s.page != 0 && reg != 0xFF {
		for i, v := range data[1:] {
			s.pages[[2]uint8{s.page, reg + uint8(i)}] = v
		}
		if s.page == 7 && reg == 0x83 && data[1] == 0x00 {
			s.pages[[2]uint8{7, 0x83}] = 0x01
		}
		return nil
	}
	for i, v := range data[1:] {
		s.regs[reg+uint8(i)] = v
	}
	if reg == 0x00 && len(data) == 2 {
		v := data[1]
		switch {
		case v&0x06 != 0:
			s.continuous = true
			s.regs[0x13] = 0x04
		case v&0x01 != 0:
			if s.continuous {
				s.continuous = false
			} else {
				s.regs[0x13] = 0x04
			}
		}
		s.regs[0x00] = 0x00
	} else if reg == 0x0B && data[1] == 0x01 && !s.continuous {
		s.regs[0x13] = 0x00
	}
	return nil
}

func (s *simConnection) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (s *simConnection) WriteRead(data []byte, n int) ([]byte, error) {
	out := make([]byte, n)
	for i := range out {
		r := data[0] + uint8(i)
		if s.page != 0 {
			out[i] = s.pages[[2]uint8{s.page, r}]
		} else {
			out[i] = s.regs[r]
		}
	}
	return out, nil
}

func (s *simConnection) Close() error                { return nil }
func (s *simConnection) Enable()                     {}
func (s *simConnection) Disable()                    {}
func (s *simConnection) IsEnabled() bool             { return true }
func (s *simConnection) IntPin() connection.InputPin { return nil }
func (s *simConnection) EnPin() connection.OutputPin { return nil }

func tail(ws [][]byte, n int) []byte {
	var out []byte
	for _, w := range ws[len(ws)-n:] {
		out = append(out, w[1])
	}
	return out
}

func near(a, b, tol uint32) bool {
	if a > b {
		return a-b < tol
	}
	return b-a < tol
}

func TestVL53L0XRejectsWrongModelID(t *testing.T) {
	s := newSim()
	s.set(0xC0, 0xEF)
	if _, err := NewVL53L0XMinimal(s); !errors.Is(err, ErrVL53L0XNotFound) {
		t.Errorf("want ErrVL53L0XNotFound, got %v", err)
	}
}

func TestVL53L0XInit(t *testing.T) {
	s := newSim()
	if _, err := NewVL53L0XMinimal(s); err != nil {
		t.Fatal(err)
	}
	if s.regs[0x89]&0x01 != 0x01 || s.regs[0x88] != 0x00 {
		t.Error("2V8 / standard I2C mode not set")
	}
	if !bytes.Equal(s.page0Writes(0x60)[0], []byte{0x60, 0x12}) {
		t.Error("signal-rate checks not disabled")
	}
	if !bytes.Equal(s.page0Writes(0x44)[0], []byte{0x44, 0x00, 0x20}) {
		t.Error("signal-rate limit not 0.25 MCPS")
	}
	spad := []byte{s.regs[0xB0], s.regs[0xB1], s.regs[0xB2], s.regs[0xB3], s.regs[0xB4], s.regs[0xB5]}
	if !bytes.Equal(spad, []byte{0x00, 0xF0, 0x01, 0, 0, 0}) {
		t.Errorf("SPAD map %x", spad)
	}
	if s.regs[0xB6] != 0xB4 || s.pages[[2]uint8{1, 0x4E}] != 0x2C {
		t.Error("reference SPAD setup")
	}
	if s.regs[0x46] != 0x25 || s.pages[[2]uint8{1, 0x46}] != 0x05 {
		t.Error("tuning table pages")
	}
	if s.regs[0x0A] != 0x04 || s.regs[0x84] != 0x01 || s.regs[0x01] != 0xE8 {
		t.Error("GPIO1 / sequence config")
	}
	if got := tail(s.page0Writes(0x00), 4); !bytes.Equal(got, []byte{0x41, 0x00, 0x01, 0x00}) {
		t.Errorf("calibration starts %x", got)
	}
	if got := tail(s.page0Writes(0x01), 4); !bytes.Equal(got, []byte{0xE8, 0x01, 0x02, 0xE8}) {
		t.Errorf("sequence order %x", got)
	}
	if s.page != 0 {
		t.Error("left on a private page")
	}
}

func TestVL53L0XSingleShot(t *testing.T) {
	s := newSim()
	d, err := NewVL53L0XMinimal(s)
	if err != nil {
		t.Fatal(err)
	}
	s.log = nil
	if got, _ := d.Distance(); got != 250 || !d.RangeValid() {
		t.Errorf("Distance = %d, valid %v", got, d.RangeValid())
	}
	want := [][]byte{{0x80, 0x01}, {0xFF, 0x01}, {0x00, 0x00}, {0x91, 0x3C}, {0x00, 0x01}, {0xFF, 0x00},
		{0x80, 0x00}, {0x00, 0x01}}
	for i, w := range want {
		if !bytes.Equal(s.log[i].data, w) {
			t.Errorf("write %d = %x, want %x", i, s.log[i].data, w)
		}
	}
	if !bytes.Equal(s.log[len(s.log)-1].data, []byte{0x0B, 0x01}) {
		t.Error("interrupt not cleared")
	}
	s.set(0x14, 4<<3)
	s.set(0x1E, 0x1F, 0xFF)
	if got, _ := d.Distance(); got != 8191 || d.RangeValid() {
		t.Errorf("out of range: %d valid %v", got, d.RangeValid())
	}
}

func TestVL53L0XFullMeasurementAndContinuous(t *testing.T) {
	s := newSim()
	d, err := NewVL53L0XFull(s)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = d.Distance()
	m, _ := d.ReadMeasurement()
	if m != (VL53L0XMeasurement{250, 11, 5.0, 0.5, 10.0}) || d.RangeStatus() != 11 {
		t.Errorf("measurement %+v", m)
	}
	s.log = nil
	_ = d.StartContinuous(0)
	if !bytes.Equal(s.log[len(s.log)-1].data, []byte{0x00, 0x02}) || !s.logged(0x91, 0x3C) {
		t.Error("back-to-back start")
	}
	if ok, _ := d.DataReady(); !ok {
		t.Error("DataReady")
	}
	if got, _ := d.ReadContinuous(); got != 250 {
		t.Errorf("ReadContinuous = %d", got)
	}
	_ = d.StopContinuous()
	stop := [][]byte{{0x00, 0x01}, {0xFF, 0x01}, {0x00, 0x00}, {0x91, 0x00}, {0x00, 0x01}, {0xFF, 0x00}}
	for i, w := range stop {
		if got := s.log[len(s.log)-6+i].data; !bytes.Equal(got, w) {
			t.Errorf("stop write %d = %x", i, got)
		}
	}
	_ = d.StartContinuous(100)
	if got := []byte{s.regs[0x04], s.regs[0x05], s.regs[0x06], s.regs[0x07]}; !bytes.Equal(got, []byte{0, 0, 0x06, 0x40}) {
		t.Errorf("timed period %x", got)
	}
	if !bytes.Equal(s.log[len(s.log)-1].data, []byte{0x00, 0x04}) {
		t.Error("timed start")
	}
	_ = d.StopContinuous()
}

func TestVL53L0XTimingVcselProfiles(t *testing.T) {
	s := newSim()
	d, err := NewVL53L0XFull(s)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := d.TimingBudget(); b < 32000 || b > 34000 {
		t.Errorf("default budget %d", b)
	}
	_ = d.SetTimingBudget(50000)
	if b, _ := d.TimingBudget(); !near(b, 50000, 50) {
		t.Errorf("budget roundtrip %d", b)
	}
	if err := d.SetTimingBudget(19999); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("budget below minimum accepted")
	}
	_ = d.SetSignalRateLimit(0.1)
	if s.reg16(0x44) != 13 {
		t.Errorf("signal rate raw %d", s.reg16(0x44))
	}
	if v, _ := d.SignalRateLimit(); v != 13.0/128 {
		t.Errorf("SignalRateLimit = %v", v)
	}
	if p, _ := d.VcselPulsePeriod(VL53L0XPreRange); p != 14 {
		t.Errorf("pre default %d", p)
	}
	if err := d.SetVcselPulsePeriod(VL53L0XPreRange, 18); err != nil {
		t.Fatal(err)
	}
	if p, _ := d.VcselPulsePeriod(VL53L0XPreRange); p != 18 || s.regs[0x57] != 0x50 {
		t.Errorf("pre 18: %d", p)
	}
	if err := d.SetVcselPulsePeriod(VL53L0XFinalRange, 14); err != nil {
		t.Fatal(err)
	}
	if p, _ := d.VcselPulsePeriod(VL53L0XFinalRange); p != 14 || s.regs[0x48] != 0x48 || s.regs[0x30] != 0x07 ||
		s.pages[[2]uint8{1, 0x30}] != 0x20 || s.regs[0x01] != 0xE8 {
		t.Errorf("final 14: %d", p)
	}
	if b, _ := d.TimingBudget(); !near(b, 50000, 300) {
		t.Errorf("budget after VCSEL change %d", b)
	}
	if err := d.SetVcselPulsePeriod(VL53L0XPreRange, 13); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("odd period accepted")
	}
	_ = d.SetProfile(VL53L0XProfileHighSpeed)
	if b, _ := d.TimingBudget(); !near(b, 20000, 50) || s.reg16(0x44) != 32 {
		t.Errorf("high speed budget %d", b)
	}
	_ = d.SetProfile(VL53L0XProfileLongRange)
	if p, _ := d.VcselPulsePeriod(VL53L0XPreRange); p != 18 || s.reg16(0x44) != 13 {
		t.Errorf("long range pre %d", p)
	}
}

func TestVL53L0XOffsetThresholdsInterrupts(t *testing.T) {
	s := newSim()
	d, err := NewVL53L0XFull(s)
	if err != nil {
		t.Fatal(err)
	}
	_ = d.SetOffset(-10.25)
	if s.reg16(0x28) != uint16(-41&0x0FFF) {
		t.Errorf("offset raw 0x%03X", s.reg16(0x28))
	}
	if v, _ := d.Offset(); v != -10.25 {
		t.Errorf("Offset = %v", v)
	}
	if err := d.SetOffset(512); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("offset range")
	}
	_ = d.SetCrosstalkCompensation(0.5)
	if s.reg16(0x20) != 4096 {
		t.Errorf("crosstalk raw %d", s.reg16(0x20))
	}
	s.log = nil
	if err := d.Recalibrate(); err != nil || !s.logged(0x00, 0x41) || !s.logged(0x01, 0x02) || s.regs[0x01] != 0xE8 {
		t.Errorf("Recalibrate: %v", err)
	}
	_ = d.SetInterruptThresholds(100, 801)
	if s.reg16(0x0E) != 50 || s.reg16(0x0C) != 400 {
		t.Error("threshold encoding")
	}
	if lo, hi, _ := d.InterruptThresholds(); lo != 100 || hi != 800 {
		t.Errorf("thresholds %d %d", lo, hi)
	}
	if err := d.SetInterruptThresholds(500, 100); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("threshold order")
	}
	_ = d.SetAddress(0x30)
	if s.regs[0x8A] != 0x30 {
		t.Error("SetAddress")
	}
	if err := d.SetAddress(0x78); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("address range")
	}
	if id, _ := d.ModelID(); id != 0xEE {
		t.Error("ModelID")
	}
	if id, _ := d.RevisionID(); id != 0x10 {
		t.Error("RevisionID")
	}
	_ = d.EnableInterrupt(VL53L0XSourceOutOfWindow)
	_ = d.DisableInterrupt(VL53L0XSourceLevelLow)
	if s.regs[0x0A] != 0x03 {
		t.Error("inactive source disabled")
	}
	_ = d.DisableInterrupt(VL53L0XSourceOutOfWindow)
	if s.regs[0x0A] != 0x00 {
		t.Error("active source not disabled")
	}
	s.regs[0x13] = 0x03 | 0x08
	if st, _ := d.PollInterrupt(); st != VL53L0XSourceOutOfWindow || s.regs[0x13] != 0 {
		t.Errorf("PollInterrupt = %d", st)
	}
	if st, _ := d.PollInterrupt(); st != 0 {
		t.Error("PollInterrupt not cleared")
	}
	if err := d.EnableInterrupt(5); !errors.Is(err, ErrVL53L0XInvalidArgument) {
		t.Error("source range")
	}
}

func TestVL53L0XTimeout(t *testing.T) {
	s := newSim()
	d, err := NewVL53L0XFull(s)
	if err != nil {
		t.Fatal(err)
	}
	_ = d.StartContinuous(0)
	s.continuous = false
	s.regs[0x13] = 0
	if _, err := d.ReadContinuous(); !errors.Is(err, ErrVL53L0XTimeout) {
		t.Errorf("want timeout, got %v", err)
	}
}
