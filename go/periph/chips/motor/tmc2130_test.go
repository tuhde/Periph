package motor

import (
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// tmc2130Sim is an in-memory TMC2130 implementing connection.Connection with
// the pipelined 40-bit datagram protocol: every reply carries the register
// requested by (or the data written in) the previous datagram.
type tmc2130Sim struct {
	regs       map[uint8]uint32
	frames     [][]byte
	writes     []tmc2130RegWrite
	pending    uint32
	mirrorBad  bool
	gstatReads int
}

type tmc2130RegWrite struct {
	reg   uint8
	value uint32
}

func newTMC2130Sim() *tmc2130Sim {
	return &tmc2130Sim{regs: map[uint8]uint32{0x04: 0x11000040, 0x01: 0x01}}
}

func (m *tmc2130Sim) datagram(frame []byte) uint32 {
	f := make([]byte, 5)
	copy(f, frame)
	m.frames = append(m.frames, f)
	reply := m.pending
	reg := f[0] & 0x7F
	if f[0]&0x80 != 0 {
		v := uint32(f[1])<<24 | uint32(f[2])<<16 | uint32(f[3])<<8 | uint32(f[4])
		m.regs[reg] = v
		m.writes = append(m.writes, tmc2130RegWrite{reg, v})
		if m.mirrorBad {
			m.pending = 0
		} else {
			m.pending = v
		}
	} else {
		if reg == 0x01 {
			m.gstatReads++
		}
		m.pending = m.regs[reg]
		if reg == 0x01 {
			m.regs[0x01] = 0
		}
	}
	return reply
}

func (m *tmc2130Sim) Write(data []byte) error {
	if len(data) != 5 {
		panic("TMC2130 write must be a 5-byte frame")
	}
	m.datagram(data)
	return nil
}

func (m *tmc2130Sim) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *tmc2130Sim) WriteRead(data []byte, n int) ([]byte, error) {
	if n != 4 {
		panic("TMC2130 read must clock 4 bytes")
	}
	r := m.datagram(data)
	return []byte{byte(r >> 24), byte(r >> 16), byte(r >> 8), byte(r)}, nil
}

func (m *tmc2130Sim) Close() error                { return nil }
func (m *tmc2130Sim) Enable()                     {}
func (m *tmc2130Sim) Disable()                    {}
func (m *tmc2130Sim) IsEnabled() bool             { return true }
func (m *tmc2130Sim) IntPin() connection.InputPin { return nil }
func (m *tmc2130Sim) EnPin() connection.OutputPin { return nil }

func (m *tmc2130Sim) last(t *testing.T, reg uint8) uint32 {
	t.Helper()
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg {
			return m.writes[i].value
		}
	}
	t.Fatalf("register 0x%02X was never written", reg)
	return 0
}

type tmc2130Pin struct{ levels []bool }

func (p *tmc2130Pin) Set(high bool) error {
	p.levels = append(p.levels, high)
	return nil
}

func (p *tmc2130Pin) highs() int {
	n := 0
	for _, l := range p.levels {
		if l {
			n++
		}
	}
	return n
}

type tmc2130InPin struct{ high bool }

func (p *tmc2130InPin) OnEdge(connection.Trigger, func()) func() { return func() {} }
func (p *tmc2130InPin) IsHigh() (bool, error)                    { return p.high, nil }

func newTMC2130Minimal(t *testing.T) (*tmc2130Sim, *tmc2130Pin, *tmc2130Pin, *TMC2130Minimal) {
	t.Helper()
	sim := newTMC2130Sim()
	step, dir := &tmc2130Pin{}, &tmc2130Pin{}
	d, err := NewTMC2130Minimal(sim, step, dir, 0.11, nil)
	if err != nil {
		t.Fatal(err)
	}
	return sim, step, dir, d
}

func TestTMC2130Init(t *testing.T) {
	sim, _, _, _ := newTMC2130Minimal(t)
	found := false
	for _, f := range sim.frames {
		if string(f) == string([]byte{0xEC, 0x14, 0x03, 0x00, 0xC3}) {
			found = true
		}
	}
	if !found {
		t.Error("CHOPCONF datagram EC 14 03 00 C3 not sent")
	}
	if sim.last(t, 0x11) != 10 || sim.last(t, 0x00) != 4 || sim.last(t, 0x13) != 500 || sim.last(t, 0x70) != 0x000401C8 {
		t.Error("init register values")
	}
	ih := sim.last(t, 0x10)
	if ih>>8&0x1F != 15 || ih&0x1F != 5 || ih>>16&0xF != 6 {
		t.Errorf("IHOLD_IRUN = 0x%X", ih)
	}
	if sim.gstatReads != 1 {
		t.Errorf("GSTAT read %d times, want 1", sim.gstatReads)
	}
}

