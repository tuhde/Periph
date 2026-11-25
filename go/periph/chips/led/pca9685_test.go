package led

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// pca9685Mock is an in-memory fake connection.RegisterConnection for unit tests —
// no hardware, no bus. Every WriteReg is logged as reg+data and applied to the
// register map; ReadReg answers from it.
type pca9685Mock struct {
	connection.RegisterConnection // embedded nil: only the methods overridden below are used
	writes                        [][]byte
	regs                          map[uint32]byte
}

func (m *pca9685Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	for i, v := range data {
		m.regs[reg+uint32(i)] = v
	}
	return nil
}

func (m *pca9685Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[reg+uint32(i)]
	}
	return out, nil
}

func (m *pca9685Mock) IntPin() connection.InputPin { return nil }
func (m *pca9685Mock) Close() error                { return nil }
func (m *pca9685Mock) last() []byte                { return m.writes[len(m.writes)-1] }

type pca9685GC struct {
	connection.Connection
	written [][]byte
}

func (g *pca9685GC) Write(data []byte) error {
	g.written = append(g.written, append([]byte(nil), data...))
	return nil
}

func TestPca9685(t *testing.T) {
	m := &pca9685Mock{regs: map[uint32]byte{}}
	d, err := NewPca9685Full(m)
	if err != nil {
		t.Fatal(err)
	}
	eq := func(label string, got []byte, want ...byte) {
		t.Helper()
		if !bytes.Equal(got, want) {
			t.Errorf("%s: got % x, want % x", label, got, want)
		}
	}
	if len(m.writes) != 3 {
		t.Fatalf("init writes: %d", len(m.writes))
	}
	eq("init1", m.writes[0], 0x00, 0x30)
	eq("init2", m.writes[1], 0xFE, 5)
	eq("init3", m.writes[2], 0x00, 0x20)

	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	must(d.SetDuty(0, 0.5))
	eq("duty_half", m.last(), 0x06, 0, 0, 0x00, 0x08)
	must(d.SetDuty(15, 0))
	eq("duty_zero", m.last(), 0x42, 0, 0, 0, 0x10)
	must(d.SetDuty(1, 1))
	eq("duty_one", m.last(), 0x0A, 0, 0x10, 0, 0)
	if d.SetDuty(16, 0.5) != ErrPca9685Range || d.SetDuty(0, 1.1) != ErrPca9685Range || d.SetDuty(0, -0.1) != ErrPca9685Range {
		t.Error("duty range")
	}

	must(d.SetPwm(2, 409, 1228))
	eq("set_pwm", m.last(), 0x0E, 0x99, 0x01, 0xCC, 0x04)
	on, off, err := d.GetPwm(2)
	must(err)
	if on != 409 || off != 1228 {
		t.Errorf("get_pwm %d %d", on, off)
	}
	if d.SetPwm(0, 4096, 1) != ErrPca9685Range || d.SetPwm(0, 5, 5) != ErrPca9685Range {
		t.Error("pwm range")
	}
	must(d.SetFullOn(3))
	eq("full_on", m.last(), 0x12, 0, 0x10, 0, 0)
	must(d.SetFullOff(3))
	eq("full_off", m.last(), 0x12, 0, 0, 0, 0x10)
	must(d.SetAllDuty(0.25))
	eq("all_duty", m.last(), 0xFA, 0, 0, 0x00, 0x04)
	must(d.SetAllPwm(0, 100))
	eq("all_pwm", m.last(), 0xFA, 0, 0, 100, 0)

	m.regs[0x00] = 0x20
	m.writes = nil
	must(d.SetFrequency(50, 25e6))
	if len(m.writes) != 3 {
		t.Fatalf("set_frequency writes %d", len(m.writes))
	}
	eq("freq1", m.writes[0], 0x00, 0x30)
	eq("freq2", m.writes[1], 0xFE, 121)
	eq("freq3", m.writes[2], 0x00, 0x20)
	hz, err := d.GetFrequency(25e6)
	must(err)
	if hz < 50.0 || hz > 50.1 {
		t.Errorf("get_frequency %v", hz)
	}
	must(d.SetFrequency(1e6, 25e6))
	eq("clamp_min", m.writes[len(m.writes)-2], 0xFE, 3)
	must(d.SetFrequency(1, 25e6))
	eq("clamp_max", m.writes[len(m.writes)-2], 0xFE, 255)
	m.regs[0x00] = 0xA0
	m.writes = nil
	must(d.SetFrequency(200, 25e6))
	eq("restart_write", m.last(), 0x00, 0xA0)
	eq("prescale_200", m.writes[1], 0xFE, 30)
	if d.SetFrequency(0, 25e6) != ErrPca9685Range {
		t.Error("freq range")
	}

	m.regs[0xFE] = 121
	must(d.SetPulseWidth(15, 0.0015))
	eq("pulse_width", m.last(), 0x42, 0, 0, byte(307&0xFF), byte(307>>8))

	m.regs[0x00] = 0x20
	must(d.Sleep())
	eq("sleep", m.last(), 0x00, 0x30)
	m.regs[0x00] = 0xB0
	m.writes = nil
	must(d.Wake())
	eq("wake1", m.writes[0], 0x00, 0x20)
	eq("wake2", m.writes[1], 0x00, 0xA0)
	must(d.Restart())
	eq("restart", m.last(), 0x00, 0xA0)

	m.regs[0x01] = 0x04
	must(d.SetOutputMode(true, true, Pca9685OffHighZ))
	eq("output_mode", m.last(), 0x01, 0x12)
	must(d.SetOutputMode(false, false, Pca9685OffLow))
	eq("output_mode_default", m.last(), 0x01, 0x04)
	if d.SetOutputMode(false, false, 3) != ErrPca9685Range {
		t.Error("offstate range")
	}
	must(d.SetUpdateOnAck(true))
	eq("och_on", m.last(), 0x01, 0x0C)
	must(d.SetUpdateOnAck(false))
	eq("och_off", m.last(), 0x01, 0x04)

	m.regs[0x00] = 0x20
	m.writes = nil
	must(d.UseExternalClock())
	eq("ext1", m.writes[0], 0x00, 0x30)
	eq("ext2", m.writes[1], 0x00, 0x70)
	eq("ext3", m.writes[2], 0x00, 0x60)

	m.regs[0x00] = 0x20
	m.writes = nil
	must(d.SetAllcall(true, 0x70))
	eq("allcall_addr", m.writes[0], 0x05, 0xE0)
	eq("allcall_mode", m.writes[1], 0x00, 0x21)
	must(d.SetSubaddress(2, 0x71, true))
	eq("sub_addr", m.writes[2], 0x03, 0xE2)
	eq("sub_mode", m.writes[3], 0x00, 0x25)
	if d.SetSubaddress(4, 0x71, true) != ErrPca9685Range || d.SetAllcall(true, 0x80) != ErrPca9685Range {
		t.Error("addr range")
	}

	m.writes = nil
	must(d.Reset())
	eq("reset_first", m.writes[0], 0x00, 0x10)
	eq("reset_last", m.last(), 0x00, 0x11)

	g := &pca9685GC{}
	must(Pca9685SoftwareReset(g))
	if len(g.written) != 1 || !bytes.Equal(g.written[0], []byte{0x06}) {
		t.Error("software_reset")
	}

	m.regs[0x00], m.regs[0x01] = 0x11, 0x04
	m1, m2, err := d.ReadMode()
	must(err)
	if m1 != 0x11 || m2 != 0x04 {
		t.Error("read_mode")
	}
}
