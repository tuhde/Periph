package motor

import (
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// tmc2208Mock is an in-memory TMC2208-style UART chip implementing
// connection.Connection: write datagrams update regs and IFCNT; read requests
// queue the reply; with echo every sent byte comes back.
type tmc2208Mock struct {
	echo        bool
	regs        map[uint8]uint32
	datagrams   [][]byte
	writes      []tmc2208RegWrite
	corruptNext bool
	rx          []byte
}

type tmc2208RegWrite struct {
	reg   uint8
	value uint32
}

func newTMC2208Mock(echo bool) *tmc2208Mock {
	return &tmc2208Mock{echo: echo, regs: map[uint8]uint32{0x06: 0x21000100, 0x02: 0}}
}

func (m *tmc2208Mock) Write(data []byte) error {
	d := append([]byte(nil), data...)
	m.datagrams = append(m.datagrams, d)
	if m.echo {
		m.rx = append(m.rx, d...)
	}
	if len(d) == 8 && d[2]&0x80 != 0 {
		reg := d[2] & 0x7F
		v := uint32(d[3])<<24 | uint32(d[4])<<16 | uint32(d[5])<<8 | uint32(d[6])
		m.regs[reg] = v
		m.writes = append(m.writes, tmc2208RegWrite{reg, v})
		m.regs[0x02] = (m.regs[0x02] + 1) & 0xFF
	} else if len(d) == 4 {
		reg := d[2] & 0x7F
		v := m.regs[reg]
		body := []byte{0x05, 0xFF, reg, byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v), 0}
		body[7] = TMC2208CRC8(body[:7])
		if m.corruptNext {
			body[7] ^= 0xFF
			m.corruptNext = false
		}
		m.rx = append(m.rx, body...)
	}
	return nil
}

func (m *tmc2208Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	k := copy(out, m.rx)
	m.rx = m.rx[k:]
	return out, nil
}

func (m *tmc2208Mock) WriteRead(data []byte, n int) ([]byte, error) {
	if err := m.Write(data); err != nil {
		return nil, err
	}
	return m.Read(n)
}

func (m *tmc2208Mock) Close() error                { return nil }
func (m *tmc2208Mock) Enable()                     {}
func (m *tmc2208Mock) Disable()                    {}
func (m *tmc2208Mock) IsEnabled() bool             { return true }
func (m *tmc2208Mock) IntPin() connection.InputPin { return nil }
func (m *tmc2208Mock) EnPin() connection.OutputPin { return nil }

func (m *tmc2208Mock) last(reg uint8) (uint32, bool) {
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg {
			return m.writes[i].value, true
		}
	}
	return 0, false
}

func (m *tmc2208Mock) mustLast(t *testing.T, reg uint8) uint32 {
	t.Helper()
	v, ok := m.last(reg)
	if !ok {
		t.Fatalf("register 0x%02X was never written", reg)
	}
	return v
}

type tmc2208Pin struct{ levels []bool }

func (p *tmc2208Pin) Set(high bool) error {
	p.levels = append(p.levels, high)
	return nil
}

func (p *tmc2208Pin) highs() int {
	n := 0
	for _, l := range p.levels {
		if l {
			n++
		}
	}
	return n
}

func TestTMC2208CRCVectors(t *testing.T) {
	if got := TMC2208CRC8([]byte{5, 0, 0x80, 0, 0, 1, 0xC1}); got != 0x7F {
		t.Errorf("write GCONF CRC = 0x%02X, want 0x7F", got)
	}
	if got := TMC2208CRC8([]byte{5, 0, 6}); got != 0x6F {
		t.Errorf("read IOIN CRC = 0x%02X, want 0x6F", got)
	}
}

