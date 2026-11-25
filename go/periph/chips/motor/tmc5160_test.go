package motor

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

type tmc5160Reg struct {
	reg   uint8
	value uint32
}

// tmc5160SpiSim is an in-memory TMC5160 over SPI with the pipelined 40-bit
// datagram protocol and an instant ramp generator.
type tmc5160SpiSim struct {
	regs      map[uint8]uint32
	writes    []tmc5160Reg
	pending   uint32
	mirrorBad bool
}

func newTMC5160SpiSim() *tmc5160SpiSim {
	return &tmc5160SpiSim{regs: map[uint8]uint32{0x04: 0x30000000}}
}

func (m *tmc5160SpiSim) datagram(frame []byte) uint32 {
	f := make([]byte, 5)
	copy(f, frame)
	reply := m.pending
	reg := f[0] & 0x7F
	if f[0]&0x80 != 0 {
		v := uint32(f[1])<<24 | uint32(f[2])<<16 | uint32(f[3])<<8 | uint32(f[4])
		m.regs[reg] = v
		m.writes = append(m.writes, tmc5160Reg{reg, v})
		if reg == 0x2D {
			m.regs[0x21] = v
			m.regs[0x35] = 1 << 9
		}
		m.pending = v
		if m.mirrorBad {
			m.pending = 0
		}
	} else {
		m.pending = m.regs[reg]
	}
	return reply
}

func (m *tmc5160SpiSim) Write(data []byte) error    { m.datagram(data); return nil }
func (m *tmc5160SpiSim) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *tmc5160SpiSim) WriteRead(data []byte, n int) ([]byte, error) {
	r := m.datagram(data)
	return []byte{byte(r >> 24), byte(r >> 16), byte(r >> 8), byte(r)}, nil
}
func (m *tmc5160SpiSim) Close() error                { return nil }
func (m *tmc5160SpiSim) Enable()                     {}
func (m *tmc5160SpiSim) Disable()                    {}
func (m *tmc5160SpiSim) IsEnabled() bool             { return true }
func (m *tmc5160SpiSim) IntPin() connection.InputPin { return nil }
func (m *tmc5160SpiSim) EnPin() connection.OutputPin { return nil }

func (m *tmc5160SpiSim) last(t *testing.T, reg uint8) uint32 {
	t.Helper()
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg {
			return m.writes[i].value
		}
	}
	t.Fatalf("register 0x%02X was never written", reg)
	return 0
}

// tmc5160UartSim is an in-memory TMC5160 over single-wire UART with echo, CRC8 and IFCNT.
type tmc5160UartSim struct {
	regs   map[uint8]uint32
	writes []tmc5160Reg
	ifcnt  uint8
	rx     []byte
}

func newTMC5160UartSim() *tmc5160UartSim {
	return &tmc5160UartSim{regs: map[uint8]uint32{0x04: 0x30000000}}
}

func (m *tmc5160UartSim) Write(data []byte) error {
	m.rx = append(m.rx, data...)
	if len(data) == 8 {
		reg := data[2] & 0x7F
		v := uint32(data[3])<<24 | uint32(data[4])<<16 | uint32(data[5])<<8 | uint32(data[6])
		m.regs[reg] = v
		m.writes = append(m.writes, tmc5160Reg{reg, v})
		m.ifcnt++
	}
	return nil
}

func (m *tmc5160UartSim) Read(n int) ([]byte, error) {
	out := m.rx[:n]
	m.rx = m.rx[n:]
	return out, nil
}

func (m *tmc5160UartSim) WriteRead(data []byte, n int) ([]byte, error) {
	reg := data[2] & 0x7F
	v := m.regs[reg]
	if reg == 0x02 {
		v = uint32(m.ifcnt)
	}
	r := []byte{0x05, 0xFF, reg, byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v), 0}
	r[7] = TMC5160CRC8(r[:7])
	return append(append([]byte{}, data...), r...)[:n], nil
}
func (m *tmc5160UartSim) Close() error                { return nil }
func (m *tmc5160UartSim) Enable()                     {}
func (m *tmc5160UartSim) Disable()                    {}
func (m *tmc5160UartSim) IsEnabled() bool             { return true }
func (m *tmc5160UartSim) IntPin() connection.InputPin { return nil }
func (m *tmc5160UartSim) EnPin() connection.OutputPin { return nil }

func TestTMC5160CRC(t *testing.T) {
	if got := TMC5160CRC8([]byte{0x05, 0x00, 0x00}); got != 0x48 {
		t.Fatalf("crc = 0x%02X", got)
	}
}

