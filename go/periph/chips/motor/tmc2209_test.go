package motor

import (
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// tmc2209Mock is an in-memory TMC2209-style UART chip implementing
// connection.Connection: write datagrams update regs and IFCNT; read requests
// queue the reply; with echo every sent byte comes back.
type tmc2209Mock struct {
	echo        bool
	regs        map[uint8]uint32
	datagrams   [][]byte
	writes      []tmc2209RegWrite
	corruptNext bool
	rx          []byte
}

type tmc2209RegWrite struct {
	reg   uint8
	value uint32
}

func newTMC2209Mock(echo bool) *tmc2209Mock {
	return &tmc2209Mock{echo: echo, regs: map[uint8]uint32{0x06: 0x21000100, 0x02: 0}}
}

func (m *tmc2209Mock) Write(data []byte) error {
	d := append([]byte(nil), data...)
	m.datagrams = append(m.datagrams, d)
	if m.echo {
		m.rx = append(m.rx, d...)
	}
	if len(d) == 8 && d[2]&0x80 != 0 {
		reg := d[2] & 0x7F
		v := uint32(d[3])<<24 | uint32(d[4])<<16 | uint32(d[5])<<8 | uint32(d[6])
		m.regs[reg] = v
		m.writes = append(m.writes, tmc2209RegWrite{reg, v})
		m.regs[0x02] = (m.regs[0x02] + 1) & 0xFF
	} else if len(d) == 4 {
		reg := d[2] & 0x7F
		v := m.regs[reg]
		body := []byte{0x05, 0xFF, reg, byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v), 0}
		body[7] = TMC2209CRC8(body[:7])
		if m.corruptNext {
			body[7] ^= 0xFF
			m.corruptNext = false
		}
		m.rx = append(m.rx, body...)
	}
	return nil
}

func (m *tmc2209Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	k := copy(out, m.rx)
	m.rx = m.rx[k:]
	return out, nil
}

func (m *tmc2209Mock) WriteRead(data []byte, n int) ([]byte, error) {
	if err := m.Write(data); err != nil {
		return nil, err
	}
	return m.Read(n)
}

func (m *tmc2209Mock) Close() error                { return nil }
func (m *tmc2209Mock) Enable()                     {}
func (m *tmc2209Mock) Disable()                    {}
func (m *tmc2209Mock) IsEnabled() bool             { return true }
func (m *tmc2209Mock) IntPin() connection.InputPin { return nil }
func (m *tmc2209Mock) EnPin() connection.OutputPin { return nil }

func (m *tmc2209Mock) last(reg uint8) (uint32, bool) {
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg {
			return m.writes[i].value, true
		}
	}
	return 0, false
}

func (m *tmc2209Mock) mustLast(t *testing.T, reg uint8) uint32 {
	t.Helper()
	v, ok := m.last(reg)
	if !ok {
		t.Fatalf("register 0x%02X was never written", reg)
	}
	return v
}

type tmc2209Pin struct{ levels []bool }

func (p *tmc2209Pin) Set(high bool) error {
	p.levels = append(p.levels, high)
	return nil
}

func (p *tmc2209Pin) highs() int {
	n := 0
	for _, l := range p.levels {
		if l {
			n++
		}
	}
	return n
}

func TestTMC2209CRCVectors(t *testing.T) {
	if got := TMC2209CRC8([]byte{5, 0, 0x80, 0, 0, 1, 0xC1}); got != 0x7F {
		t.Errorf("write GCONF CRC = 0x%02X, want 0x7F", got)
	}
	if got := TMC2209CRC8([]byte{5, 0, 6}); got != 0x6F {
		t.Errorf("read IOIN CRC = 0x%02X, want 0x6F", got)
	}
}