func TestTMC2208MinimalInit(t *testing.T) {
	m := newTMC2208Mock(true)
	if _, err := NewTMC2208Minimal(m, 0.11, nil, true); err != nil {
		t.Fatal(err)
	}
	if m.mustLast(t, 0x00) != 0x1C1 || m.mustLast(t, 0x01) != 0x07 || m.mustLast(t, 0x11) != 20 || m.mustLast(t, 0x70) != 0xC10D0024 {
		t.Errorf("unexpected init writes: %+v", m.writes)
	}
	wantGconf := []byte{5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F}
	wantIoin := []byte{5, 0, 6, 0x6F}
	var g, i bool
	for _, d := range m.datagrams {
		if string(d) == string(wantGconf) {
			g = true
		}
		if string(d) == string(wantIoin) {
			i = true
		}
	}
	if !g || !i {
		t.Errorf("expected GCONF write and IOIN read datagrams (g=%v i=%v)", g, i)
	}
	chop := m.mustLast(t, 0x6C)
	if chop&0xF != 3 || chop>>15&3 != 2 || chop>>4&7 != 5 || chop>>24&0xF != 4 || chop>>28&1 != 1 {
		t.Errorf("CHOPCONF fields wrong: 0x%08X", chop)
	}
	ih := m.mustLast(t, 0x10)
	if ih>>8&0x1F != 9 || ih&0x1F != 2 || ih>>16&0xF != 6 {
		t.Errorf("IHOLD_IRUN wrong: 0x%08X", ih)
	}
}

