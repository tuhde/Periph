package tof

import (
	"errors"
	"math"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// l6180xSim is a VL6180X simulator implementing RegisterConnection: starting a
// measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
type l6180xSim struct {
	regs map[uint32]uint8
	log  [][2]uint32 // (reg, first data byte) of every WriteReg
	n    int
}

func newL6180xSim(fresh uint8) *l6180xSim {
	s := &l6180xSim{regs: map[uint32]uint8{}}
	s.set(0x000, 0xB4, 0x01, 0x03)
	s.set(0x016, fresh)
	s.set(0x062, 42)
	s.set(0x050, 0x01, 0x90) // ALS 400 counts
	return s
}

func (s *l6180xSim) set(reg uint32, values ...uint8) {
	for i, v := range values {
		s.regs[reg+uint32(i)] = v
	}
}

func (s *l6180xSim) writesTo(reg uint32) []uint8 {
	var out []uint8
	for _, w := range s.log {
		if w[0] == reg {
			out = append(out, uint8(w[1]))
		}
	}
	return out
}

func (s *l6180xSim) last(reg uint32) int {
	w := s.writesTo(reg)
	if len(w) == 0 {
		return -1
	}
	return int(w[len(w)-1])
}

func (s *l6180xSim) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	for i := range out {
		out[i] = s.regs[reg+uint32(i)]
	}
	return out, nil
}

func (s *l6180xSim) WriteReg(reg uint32, data []byte) error {
	s.log = append(s.log, [2]uint32{reg, uint32(data[0])})
	for i, v := range data {
		s.regs[reg+uint32(i)] = v
	}
	switch {
	case reg == 0x018 && data[0]&1 != 0:
		s.regs[0x04F] = s.regs[0x04F]&^0x07 | 4
	case reg == 0x038 && data[0]&1 != 0:
		s.regs[0x04F] = s.regs[0x04F]&^0x38 | 4<<3
	case reg == 0x015:
		s.regs[0x04F] = 0
	case reg == 0x02E:
		s.regs[0x02E] = 0
	}
	return nil
}

func (s *l6180xSim) Write(data []byte) error                   { return errors.New("unused") }
func (s *l6180xSim) Read(n int) ([]byte, error)                { return nil, errors.New("unused") }
func (s *l6180xSim) WriteRead(d []byte, n int) ([]byte, error) { return nil, errors.New("unused") }
func (s *l6180xSim) Close() error                              { return nil }
func (s *l6180xSim) Enable()                                   {}
func (s *l6180xSim) Disable()                                  {}
func (s *l6180xSim) IsEnabled() bool                           { return true }
func (s *l6180xSim) IntPin() connection.InputPin               { return nil }
func (s *l6180xSim) EnPin() connection.OutputPin               { return nil }

func TestVL6180XInit(t *testing.T) {
	sim := newL6180xSim(1)
	if _, err := NewVL6180XMinimal(sim); err != nil {
		t.Fatal(err)
	}
	if len(sim.writesTo(0x0207)) != 1 || sim.writesTo(0x0207)[0] != 0x01 {
		t.Error("private 0x0207 not written")
	}
	if sim.last(0x00DB) != 0xCE {
		t.Error("private 0x00DB not written")
	}
	if sim.last(0x016) != 0 {
		t.Error("fresh flag not cleared")
	}
	for _, c := range []struct {
		reg  uint32
		want int
	}{{0x011, 0x10}, {0x10A, 0x30}, {0x03F, 0x46}, {0x031, 0xFF}, {0x014, 0x24}, {0x01B, 0x09}, {0x03E, 0x31}} {
		if sim.last(c.reg) != c.want {
			t.Errorf("reg 0x%03X = 0x%02X, want 0x%02X", c.reg, sim.last(c.reg), c.want)
		}
	}
	if sim.regs[0x040] != 0x00 || sim.regs[0x041] != 0x63 {
		t.Error("integration period not 100 ms")
	}
}

