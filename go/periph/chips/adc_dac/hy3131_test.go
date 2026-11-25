package adcdac

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// hyChip models the HY3131 framing: (addr<<1)|R/W command, one blank clock on
// reads, auto-increment. INTF reads back intf once, then clears.
type hyChip struct {
	mem  map[uint8]uint8
	intf uint8
	log  [][]byte
}

func newHyChip() *hyChip { return &hyChip{mem: map[uint8]uint8{}, intf: 0x07} }

func (c *hyChip) set(reg uint8, vals ...uint8) {
	for i, v := range vals {
		c.mem[reg+uint8(i)] = v
	}
}

func (c *hyChip) Write(data []byte) error {
	c.log = append(c.log, append([]byte(nil), data...))
	addr := data[0] >> 1
	for i := 1; i < len(data); i++ {
		c.mem[addr+uint8(i-1)] = data[i]
	}
	return nil
}

func (c *hyChip) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (c *hyChip) WriteRead(data []byte, n int) ([]byte, error) {
	addr := data[0] >> 1
	vals := make([]uint8, n)
	for i := range vals {
		a := addr + uint8(i)
		if a == 0x1E {
			vals[i] = c.intf
			c.intf = 0
		} else {
			vals[i] = c.mem[a]
		}
	}
	out := make([]byte, n)
	for i := range out {
		if i == 0 {
			out[i] = vals[0] >> 1
		} else {
			out[i] = ((vals[i-1] & 1) << 7) | (vals[i] >> 1)
		}
	}
	return out, nil
}

func (c *hyChip) Close() error                { return nil }
func (c *hyChip) Enable()                     {}
func (c *hyChip) Disable()                    {}
func (c *hyChip) IsEnabled() bool             { return true }
func (c *hyChip) IntPin() connection.InputPin { return nil }
func (c *hyChip) EnPin() connection.OutputPin { return nil }

func hyNear(a, b, tol float64) bool { return math.Abs(a-b) < tol }

func TestHY3131Init(t *testing.T) {
	c := newHyChip()
	if _, err := NewHY3131Minimal(c, 1.2); err != nil {
		t.Fatal(err)
	}
	if !bytesEqual(c.log[0], []byte{0x37 << 1, 0x60}) {
		t.Errorf("first write: %v", c.log[0])
	}
	checks := map[uint8]uint8{0x33: 0x28, 0x31: 0xC0, 0x30: 0x00, 0x24: 0xA3, 0x28: 0x11, 0x22: 0x07, 0x23: 0x80}
	for reg, want := range checks {
		if c.mem[reg] != want {
			t.Errorf("reg 0x%02X = 0x%02X, want 0x%02X", reg, c.mem[reg], want)
		}
	}
}

func TestHY3131RegisterFraming(t *testing.T) {
	c := newHyChip()
	s, _ := NewHY3131Minimal(c, 1.2)
	c.set(0x70, 0xA5, 0x5A)
	b, _ := s.ReadRegister(0x70, 2)
	if !bytesEqual(b, []byte{0xA5, 0x5A}) {
		t.Errorf("read_register shift: %v", b)
	}
	_ = s.WriteRegister(0x60, []byte{1, 2, 3})
	if c.mem[0x60] != 1 || c.mem[0x61] != 2 || c.mem[0x62] != 3 {
		t.Errorf("auto-increment write failed")
	}
}

func TestHY3131ReadRawVoltage(t *testing.T) {
	c := newHyChip()
	s, _ := NewHY3131Minimal(c, 1.2)
	c.set(0x00, 0, 0, 0x40)
	c.intf = 0x04
	if raw, err := s.ReadRaw(); err != nil || raw != 0x400000 {
		t.Errorf("ReadRaw = %v, %v", raw, err)
	}
	c.intf = 0x04
	if v, _ := s.ReadVoltage(); !hyNear(v, 0.5*1.2/0.9, 1e-9) {
		t.Errorf("ReadVoltage = %v", v)
	}
	c.set(0x00, 0, 0, 0x80)
	c.intf = 0x04
	if raw, _ := s.ReadRaw(); raw != -8388608 {
		t.Errorf("negative ReadRaw = %v", raw)
	}
	c.intf = 0x04
	if ok, _ := s.DataReady(); !ok {
		t.Errorf("DataReady false")
	}
	c.set(0x00, 1, 0, 0)
	if raw, _ := s.ReadRaw(); raw != 1 {
		t.Errorf("latched flag lost: %v", raw)
	}
}