func TestTMC2130InitFailures(t *testing.T) {
	for name, setup := range map[string]func(*tmc2130Sim){
		"version": func(s *tmc2130Sim) { s.regs[0x04] = 0 },
		"bit6":    func(s *tmc2130Sim) { s.regs[0x04] = 0x11000000 },
	} {
		s := newTMC2130Sim()
		setup(s)
		if _, err := NewTMC2130Minimal(s, &tmc2130Pin{}, &tmc2130Pin{}, 0.11, nil); err != ErrTMC2130NotFound {
			t.Errorf("%s: want ErrTMC2130NotFound, got %v", name, err)
		}
	}
	s := newTMC2130Sim()
	s.mirrorBad = true
	if _, err := NewTMC2130Minimal(s, &tmc2130Pin{}, &tmc2130Pin{}, 0.11, nil); err != ErrTMC2130MirrorMismatch {
		t.Errorf("want mirror mismatch, got %v", err)
	}
}

func TestTMC2130CurrentMotionFaults(t *testing.T) {
	sim, step, dir, d := newTMC2130Minimal(t)
	d.SetCurrent(1.0)
	if sim.last(t, 0x10)>>8&0x1F != 17 || sim.last(t, 0x6C)&(1<<17) != 0 {
		t.Error("1.0 A -> CS 17, vsense 0")
	}
	d.SetCurrent(0.5)
	if sim.last(t, 0x10)>>8&0x1F != 15 || sim.last(t, 0x6C)&(1<<17) == 0 {
		t.Error("0.5 A -> CS 15, vsense 1")
	}
	if d.SetCurrent(1.5) != ErrTMC2130InvalidArgument {
		t.Error("1.5 A must be rejected")
	}
	if err := d.Move(2, 1e6); err != nil || step.highs() != 32 || !dir.levels[len(dir.levels)-1] {
		t.Errorf("move forward: highs=%d err=%v", step.highs(), err)
	}
	d.Move(-1, 1e6)
	if dir.levels[len(dir.levels)-1] || step.highs() != 48 {
		t.Error("move reverse")
	}
	if d.Move(1, 0) != ErrTMC2130InvalidArgument {
		t.Error("speed 0 must be rejected")
	}
	d.Disable()
	if sim.last(t, 0x6C)&0xF != 0 {
		t.Error("disable -> TOFF 0")
	}
	d.Enable()
	if sim.last(t, 0x6C)&0xF != 3 {
		t.Error("enable -> TOFF 3")
	}
	sim.regs[0x6F] = 1<<31 | 1<<26 | 1<<28 | 1<<30
	if st, _ := d.IsStandstill(); !st {
		t.Error("expected standstill")
	}
	if f, _ := d.ReadFault(); f != TMC2130FaultOvertempWarning|TMC2130FaultShortGND|TMC2130FaultOpenLoad {
		t.Errorf("fault = 0x%02X", f)
	}
}

func TestTMC2130ENPin(t *testing.T) {
	pin := &tmc2130Pin{}
	d, err := NewTMC2130Minimal(newTMC2130Sim(), &tmc2130Pin{}, &tmc2130Pin{}, 0.11, pin)
	if err != nil {
		t.Fatal(err)
	}
	if len(pin.levels) != 1 || !pin.levels[0] {
		t.Errorf("ENN should be driven high at init: %v", pin.levels)
	}
	d.Enable()
	if pin.levels[len(pin.levels)-1] {
		t.Error("Enable should drive ENN low")
	}
}