func TestTMC2209MinimalInit(t *testing.T) {
	m := newTMC2209Mock(true)
	if _, err := NewTMC2209Minimal(m, 0.11, 0, nil, true); err != nil {
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
	if ih>>8&0x1F != 8 || ih&0x1F != 2 || ih>>16&0xF != 6 {
		t.Errorf("IHOLD_IRUN wrong: 0x%08X", ih)
	}
}

func TestTMC2209CurrentAndMotion(t *testing.T) {
	m := newTMC2209Mock(true)
	d, err := NewTMC2209Minimal(m, 0.11, 0, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	d.SetCurrent(1.0)
	if m.mustLast(t, 0x10)>>8&0x1F != 17 {
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

func TestTMC2209StatusAndFaults(t *testing.T) {
	m := newTMC2209Mock(true)
	d, _ := NewTMC2209Minimal(m, 0.11, 0, nil, true)
	m.regs[0x6F] = 1<<31 | 0x01 | 0x08 | 0x20
	if st, _ := d.IsStandstill(); !st {
		t.Error("expected standstill")
	}
	f, _ := d.ReadFault()
	if f != TMC2209FaultOvertempWarning|TMC2209FaultShortGND|TMC2209FaultShortSupply {
		t.Errorf("fault mask = 0x%02X", f)
	}
}

func TestTMC2209ENPinAndErrors(t *testing.T) {
	pin := &tmc2209Pin{}
	d, err := NewTMC2209Minimal(newTMC2209Mock(true), 0.11, 0, pin, true)
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

	ne := newTMC2209Mock(false)
	if _, err := NewTMC2209Minimal(ne, 0.11, 0, nil, false); err != nil {
		t.Fatal(err)
	}
	if ne.mustLast(t, 0x00) != 0x1C1 {
		t.Error("no-echo init failed")
	}

	bad := newTMC2209Mock(true)
	bad.regs[0x06] = 0
	if _, err := NewTMC2209Minimal(bad, 0.11, 0, nil, true); err != ErrTMC2209NotFound {
		t.Errorf("want ErrTMC2209NotFound, got %v", err)
	}

	flaky := newTMC2209Mock(true)
	flaky.corruptNext = true
	if _, err := NewTMC2209Minimal(flaky, 0.11, 0, nil, true); err != nil {
		t.Errorf("a single CRC error must be retried: %v", err)
	}
}

func TestTMC2209FullConfiguration(t *testing.T) {
	m := newTMC2209Mock(true)
	step, dir := &tmc2209Pin{}, &tmc2209Pin{}
	d, err := NewTMC2209Full(m, 0.11, 0, nil, true, step, dir, nil, false, 12000000)
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
	if d.SetMicrosteps(3) != ErrTMC2209InvalidArgument {
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
	if m.mustLast(t, 0x03) != 4<<8 || d.SetSendDelay(16) != ErrTMC2209InvalidArgument {
		t.Error("send delay")
	}
	d.SetInternalRsense(true)
	if d.SetCurrent(0.5) != ErrTMC2209Unsupported {
		t.Error("SetCurrent must fail with internal Rsense")
	}
	d.SetInternalRsense(false)
}

func TestTMC2209FullReadbacks(t *testing.T) {
	m := newTMC2209Mock(true)
	d, _ := NewTMC2209Full(m, 0.11, 0, nil, true, nil, nil, nil, false, 12000000)
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
	if pa != (TMC2209PwmAuto{PwmScaleSum: 77, PwmScaleAuto: -2, PwmOfsAuto: 33, PwmGradAuto: 9}) {
		t.Errorf("pwm auto = %+v", pa)
	}
	m.regs[0x06] = 0x21000000 | 0x04 | 0x10 | 0x200
	if io, _ := d.ReadIoin(); !io.MS1 || !io.Diag || !io.Dir || io.MS2 || io.Version != 0x21 {
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
	if _, err := d.ReadRegister(0x10); err != ErrTMC2209InvalidArgument {
		t.Error("write-only register must be rejected")
	}
	d.WriteRegister(0x13, 5)
	if m.mustLast(t, 0x13) != 5 || d.WriteRegister(0x04, 1) != ErrTMC2209InvalidArgument {
		t.Error("write register")
	}
}

func TestTMC2209StepPulses(t *testing.T) {
	step, dir := &tmc2209Pin{}, &tmc2209Pin{}
	d, _ := NewTMC2209Full(newTMC2209Mock(true), 0.11, 0, nil, true, step, dir, nil, false, 12000000)
	if err := d.Step(3, time.Microsecond); err != nil || step.highs() != 3 || !dir.levels[len(dir.levels)-1] {
		t.Errorf("forward pulses: highs=%d dir=%v err=%v", step.highs(), dir.levels, err)
	}
	d.Step(-2, time.Microsecond)
	if dir.levels[len(dir.levels)-1] {
		t.Error("negative count should set DIR low")
	}
	nopin, _ := NewTMC2209Full(newTMC2209Mock(true), 0.11, 0, nil, true, nil, nil, nil, false, 12000000)
	if nopin.Step(1, 0) != ErrTMC2209NoPin {
		t.Error("Step without pin must fail")
	}
}

type tmc2209InPin struct{ high bool }

func (p *tmc2209InPin) OnEdge(connection.Trigger, func()) func() { return func() {} }
func (p *tmc2209InPin) IsHigh() (bool, error)                    { return p.high, nil }

func TestTMC2209RejectsTMC2208(t *testing.T) {
	m := newTMC2209Mock(true)
	m.regs[0x06] = 0x20000100
	if _, err := NewTMC2209Minimal(m, 0.11, 0, nil, true); err != ErrTMC2209NotFound {
		t.Errorf("TMC2208 (VERSION 0x20) must be rejected: %v", err)
	}
}

func TestTMC2209NodeAddress(t *testing.T) {
	m := newTMC2209Mock(true)
	if _, err := NewTMC2209Minimal(m, 0.11, 2, nil, true); err != nil {
		t.Fatal(err)
	}
	for _, dg := range m.datagrams {
		if dg[1] != 2 {
			t.Fatalf("datagram %v lacks node address 2", dg)
		}
	}
	gconf := m.datagrams[2]
	if gconf[7] != TMC2209CRC8(gconf[:7]) {
		t.Error("CRC must cover the node address")
	}
	if _, err := NewTMC2209Minimal(newTMC2209Mock(true), 0.11, 4, nil, true); err != ErrTMC2209InvalidArgument {
		t.Errorf("address 4 must be rejected: %v", err)
	}
}

func TestTMC2209MultiNode(t *testing.T) {
	m := newTMC2209Mock(true)
	if _, err := NewTMC2209Full(m, 0.11, 0, nil, true, nil, nil, nil, true, 12000000); err != nil {
		t.Fatal(err)
	}
	if m.writes[0].reg != 0x03 || m.writes[0].value != 2<<8 {
		t.Errorf("first write = %+v, want NODECONF SENDDELAY=2", m.writes[0])
	}
	if m.regs[0x02] != 7 {
		t.Errorf("IFCNT = %d, want 7", m.regs[0x02])
	}
}

func TestTMC2209StallGuardAndCoolStep(t *testing.T) {
	m := newTMC2209Mock(true)
	diag := &tmc2209InPin{high: true}
	d, err := NewTMC2209Full(m, 0.11, 0, nil, true, nil, nil, diag, false, 12000000)
	if err != nil {
		t.Fatal(err)
	}
	d.SetStallThreshold(60)
	if m.mustLast(t, 0x40) != 60 {
		t.Error("SGTHRS")
	}
	d.SetCoolstepThreshold(500)
	if m.mustLast(t, 0x14) != 500 || d.SetCoolstepThreshold(1<<20) != ErrTMC2209InvalidArgument {
		t.Error("TCOOLTHRS")
	}
	d.ConfigureCoolstep(5, 2, 1, 1, 0)
	if m.mustLast(t, 0x42) != 5|1<<5|2<<8|1<<13 {
		t.Errorf("COOLCONF = 0x%X", m.mustLast(t, 0x42))
	}
	d.ConfigureCoolstep(0, 0, 0, 0, 1)
	if m.mustLast(t, 0x42) != 1<<15 || d.ConfigureCoolstep(16, 0, 0, 0, 0) != ErrTMC2209InvalidArgument {
		t.Error("COOLCONF seimin / range")
	}
	m.regs[0x41] = 0x1FE
	if v, _ := d.ReadStallguard(); v != 510 {
		t.Errorf("SG_RESULT = %d", v)
	}
	if st, err := d.IsStalled(); err != nil || !st {
		t.Errorf("IsStalled = %v, %v", st, err)
	}
	nodiag, _ := NewTMC2209Full(newTMC2209Mock(true), 0.11, 0, nil, true, nil, nil, nil, false, 12000000)
	if _, err := nodiag.IsStalled(); err != ErrTMC2209NoPin {
		t.Error("IsStalled without pin must fail")
	}
}