func TestTMC2208CurrentAndMotion(t *testing.T) {
	m := newTMC2208Mock(true)
	d, err := NewTMC2208Minimal(m, 0.11, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	d.SetCurrent(1.0)
	if m.mustLast(t, 0x10)>>8&0x1F != 18 {
		t.Errorf("1.0 A -> CS %d, want 18", m.mustLast(t, 0x10)>>8&0x1F)
	}
	d.SetCurrent(5.0)
	if m.mustLast(t, 0x10)>>8&0x1F != 31 {
		t.Error("5.0 A should clamp to CS 31")
	}
	d.Run(200)
	if m.mustLast(t, 0x22) != 4474 {
		t.Errorf("run(200) VACTUAL = %d, want 4474", m.mustLast(t, 0x22))
	}
	d.Run(-200)
	if m.mustLast(t, 0x22) != uint32(0x1000000-4474) {
		t.Errorf("run(-200) VACTUAL = 0x%X", m.mustLast(t, 0x22))
	}
	d.Stop()
	if m.mustLast(t, 0x22) != 0 {
		t.Error("stop should write VACTUAL = 0")
	}
	d.Disable()
	if m.mustLast(t, 0x6C)&0xF != 0 {
		t.Error("disable without ENN pin should clear TOFF")
	}
	d.Enable()
	if m.mustLast(t, 0x6C)&0xF != 3 {
		t.Error("enable should restore TOFF = 3")
	}
}

func TestTMC2208StatusAndFaults(t *testing.T) {
	m := newTMC2208Mock(true)
	d, _ := NewTMC2208Minimal(m, 0.11, nil, true)
	m.regs[0x6F] = 1<<31 | 0x01 | 0x08 | 0x20
	if st, _ := d.IsStandstill(); !st {
		t.Error("expected standstill")
	}
	f, _ := d.ReadFault()
	if f != TMC2208FaultOvertempWarning|TMC2208FaultShortGND|TMC2208FaultShortSupply {
		t.Errorf("fault mask = 0x%02X", f)
	}
}

func TestTMC2208ENPinAndErrors(t *testing.T) {
	pin := &tmc2208Pin{}
	d, err := NewTMC2208Minimal(newTMC2208Mock(true), 0.11, pin, true)
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

	ne := newTMC2208Mock(false)
	if _, err := NewTMC2208Minimal(ne, 0.11, nil, false); err != nil {
		t.Fatal(err)
	}
	if ne.mustLast(t, 0x00) != 0x1C1 {
		t.Error("no-echo init failed")
	}

	bad := newTMC2208Mock(true)
	bad.regs[0x06] = 0
	if _, err := NewTMC2208Minimal(bad, 0.11, nil, true); err != ErrTMC2208NotFound {
		t.Errorf("want ErrTMC2208NotFound, got %v", err)
	}

	flaky := newTMC2208Mock(true)
	flaky.corruptNext = true
	if _, err := NewTMC2208Minimal(flaky, 0.11, nil, true); err != nil {
		t.Errorf("a single CRC error must be retried: %v", err)
	}
}

func TestTMC2208FullConfiguration(t *testing.T) {
	m := newTMC2208Mock(true)
	step, dir := &tmc2208Pin{}, &tmc2208Pin{}
	d, err := NewTMC2208Full(m, 0.11, nil, true, step, dir, 12000000, false)
	if err != nil {
		t.Fatal(err)
	}
	d.SetMicrosteps(256)
	if m.mustLast(t, 0x6C)>>24&0xF != 0 {
		t.Error("256 microsteps -> MRES 0")
	}
	d.SetMicrosteps(1)
	if m.mustLast(t, 0x6C)>>24&0xF != 8 {
		t.Error("1 microstep -> MRES 8")
	}
	if d.SetMicrosteps(3) != ErrTMC2208InvalidArgument {
		t.Error("3 microsteps must be rejected")
	}
	d.SetMicrosteps(32)
	d.Run(100)
	if m.mustLast(t, 0x22) != 4474 {
		t.Errorf("run uses microsteps: VACTUAL = %d", m.mustLast(t, 0x22))
	}
	d.SetInterpolation(false)
	if m.mustLast(t, 0x6C)>>28&1 != 0 {
		t.Error("intpol off")
	}
	d.SetChopperMode(true)
	d.SetDirectionInverted(true)
	d.SetIndexOutput(1)
	g := m.mustLast(t, 0x00)
	if g&0x4 == 0 || g&0x8 == 0 || g&0x30 != 0x10 {
		t.Errorf("GCONF bits wrong: 0x%X", g)
	}
	d.SetDoubleEdge(true)
	d.SetDriverProtection(false, true)
	c := m.mustLast(t, 0x6C)
	if c>>29&1 != 1 || c>>31&1 != 1 || c>>30&1 != 0 {
		t.Errorf("CHOPCONF dedge/protection wrong: 0x%X", c)
	}
	d.SetVsense(true)
	if m.mustLast(t, 0x6C)>>17&1 != 1 {
		t.Error("vsense")
	}
	d.SetHoldCurrent(0.2)
	if m.mustLast(t, 0x10)&0x1F != 6 {
		t.Errorf("hold current CS = %d", m.mustLast(t, 0x10)&0x1F)
	}
	d.SetCurrentDelay(2, 100)
	if m.mustLast(t, 0x10)>>16&0xF != 2 || m.mustLast(t, 0x11) != 100 {
		t.Error("current delay")
	}
	d.SetStealthchopThreshold(300)
	if m.mustLast(t, 0x13) != 300 {
		t.Error("tpwmthrs")
	}
	d.ConfigureChopper(4, 1, 3, 2)
	c = m.mustLast(t, 0x6C)
	if c&0xF != 4 || c>>15&3 != 1 || c>>4&7 != 3 || c>>7&0xF != 2 {
		t.Errorf("configure chopper: 0x%X", c)
	}
	d.ConfigureStealthchop(true, false, 40, 5, 1, 4, 12)
	p := m.mustLast(t, 0x70)
	if p&0xFF != 40 || p>>8&0xFF != 5 || p>>16&3 != 1 || p>>18&1 != 1 || p>>19&1 != 0 || p>>24&0xF != 4 || p>>28 != 12 {
		t.Errorf("configure stealthchop: 0x%X", p)
	}
	d.SetFreewheel(1)
	if m.mustLast(t, 0x70)>>20&3 != 1 {
		t.Error("freewheel")
	}
	d.SetSendDelay(4)
	if m.mustLast(t, 0x03) != 4<<8 || d.SetSendDelay(16) != ErrTMC2208InvalidArgument {
		t.Error("send delay")
	}
	d.SetInternalRsense(true)
	if d.SetCurrent(0.5) != ErrTMC2208Unsupported {
		t.Error("SetCurrent must fail with internal Rsense")
	}
	d.SetInternalRsense(false)
}

func TestTMC2208FullReadbacks(t *testing.T) {
	m := newTMC2208Mock(true)
	d, _ := NewTMC2208Full(m, 0.11, nil, true, nil, nil, 12000000, false)
	m.regs[0x6A] = 513
	m.regs[0x6B] = 0x1FF<<16 | 0x100
	m.regs[0x12] = 1234
	m.regs[0x6F] = 1<<31 | 1<<30 | 17<<16 | 0x5<<8 | 0x02
	m.regs[0x71] = 0x1FE<<16 | 77
	m.regs[0x72] = 9<<16 | 33
	m.regs[0x01] = 0x03
	m.regs[0x05] = 0x123456
	if v, _ := d.MicrostepPosition(); v != 513 {
		t.Errorf("MSCNT = %d", v)
	}
	if lo, hi, _ := d.ReadCoilCurrents(); lo != -256 || hi != -1 {
		t.Errorf("coil currents = %d,%d", lo, hi)
	}
	if v, _ := d.ReadTstep(); v != 1234 {
		t.Errorf("TSTEP = %d", v)
	}
	if v, _ := d.ReadCurrentScale(); v != 17 {
		t.Errorf("CS_ACTUAL = %d", v)
	}
	if v, _ := d.ReadTemperatureFlags(); v != 5 {
		t.Errorf("temperature flags = %d", v)
	}
	st, _ := d.ReadStatus()
	if !st.Standstill || !st.Stealth || !st.OT || st.CSActual != 17 || !st.T120 || !st.T150 || st.T143 {
		t.Errorf("status = %+v", st)
	}
	gs, _ := d.ReadGlobalStatus(false)
	if !gs.Reset || !gs.DrvErr || gs.UVCP {
		t.Errorf("gstat = %+v", gs)
	}
	d.ReadGlobalStatus(true)
	if m.mustLast(t, 0x01) != 0x07 {
		t.Error("clear should write GSTAT")
	}
	pa, _ := d.ReadPwmAuto()
	if pa != (TMC2208PwmAuto{PwmScaleSum: 77, PwmScaleAuto: -2, PwmOfsAuto: 33, PwmGradAuto: 9}) {
		t.Errorf("pwm auto = %+v", pa)
	}
	if io, _ := d.ReadIoin(); !io.SelA || io.Version != 0x21 {
		t.Errorf("ioin = %+v", io)
	}
	if v, _ := d.ReadOtp(); v != 0x123456 {
		t.Errorf("otp = 0x%X", v)
	}
	if v, _ := d.ReadIfcnt(); uint32(v) != m.regs[0x02] {
		t.Errorf("ifcnt = %d", v)
	}
	if v, err := d.ReadRegister(0x6A); err != nil || v != 513 {
		t.Errorf("read register: %v %v", v, err)
	}
	if _, err := d.ReadRegister(0x10); err != ErrTMC2208InvalidArgument {
		t.Error("write-only register must be rejected")
	}
	d.WriteRegister(0x13, 5)
	if m.mustLast(t, 0x13) != 5 || d.WriteRegister(0x04, 1) != ErrTMC2208InvalidArgument {
		t.Error("write register")
	}
}

func TestTMC2208StepPulses(t *testing.T) {
	step, dir := &tmc2208Pin{}, &tmc2208Pin{}
	d, _ := NewTMC2208Full(newTMC2208Mock(true), 0.11, nil, true, step, dir, 12000000, false)
	if err := d.Step(3, time.Microsecond); err != nil || step.highs() != 3 || !dir.levels[len(dir.levels)-1] {
		t.Errorf("forward pulses: highs=%d dir=%v err=%v", step.highs(), dir.levels, err)
	}
	d.Step(-2, time.Microsecond)
	if dir.levels[len(dir.levels)-1] {
		t.Error("negative count should set DIR low")
	}
	nopin, _ := NewTMC2208Full(newTMC2208Mock(true), 0.11, nil, true, nil, nil, 12000000, false)
	if nopin.Step(1, 0) != ErrTMC2208NoPin {
		t.Error("Step without pin must fail")
	}
}

func TestTMC2208TMC2224Identity(t *testing.T) {
	m := newTMC2208Mock(true)
	m.regs[0x06] = 0x21000000
	if _, err := NewTMC2208Full(m, 0.11, nil, true, nil, nil, 12000000, true); err != nil {
		t.Errorf("TMC2224 identity should be accepted: %v", err)
	}
	if _, err := NewTMC2208Full(newTMC2208Mock(true), 0.11, nil, true, nil, nil, 12000000, true); err != ErrTMC2208NotFound {
		t.Errorf("TMC2208 identity must be rejected for tmc2224=true: %v", err)
	}
}