func newTMC2130Full(t *testing.T) (*tmc2130Sim, *tmc2130Pin, *tmc2130Pin, *TMC2130Full) {
	t.Helper()
	sim := newTMC2130Sim()
	step, dir := &tmc2130Pin{}, &tmc2130Pin{}
	d, err := NewTMC2130Full(sim, step, dir, 0.11, nil, &tmc2130InPin{high: true}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return sim, step, dir, d
}

func TestTMC2130FullConfiguration(t *testing.T) {
	sim, step, dir, d := newTMC2130Full(t)
	d.SetMicrosteps(256)
	if sim.last(t, 0x6C)>>24&0xF != 0 {
		t.Error("256 -> MRES 0")
	}
	d.SetMicrosteps(1)
	if sim.last(t, 0x6C)>>24&0xF != 8 {
		t.Error("1 -> MRES 8")
	}
	if d.SetMicrosteps(3) != ErrTMC2130InvalidArgument {
		t.Error("3 microsteps must be rejected")
	}
	d.SetMicrosteps(32)
	d.Move(1, 1e6)
	if step.highs() != 32 {
		t.Error("move uses microsteps")
	}
	d.SetChopperMode(true)
	if sim.last(t, 0x00)&4 != 0 {
		t.Error("spreadcycle clears en_pwm_mode")
	}
	d.SetChopperMode(false)
	if sim.last(t, 0x00)&4 != 4 {
		t.Error("stealthchop sets en_pwm_mode")
	}
	d.SetDirectionInverted(true)
	if sim.last(t, 0x00)&0x10 == 0 {
		t.Error("shaft")
	}
	d.ConfigureDiag(TMC2130DiagConfig{Diag0Stall: true, Diag1Index: true, Diag0PushPull: true})
	g := sim.last(t, 0x00)
	if g>>7&1 != 1 || g>>9&1 != 1 || g>>12&1 != 1 || g>>5&1 != 0 || g&0x14 != 0x14 {
		t.Errorf("diag bits GCONF = 0x%X", g)
	}
	d.SetVsense(false)
	d.SetCurrent(0.5)
	if sim.last(t, 0x10)>>8&0x1F != 8 || sim.last(t, 0x6C)>>17&1 != 0 {
		t.Error("manual vsense must be kept")
	}
	d.SetHoldCurrent(0.2)
	if sim.last(t, 0x10)&0x1F != 3 {
		t.Error("hold current")
	}
	d.SetCurrentDelay(2, 100)
	if sim.last(t, 0x10)>>16&0xF != 2 || sim.last(t, 0x11) != 100 {
		t.Error("current delay")
	}
	d.ConfigureChopper(4, 1, 3, 2)
	c := sim.last(t, 0x6C)
	if c&0xF != 4 || c>>15&3 != 1 || c>>4&7 != 3 || c>>7&0xF != 2 || c>>14&1 != 0 {
		t.Errorf("configure chopper CHOPCONF = 0x%X", c)
	}
	d.ConfigureConstantOffTime(5, 2, 11, -3, true, true)
	c = sim.last(t, 0x6C)
	if c&0xF != 5 || c>>14&1 != 1 || c>>4&7 != 3 || c>>11&1 != 1 || c>>7&0xF != 0 || c>>12&3 != 3 {
		t.Errorf("constant off time CHOPCONF = 0x%X", c)
	}
	d.ConfigureStealthchop(true, false, 40, 5, 1)
	d.SetFreewheel(1)
	p := sim.last(t, 0x70)
	if p&0xFF != 40 || p>>8&0xFF != 5 || p>>16&3 != 1 || p>>20&3 != 1 || p>>18&1 != 1 {
		t.Errorf("PWMCONF = 0x%X", p)
	}
	d.SetStallThreshold(-5)
	d.SetStallguardFilter(true)
	d.ConfigureCoolstep(5, 2, 1, 1, 0)
	cc := sim.last(t, 0x6D)
	if cc&0xF != 5 || cc>>16&0x7F != uint32(0x7B) || cc>>24&1 != 1 || cc>>8&0xF != 2 {
		t.Errorf("COOLCONF = 0x%X", cc)
	}
	if d.SetStallThreshold(64) != ErrTMC2130InvalidArgument || d.ConfigureCoolstep(16, 0, 0, 0, 0) != ErrTMC2130InvalidArgument ||
		d.SetCoolstepThreshold(1<<20) != ErrTMC2130InvalidArgument {
		t.Error("range checks")
	}
	d.SetCoolstepThreshold(500)
	if sim.last(t, 0x14) != 500 {
		t.Error("TCOOLTHRS")
	}
	if d.SetCoilCurrents(1, 1) != ErrTMC2130DirectModeNeeded {
		t.Error("XDIRECT needs direct mode")
	}
	d.SetDirectMode(true)
	d.SetCoilCurrents(100, -100)
	if want := uint32(100) | (uint32(0x1FFFF9C)&0x1FF)<<16; sim.last(t, 0x2D) != want {
		t.Errorf("XDIRECT = 0x%X, want 0x%X", sim.last(t, 0x2D), want)
	}
	d.SetDcstep(1000, 200, 3)
	if sim.last(t, 0x33) != 1000 || sim.last(t, 0x6E) != 200|3<<16 {
		t.Error("DcStep")
	}
	d.WriteMicrostepTable([8]uint32{1, 2, 3, 4, 5, 6, 7, 8}, 0x12345678, 0x00F70000)
	if sim.last(t, 0x60) != 1 || sim.last(t, 0x67) != 8 || sim.last(t, 0x68) != 0x12345678 || sim.last(t, 0x69) != 0x00F70000 {
		t.Error("microstep table")
	}
	d.SetInternalRsense(true)
	if d.SetCurrent(0.5) != ErrTMC2130Unsupported {
		t.Error("internal Rsense must reject SetCurrent")
	}
	d.SetInternalRsense(false)
	d.Step(3, time.Microsecond)
	if !dir.levels[len(dir.levels)-1] || step.highs() != 35 {
		t.Error("step forward")
	}
	d.Step(-2, time.Microsecond)
	if dir.levels[len(dir.levels)-1] {
		t.Error("step reverse")
	}
}

func TestTMC2130FullReadbacks(t *testing.T) {
	sim, _, _, d := newTMC2130Full(t)
	sim.regs[0x6A] = 513
	sim.regs[0x6B] = 0x1FF<<16 | 0x100
	sim.regs[0x12] = 1234
	sim.regs[0x71] = 77
	sim.regs[0x73] = 0x123456
	sim.regs[0x6F] = 1<<31 | 1<<15 | 17<<16 | 1<<25 | 1<<24 | 300
	sim.regs[0x04] = 0x11000000 | 0x40 | 0x1 | 0x10
	sim.regs[0x01] = 0x03
	if v, _ := d.MicrostepPosition(); v != 513 {
		t.Error("MSCNT")
	}
	if a, b, _ := d.ReadCoilCurrents(); a != -1 || b != -256 {
		t.Errorf("coil currents = %d, %d", a, b)
	}
	if v, _ := d.ReadTstep(); v != 1234 {
		t.Error("TSTEP")
	}
	if v, _ := d.ReadPwmScale(); v != 77 {
		t.Error("PWM_SCALE")
	}
	if v, _ := d.ReadLostSteps(); v != 0x23456 {
		t.Errorf("LOST_STEPS = 0x%X", v)
	}
	if v, _ := d.ReadCurrentScale(); v != 17 {
		t.Error("CS_ACTUAL")
	}
	if v, _ := d.ReadStallguard(); v != 300 {
		t.Error("SG_RESULT")
	}
	if v, _ := d.IsStalled(); !v {
		t.Error("stalled")
	}
	st, _ := d.ReadStatus()
	if !st.Standstill || !st.FsActive || !st.OT || !st.StallGuard || st.OTPW || st.CSActual != 17 || st.SGResult != 300 {
		t.Errorf("status = %+v", st)
	}
	gs, _ := d.ReadGlobalStatus()
	if !gs.Reset || !gs.DrvErr || gs.UVCP || sim.regs[0x01] != 0 {
		t.Errorf("gstat = %+v", gs)
	}
	io, _ := d.ReadIoin()
	if io.Version != 0x11 || !io.Step || !io.Enn || io.Dir {
		t.Errorf("ioin = %+v", io)
	}
	if v, err := d.ReadRegister(0x6A); err != nil || v != 513 {
		t.Error("read register")
	}
	if _, err := d.ReadRegister(0x10); err != ErrTMC2130InvalidArgument {
		t.Error("write-only register must be rejected")
	}
	d.WriteRegister(0x13, 5, true)
	if sim.last(t, 0x13) != 5 || d.WriteRegister(0x72, 1, false) != ErrTMC2130InvalidArgument {
		t.Error("write register")
	}
	if v, err := d.ReadDiag(0); err != nil || !v {
		t.Error("DIAG0")
	}
	if _, err := d.ReadDiag(1); err != ErrTMC2130NoPin {
		t.Error("unwired DIAG1")
	}
	if _, err := d.ReadDiag(2); err != ErrTMC2130InvalidArgument {
		t.Error("DIAG index range")
	}
}