func TestVL6180XInitSkipsPrivateWhenNotFresh(t *testing.T) {
	sim := newL6180xSim(0)
	if _, err := NewVL6180XMinimal(sim); err != nil {
		t.Fatal(err)
	}
	if len(sim.writesTo(0x0207)) != 0 {
		t.Error("private settings written although not fresh")
	}
	if sim.last(0x014) != 0x24 {
		t.Error("recommended settings missing")
	}
}

func TestVL6180XReadRange(t *testing.T) {
	sim := newL6180xSim(1)
	d, _ := NewVL6180XMinimal(sim)
	mm, err := d.ReadRange()
	if err != nil || mm != 42 {
		t.Fatalf("ReadRange = %d, %v", mm, err)
	}
	if sim.last(0x018) != 0x01 || sim.last(0x015) != 0x07 {
		t.Error("start/clear not written")
	}
	sim.set(0x04D, 0x70)
	if _, err := d.ReadRange(); !errors.Is(err, ErrVL6180XRange) || d.LastError() != 7 {
		t.Errorf("range error: %v, code %d", err, d.LastError())
	}
}

func TestVL6180XFull(t *testing.T) {
	sim := newL6180xSim(1)
	d, err := NewVL6180XFull(sim)
	if err != nil {
		t.Fatal(err)
	}
	sim.set(0x04D, 0x60)
	if mm, code, err := d.ReadRangeStatus(); err != nil || mm != 42 || code != 6 {
		t.Errorf("ReadRangeStatus = %d %d %v", mm, code, err)
	}
	sim.set(0x04D, 0x00)

	if raw, err := d.ReadAlsRaw(); err != nil || raw != 400 {
		t.Errorf("ReadAlsRaw = %d %v", raw, err)
	}
	if lux, _ := d.ReadLux(); math.Abs(float64(lux)-0.32*400/1.01) > 1e-3 {
		t.Errorf("lux = %f", lux)
	}
	_ = d.SetAlsGain(0)
	if sim.last(0x03F) != 0x40 {
		t.Error("gain not written")
	}
	_ = d.SetAlsIntegrationTime(50)
	if sim.regs[0x040] != 0x00 || sim.regs[0x041] != 0x31 {
		t.Error("integration code")
	}
	if lux, _ := d.ReadLux(); math.Abs(float64(lux)-0.32*(400/20.0)*2) > 1e-3 {
		t.Errorf("lux gain 20 / 50 ms = %f", lux)
	}
	hold := sim.writesTo(0x017)
	if len(hold) < 2 || hold[len(hold)-2] != 1 || hold[len(hold)-1] != 0 {
		t.Error("grouped hold not toggled")
	}
	for name, err := range map[string]error{
		"gain": d.SetAlsGain(8), "integration": d.SetAlsIntegrationTime(513), "scaler": d.SetAlsScaler(17),
		"period": d.StartContinuousRange(5), "als period": d.StartContinuousAls(2560),
		"range thresholds": d.SetRangeThresholds(90, 80), "als thresholds": d.SetAlsThresholds(2, 1),
		"convergence": d.SetMaxConvergenceTime(64), "crosstalk": d.SetCrosstalkRate(-1),
		"address": d.SetAddress(0x80), "source": d.EnableInterrupt(VL6180XSourceError, 1),
		"mode": d.EnableInterrupt(VL6180XSourceRange, 5),
	} {
		if !errors.Is(err, ErrVL6180XInvalidArgument) {
			t.Errorf("%s: want invalid argument, got %v", name, err)
		}
	}
	_ = d.SetAlsScaler(4)
	if sim.last(0x120) != 4 {
		t.Error("scaler")
	}

	_ = d.StartContinuousRange(50)
	if sim.last(0x01B) != 0x04 || sim.last(0x018) != 0x03 {
		t.Error("continuous range registers")
	}
	sim.regs[0x04F] = 4
	if mm, err := d.ReadRangeContinuous(); err != nil || mm != 42 {
		t.Errorf("ReadRangeContinuous = %d %v", mm, err)
	}
	_ = d.StopContinuousRange()
	if sim.last(0x018) != 0x01 {
		t.Error("stop")
	}
	_ = d.StartContinuousAls(100)
	if sim.last(0x03E) != 0x09 || sim.last(0x038) != 0x03 {
		t.Error("continuous als registers")
	}
	_ = d.StopContinuousAls()
	if sim.last(0x038) != 0x01 {
		t.Error("als stop")
	}
	_ = d.SetInterleaved(true)
	if sim.last(0x2A3) != 1 {
		t.Error("interleaved on")
	}
	_ = d.SetInterleaved(false)
	if sim.last(0x2A3) != 0 {
		t.Error("interleaved off")
	}

	_ = d.SetRangeThresholds(30, 80)
	if sim.last(0x019) != 80 || sim.last(0x01A) != 30 {
		t.Error("range thresholds")
	}
	_ = d.SetAlsThresholds(100, 5000)
	if sim.regs[0x03A] != 0x13 || sim.regs[0x03B] != 0x88 || sim.regs[0x03C] != 0 || sim.regs[0x03D] != 100 {
		t.Error("als thresholds")
	}
	_ = d.SetMaxConvergenceTime(20)
	_ = d.SetAveragingPeriod(100)
	if sim.last(0x01C) != 20 || sim.last(0x10A) != 100 {
		t.Error("convergence / averaging")
	}
	_ = d.SetRangeOffset(-5)
	if sim.last(0x024) != 0xFB {
		t.Error("offset written")
	}
	if v, _ := d.GetRangeOffset(); v != -5 {
		t.Errorf("offset read = %d", v)
	}
	_ = d.SetCrosstalkRate(0.5)
	if sim.regs[0x01E] != 0x00 || sim.regs[0x01F] != 0x40 {
		t.Error("crosstalk 9.7")
	}
	if err := d.RecalibrateVHV(); err != nil || sim.last(0x02E) != 0x01 {
		t.Errorf("RecalibrateVHV: %v", err)
	}
	_ = d.SetAddress(0x30)
	if sim.last(0x212) != 0x30 {
		t.Error("address")
	}
	if id, _ := d.ModelID(); id != 0xB4 {
		t.Error("model id")
	}
	if major, minor, _ := d.Revision(); major != 1 || minor != 3 {
		t.Error("revision")
	}
	sim.set(0x052, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0)
	if h, _ := d.ReadHistory(); h[0] != 1 || h[6] != 7 || h[7] != 256 {
		t.Errorf("history = %v", h)
	}
}