func TestHY3131Full(t *testing.T) {
	c := newHyChip()
	f, err := NewHY3131Full(c, 1.2)
	if err != nil {
		t.Fatal(err)
	}
	_ = f.ConfigureAD1(4, 2, 3, 1, 1, 3)
	if c.mem[0x22] != (1<<5)|(2<<3)|4 || c.mem[0x23] != 0x93 || (c.mem[0x25]>>4)&3 != 3 {
		t.Errorf("ConfigureAD1 regs: %02X %02X %02X", c.mem[0x22], c.mem[0x23], c.mem[0x25])
	}
	c.set(0x00, 0, 0, 0x40)
	c.intf = 0x04
	v, _ := f.ReadVoltage()
	if !hyNear(v, (0.5*0.3333333333-0.25)*1.2/3.6, 1e-6) {
		t.Errorf("ConfigureAD1 voltage = %v", v)
	}

	_ = f.SetAD1Input(0x8, 0x5, 1)
	_ = f.SetAD1Reference(2, 6)
	if c.mem[0x24] != 0x85 || (c.mem[0x33]>>2)&3 != 1 || c.mem[0x28] != 0x26 {
		t.Errorf("mux regs wrong")
	}

	_ = f.EnableAD2(3, 2, 0, false, false, 1, true)
	if c.mem[0x26] != 0x83 || c.mem[0x34]&0x84 != 0x84 || (c.mem[0x25]>>6)&3 != 2 {
		t.Errorf("EnableAD2 regs wrong")
	}
	c.set(0x03, 0xFF, 0xFF, 0xFF)
	c.intf = 0x02
	if raw, _ := f.ReadAD2Raw(); raw != -1 {
		t.Errorf("ReadAD2Raw = %v", raw)
	}

	_ = f.EnableLPF(3)
	_ = f.EnableRMS()
	if c.mem[0x29] != 0xC0|(3<<3) {
		t.Errorf("R29 = %02X", c.mem[0x29])
	}
	c.set(0x09, 0x00, 0x90, 0x01, 0x00, 0x00)
	c.intf = 0x10
	if rms, _ := f.ReadRMS(); !hyNear(rms, 320, 1e-9) {
		t.Errorf("ReadRMS = %v", rms)
	}

	_ = f.EnablePeakHold(2)
	if c.mem[0x29]&0x07 != 0x06 {
		t.Errorf("peak hold bits")
	}
	c.set(0x0E, 0x00, 0x00, 0xFE, 0x00, 0x00, 0x01)
	if lo, hi, _ := f.ReadPeak(); lo != -131072 || hi != 65536 {
		t.Errorf("ReadPeak = %v %v", lo, hi)
	}

	_ = f.StartCounter(0x1234)
	if c.mem[0x1C] != 0x34 || c.mem[0x1D] != 0x12 || c.mem[0x20]&0x0A != 0x0A {
		t.Errorf("StartCounter regs wrong")
	}
	c.set(0x14, 0x01)
	c.intf = 0x01
	if _, _, _, ov, _ := f.ReadCounter(); !ov {
		t.Errorf("overflow not reported")
	}

	c.intf = 0x14
	if fl, _ := f.ReadFlags(); fl != 0x14 {
		t.Errorf("ReadFlags = %02X", fl)
	}
	_ = f.EnableInterrupts(0xFF)
	if c.mem[0x1F] != 0x1F {
		t.Errorf("INTE = %02X", c.mem[0x1F])
	}
}

func TestHY3131Timeout(t *testing.T) {
	if testing.Short() {
		t.Skip("2 s timeout")
	}
	c := newHyChip()
	s, _ := NewHY3131Minimal(c, 1.2)
	c.intf = 0
	if _, err := s.ReadRaw(); err != ErrHY3131Timeout {
		t.Errorf("expected timeout, got %v", err)
	}
}