func TestTMC5160SpiInitDefaults(t *testing.T) {
	sim := newTMC5160SpiSim()
	if _, err := NewTMC5160Minimal(sim, 0.075, nil); err != nil {
		t.Fatal(err)
	}
	for reg, want := range map[uint8]uint32{0x6C: 0x000100C3, 0x00: 0x4, 0x13: 500, 0x01: 7, 0x27: 71583, 0x26: 1564} {
		if got := sim.last(t, reg); got != want {
			t.Errorf("reg 0x%02X = %d, want %d", reg, got, want)
		}
	}
}

func TestTMC5160RejectsBadChip(t *testing.T) {
	sim := newTMC5160SpiSim()
	sim.regs[0x04] = 0x11000000
	if _, err := NewTMC5160Minimal(sim, 0.075, nil); err != ErrTMC5160NotFound {
		t.Fatalf("err = %v", err)
	}
	sim = newTMC5160SpiSim()
	sim.regs[0x04] = 0x30000040
	if _, err := NewTMC5160Minimal(sim, 0.075, nil); err != ErrTMC5160StepDirMode {
		t.Fatalf("err = %v", err)
	}
}

func TestTMC5160Motion(t *testing.T) {
	sim := newTMC5160SpiSim()
	d, _ := NewTMC5160Minimal(sim, 0.075, nil)
	if err := d.MoveTo(400); err != nil {
		t.Fatal(err)
	}
	if got := sim.last(t, 0x2D); got != 400*256 {
		t.Fatalf("XTARGET = %d", got)
	}
	if err := d.WaitUntilReached(1); err != nil {
		t.Fatal(err)
	}
	if p, _ := d.Position(); p != 400 {
		t.Fatalf("position = %v", p)
	}
	_ = d.Run(-200)
	if got := sim.last(t, 0x20); got != 2 {
		t.Fatalf("RAMPMODE = %d", got)
	}
	_ = d.Stop()
	if got := sim.last(t, 0x27); got != 0 {
		t.Fatalf("VMAX = %d", got)
	}
	if d.SetCurrent(10) == nil || d.SetSpeed(0) == nil {
		t.Fatal("expected invalid argument errors")
	}
	if err := d.SetCurrent(1); err != nil {
		t.Fatal(err)
	}
}

func TestTMC5160FullRampAndUartOnly(t *testing.T) {
	sim := newTMC5160SpiSim()
	d, err := NewTMC5160Full(sim, 0.075, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.ConfigureRamp(0, 800, 400, 400, 200, 400, 800, 1); err != nil {
		t.Fatal(err)
	}
	if got := sim.last(t, 0x24); got != 3127 {
		t.Fatalf("A1 = %d", got)
	}
	if _, err := d.ReadIfcnt(); err != ErrTMC5160UartOnly {
		t.Fatalf("err = %v", err)
	}
	if err := d.WriteRegister(0x13, 5, true); err != nil {
		t.Fatal(err)
	}
	sim.mirrorBad = true
	if err := d.WriteRegister(0x13, 6, true); err != ErrTMC5160NotAcknowledged {
		t.Fatalf("err = %v", err)
	}
	sim.mirrorBad = false
	if err := d.SetMicrosteps(16); err != nil {
		t.Fatal(err)
	}
	if got := (sim.last(t, 0x6C) >> 24) & 0xF; got != 4 {
		t.Fatalf("MRES = %d", got)
	}
	if d.SetMicrosteps(3) == nil {
		t.Fatal("expected error for 3 microsteps")
	}
	if d.SetCoilCurrents(1, 1) != ErrTMC5160DirectModeNeeded {
		t.Fatal("expected direct mode error")
	}
}

func TestTMC5160UartInit(t *testing.T) {
	sim := newTMC5160UartSim()
	d, err := NewTMC5160FullUART(sim, 0.075, 0, nil, true, false)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, w := range sim.writes {
		if w.reg == 0x6C && w.value == 0x000100C3 {
			found = true
		}
	}
	if !found {
		t.Fatal("CHOPCONF default not written")
	}
	if n, err := d.ReadIfcnt(); err != nil || n == 0 {
		t.Fatalf("ifcnt = %d, %v", n, err)
	}
	if err := d.SetSendDelay(2); err != nil {
		t.Fatal(err)
	}
	if sim.regs[0x03] != 2<<8 {
		t.Fatalf("SLAVECONF = 0x%X", sim.regs[0x03])
	}
}