func TestVL6180XInterrupts(t *testing.T) {
	sim := newL6180xSim(1)
	d, _ := NewVL6180XFull(sim)
	sim.regs[0x014] = 0x24
	_ = d.EnableInterrupt(VL6180XSourceRange, VL6180XModeOutOfWindow)
	if sim.last(0x014) != 0x23 {
		t.Errorf("range mode = 0x%02X", sim.last(0x014))
	}
	_ = d.EnableInterrupt(VL6180XSourceALS, VL6180XModeLevelLow)
	if sim.last(0x014) != 0x0B {
		t.Errorf("als mode = 0x%02X", sim.last(0x014))
	}
	_ = d.DisableInterrupt(VL6180XSourceRange)
	if sim.last(0x014) != 0x08 {
		t.Errorf("range disabled = 0x%02X", sim.last(0x014))
	}

	for _, c := range []struct{ status, want uint8 }{
		{0x04, VL6180XSourceRange}, {0x24, VL6180XSourceRange | VL6180XSourceALS}, {0x40, VL6180XSourceError}, {0x00, 0},
	} {
		sim.regs[0x04F] = c.status
		if got, err := d.PollInterrupt(); err != nil || got != c.want {
			t.Errorf("poll(0x%02X) = 0x%02X, %v, want 0x%02X", c.status, got, err, c.want)
		}
	}
	if sim.regs[0x04F] != 0 {
		t.Error("poll did not clear")
	}

	sim.regs[0x04F] = 0x04
	got := make(chan uint8, 4)
	if err := d.OnInterrupt(func(s uint8) { got <- s }); err != nil {
		t.Fatal(err)
	}
	select {
	case s := <-got:
		if s != VL6180XSourceRange {
			t.Errorf("callback status = 0x%02X", s)
		}
	case <-time.After(500 * time.Millisecond):
		t.Error("polling fallback delivered nothing")
	}
	_ = d.OffInterrupt()
}
