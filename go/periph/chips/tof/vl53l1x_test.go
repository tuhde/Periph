package tof

import (
	"bytes"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// l1xSim is a VL53L1X simulator implementing Connection with explicit 16-bit
// register indices. GPIO__TIO_HV_STATUS (0x0031) is computed: bit 0 is 0
// (active-low line asserted) while a result is pending. Starting a single-shot
// or timed ranging makes a result pending; the interrupt clear drops it unless
// timed ranging is running.
type l1xSim struct {
	regs    map[uint16]uint8
	log     [][]byte
	pending bool
	ranging bool
}

func newL1XSim() *l1xSim {
	s := &l1xSim{regs: map[uint16]uint8{}}
	s.set(0x00E5, 0x01)
	s.set(0x010F, 0xEA, 0xCC, 0x10)
	s.set(0x013E, 0x91)
	s.set(0x00DE, 0x00, 0x50)
	// Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
	s.set(0x0089, 9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80)
	return s
}

func (s *l1xSim) set(reg uint16, values ...uint8) {
	for i, v := range values {
		s.regs[reg+uint16(i)] = v
	}
}

func (s *l1xSim) reg16(reg uint16) uint16 { return uint16(s.regs[reg])<<8 | uint16(s.regs[reg+1]) }

func (s *l1xSim) writesTo(reg uint16) []byte {
	var out []byte
	for _, w := range s.log {
		if len(w) == 3 && uint16(w[0])<<8|uint16(w[1]) == reg {
			out = append(out, w[2])
		}
	}
	return out
}

func (s *l1xSim) Write(data []byte) error {
	s.log = append(s.log, append([]byte(nil), data...))
	reg := uint16(data[0])<<8 | uint16(data[1])
	for i, v := range data[2:] {
		s.regs[reg+uint16(i)] = v
	}
	if reg == 0x0087 && len(data) == 3 {
		if data[2] == 0x10 || data[2] == 0x40 {
			s.pending = true
			s.ranging = data[2] == 0x40
		} else {
			s.ranging = false
		}
	} else if reg == 0x0086 && data[2] == 0x01 {
		s.pending = s.ranging
	}
	return nil
}

func (s *l1xSim) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (s *l1xSim) WriteRead(data []byte, n int) ([]byte, error) {
	reg := uint16(data[0])<<8 | uint16(data[1])
	out := make([]byte, n)
	for i := range out {
		r := reg + uint16(i)
		if r == 0x0031 {
			if !s.pending {
				out[i] = 0x01
			}
		} else {
			out[i] = s.regs[r]
		}
	}
	return out, nil
}

func (s *l1xSim) Close() error                { return nil }
func (s *l1xSim) Enable()                     {}
func (s *l1xSim) Disable()                    {}
func (s *l1xSim) IsEnabled() bool             { return true }
func (s *l1xSim) IntPin() connection.InputPin { return nil }
func (s *l1xSim) EnPin() connection.OutputPin { return nil }

func newL1XFull(t *testing.T) (*l1xSim, *VL53L1XFull) {
	t.Helper()
	sim := newL1XSim()
	f, err := NewVL53L1XFull(sim)
	if err != nil {
		t.Fatalf("init: %v", err)
	}
	return sim, f
}

func TestVL53L1XRejectsWrongSensorIDAndBootTimeout(t *testing.T) {
	sim := newL1XSim()
	sim.set(0x0110, 0xCD)
	if _, err := NewVL53L1XMinimal(sim); !errors.Is(err, ErrVL53L1XNotFound) {
		t.Fatalf("want ErrVL53L1XNotFound, got %v", err)
	}
	sim = newL1XSim()
	sim.set(0x00E5, 0x00)
	if _, err := NewVL53L1XMinimal(sim); !errors.Is(err, ErrVL53L1XTimeout) {
		t.Fatalf("want ErrVL53L1XTimeout, got %v", err)
	}
}

func TestVL53L1XInitSequence(t *testing.T) {
	sim := newL1XSim()
	if _, err := NewVL53L1XMinimal(sim); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 91; i++ {
		w := sim.log[i]
		if len(w) != 3 || uint16(w[0])<<8|uint16(w[1]) != 0x2D+uint16(i) {
			t.Fatalf("config write %d = % x", i, w)
		}
	}
	if sim.writesTo(0x0046)[0] != 0x20 || sim.writesTo(0x0081)[0] != 0x9B {
		t.Error("config values")
	}
	if sim.regs[0x002E] != 0x01 || sim.regs[0x002F] != 0x01 || sim.regs[0x0030] != 0x11 {
		t.Error("2V8 / active-low overrides")
	}
	starts := sim.writesTo(0x0087)
	if !bytes.Equal(starts[len(starts)-2:], []byte{0x40, 0x00}) {
		t.Errorf("settling ranging %v", starts)
	}
	if sim.regs[0x0008] != 0x09 || sim.regs[0x000B] != 0x00 || sim.ranging {
		t.Error("VHV bounds / idle")
	}
}

func TestVL53L1XSingleShot(t *testing.T) {
	sim := newL1XSim()
	m, err := NewVL53L1XMinimal(sim)
	if err != nil {
		t.Fatal(err)
	}
	sim.log = nil
	if d, err := m.Distance(); err != nil || d != 250 || !m.RangeValid() {
		t.Fatalf("distance %d %v valid %v", d, err, m.RangeValid())
	}
	if !bytes.Equal(sim.log[0], []byte{0x00, 0x86, 0x01}) || !bytes.Equal(sim.log[1], []byte{0x00, 0x87, 0x10}) ||
		!bytes.Equal(sim.log[len(sim.log)-1], []byte{0x00, 0x86, 0x01}) {
		t.Error("single-shot sequence")
	}
	sim.set(0x0089, 4)
	if d, _ := m.Distance(); d != 250 || m.RangeValid() {
		t.Error("invalid status")
	}
}

func TestVL53L1XMeasurementBudgetMode(t *testing.T) {
	sim, f := newL1XFull(t)
	f.Distance()
	m, err := f.ReadMeasurement()
	want := VL53L1XMeasurement{250, 0, 5.0, 0.5, 10.0}
	if err != nil || m != want || f.RangeStatus() != 0 {
		t.Fatalf("measurement %+v %v", m, err)
	}
	sim.set(0x0089, 0x1F)
	if m, _ := f.ReadMeasurement(); m.RangeStatus != 255 {
		t.Error("status out of table")
	}
	sim.set(0x0089, 9)

	if b, _ := f.TimingBudget(); b != 100000 {
		t.Errorf("default budget %d", b)
	}
	if mode, _ := f.DistanceMode(); mode != VL53L1XDistanceModeLong {
		t.Error("default mode")
	}
	if err := f.SetTimingBudget(33000); err != nil || sim.reg16(0x005E) != 0x0060 || sim.reg16(0x0061) != 0x006E {
		t.Error("budget long 33")
	}
	if !errors.Is(f.SetTimingBudget(15000), ErrVL53L1XInvalidArgument) ||
		!errors.Is(f.SetTimingBudget(40000), ErrVL53L1XInvalidArgument) {
		t.Error("budget rejects")
	}
	if err := f.SetDistanceMode(VL53L1XDistanceModeShort); err != nil {
		t.Fatal(err)
	}
	if sim.regs[0x004B] != 0x14 || sim.regs[0x0060] != 0x07 || sim.regs[0x0063] != 0x05 || sim.regs[0x0069] != 0x38 ||
		sim.reg16(0x0078) != 0x0705 || sim.reg16(0x007A) != 0x0606 || sim.reg16(0x005E) != 0x00D6 {
		t.Error("short mode registers")
	}
	if err := f.SetTimingBudget(15000); err != nil || sim.reg16(0x005E) != 0x001D || sim.reg16(0x0061) != 0x0027 {
		t.Error("budget short 15")
	}
	if !errors.Is(f.SetDistanceMode(VL53L1XDistanceModeLong), ErrVL53L1XInvalidArgument) {
		t.Error("long rejects 15 ms")
	}
	f.SetTimingBudget(100000)
	f.SetDistanceMode(VL53L1XDistanceModeLong)
	if sim.regs[0x004B] != 0x0A || sim.reg16(0x0078) != 0x0F0D || sim.reg16(0x005E) != 0x01CC || sim.reg16(0x0061) != 0x01EA {
		t.Error("long mode registers")
	}
	sim.set(0x004B, 0x33)
	if _, err := f.DistanceMode(); !errors.Is(err, ErrVL53L1XUnknownDistanceMode) {
		t.Error("unknown mode")
	}
}

func TestVL53L1XContinuousAndThresholds(t *testing.T) {
	sim, f := newL1XFull(t)
	f.SetInterMeasurement(200)
	if raw := uint32(sim.reg16(0x006C))<<16 | uint32(sim.reg16(0x006E)); raw != 0x50*200*1075/1000 {
		t.Errorf("inter-measurement raw %d", raw)
	}
	if p, _ := f.InterMeasurement(); p != 200 {
		t.Errorf("inter-measurement %d", p)
	}
	if !errors.Is(f.SetInterMeasurement(0), ErrVL53L1XInvalidArgument) {
		t.Error("rejects 0")
	}
	sim.log = nil
	f.StartContinuous(0)
	if p, _ := f.InterMeasurement(); p != 100 || !bytes.Equal(sim.log[len(sim.log)-1], []byte{0x00, 0x87, 0x40}) {
		t.Error("continuous start")
	}
	if r, _ := f.DataReady(); !r {
		t.Error("data ready")
	}
	if d, err := f.ReadContinuous(); err != nil || d != 250 {
		t.Error("read continuous")
	}
	if r, _ := f.DataReady(); !r {
		t.Error("continuous stays ready")
	}
	f.StopContinuous()
	if !bytes.Equal(sim.log[len(sim.log)-1], []byte{0x00, 0x87, 0x00}) {
		t.Error("stop")
	}
	f.StartContinuous(50)
	if p, _ := f.InterMeasurement(); p != 100 {
		t.Error("period clamped")
	}
	f.StopContinuous()
	f.StartContinuous(500)
	if p, _ := f.InterMeasurement(); p != 500 {
		t.Error("period kept")
	}
	f.StopContinuous()
	if !errors.Is(f.StartContinuous(60001), ErrVL53L1XInvalidArgument) {
		t.Error("rejects period")
	}

	f.SetInterruptThresholds(100, 801)
	if sim.reg16(0x0074) != 100 || sim.reg16(0x0072) != 801 {
		t.Error("thresholds encode")
	}
	if lo, hi, _ := f.InterruptThresholds(); lo != 100 || hi != 801 {
		t.Error("thresholds decode")
	}
	if !errors.Is(f.SetInterruptThresholds(500, 100), ErrVL53L1XInvalidArgument) {
		t.Error("thresholds order")
	}
}

func TestVL53L1XSignalSigmaROIOffsetCrosstalk(t *testing.T) {
	sim, f := newL1XFull(t)
	if v, _ := f.SignalRateLimit(); v != 1.0 {
		t.Error("signal default")
	}
	f.SetSignalRateLimit(0.25)
	if sim.reg16(0x0066) != 32 || !errors.Is(f.SetSignalRateLimit(-1), ErrVL53L1XInvalidArgument) {
		t.Error("signal rate")
	}
	if v, _ := f.SigmaThreshold(); v != 90 {
		t.Error("sigma default")
	}
	f.SetSigmaThreshold(45)
	if sim.reg16(0x0064) != 180 || !errors.Is(f.SetSigmaThreshold(16384), ErrVL53L1XInvalidArgument) {
		t.Error("sigma")
	}

	if w, h, _ := f.ROI(); w != 16 || h != 16 {
		t.Error("roi default")
	}
	f.SetROICenter(167)
	f.SetROI(8, 8)
	if c, _ := f.ROICenter(); sim.regs[0x0080] != 0x77 || c != 167 {
		t.Error("roi small keeps centre")
	}
	f.SetROI(8, 16)
	if w, h, _ := f.ROI(); w != 8 || h != 16 {
		t.Error("roi 8x16")
	}
	if c, _ := f.ROICenter(); c != 199 {
		t.Error("roi recentred")
	}
	if !errors.Is(f.SetROI(3, 8), ErrVL53L1XInvalidArgument) {
		t.Error("roi rejects")
	}
	if c, _ := f.OpticalCenter(); c != 0x91 {
		t.Error("optical centre")
	}

	f.SetOffset(-10.25)
	if sim.reg16(0x001E) != uint16(int32(-41)&0x1FFF) || sim.reg16(0x0020) != 0 || sim.reg16(0x0022) != 0 {
		t.Error("offset encode")
	}
	if o, _ := f.Offset(); o != -10.25 {
		t.Error("offset decode")
	}
	f.SetOffset(700.5)
	if o, _ := f.Offset(); o != 700.5 {
		t.Error("offset positive")
	}
	if !errors.Is(f.SetOffset(1024), ErrVL53L1XInvalidArgument) {
		t.Error("offset rejects")
	}
	f.SetCrosstalkCompensation(0.01)
	if sim.reg16(0x0016) != 5120 || !errors.Is(f.SetCrosstalkCompensation(0.128), ErrVL53L1XInvalidArgument) {
		t.Error("crosstalk")
	}
}

func TestVL53L1XCalibrationAndRecalibrate(t *testing.T) {
	sim, f := newL1XFull(t)
	if o, err := f.CalibrateOffset(260); err != nil || o != 10 {
		t.Fatalf("calibrate offset %v %v", o, err)
	}
	if o, _ := f.Offset(); o != 10 || sim.ranging {
		t.Error("offset applied / stopped")
	}
	if x, _ := f.CalibrateCrosstalk(500); x != 0.127 {
		t.Errorf("crosstalk clamp %v", x)
	}
	sim.set(0x0098, 0x00, 0x20)
	if x, _ := f.CalibrateCrosstalk(500); math.Abs(float64(x)-0.0125) > 1e-6 || sim.reg16(0x0016) != 6400 {
		t.Errorf("crosstalk value %v", x)
	}
	if _, err := f.CalibrateCrosstalk(0); !errors.Is(err, ErrVL53L1XInvalidArgument) {
		t.Error("crosstalk rejects 0")
	}
	sim.log = nil
	if err := f.Recalibrate(); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(sim.writesTo(0x0008), []byte{0x81, 0x09}) || !bytes.Equal(sim.writesTo(0x000B), []byte{0x92, 0x00}) ||
		!bytes.Equal(sim.writesTo(0x0087), []byte{0x40, 0x00}) {
		t.Error("temperature update sequence")
	}
}

func TestVL53L1XAddressIdentificationInterrupts(t *testing.T) {
	sim, f := newL1XFull(t)
	f.SetAddress(0x30)
	if sim.regs[0x0001] != 0x30 || !errors.Is(f.SetAddress(0x78), ErrVL53L1XInvalidArgument) {
		t.Error("address")
	}
	if id, _ := f.ModelID(); id != 0xEA {
		t.Error("model id")
	}
	if mt, _ := f.ModuleType(); mt != 0xCC {
		t.Error("module type")
	}
	if rv, _ := f.RevisionID(); rv != 0x10 {
		t.Error("revision id")
	}

	f.EnableInterrupt(VL53L1XSourceOutOfWindow)
	if sim.regs[0x0046] != 0x02 {
		t.Error("enable out of window")
	}
	f.EnableInterrupt(VL53L1XSourceInWindow)
	f.DisableInterrupt(VL53L1XSourceLevelLow)
	if sim.regs[0x0046] != 0x03 {
		t.Error("disable inactive ignored")
	}
	f.DisableInterrupt(VL53L1XSourceInWindow)
	f.DisableInterrupt(VL53L1XSourceNewSampleReady)
	if sim.regs[0x0046] != 0x20 {
		t.Error("disable reverts to new sample")
	}
	sim.pending = false
	if s, _ := f.PollInterrupt(); s != 0 {
		t.Error("poll none")
	}
	f.EnableInterrupt(VL53L1XSourceOutOfWindow)
	sim.pending = true
	if s, _ := f.PollInterrupt(); s != VL53L1XSourceOutOfWindow || sim.pending {
		t.Error("poll value / clears")
	}
	if !errors.Is(f.EnableInterrupt(6), ErrVL53L1XInvalidArgument) {
		t.Error("enable rejects")
	}
}
