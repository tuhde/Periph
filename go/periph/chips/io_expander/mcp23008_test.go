package ioexpander

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// spiMock08 is a mockConnection whose WriteRead resolves the register from the
// second header byte: the MCP23S08 header is [opcode, reg], unlike the
// register-address-first framing of mockConnection.
type spiMock08 struct {
	*mockConnection
}

func newSPIMock08() *spiMock08 { return &spiMock08{newMockConnection()} }

func (m *spiMock08) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[data[1]+byte(i)]
	}
	return out, nil
}

func TestMCP23008FullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMCP23008Full(conn, 0x20)
	if err != nil {
		t.Fatalf("NewMCP23008Full: %v", err)
	}

	if conn.registers[m08RegOLAT] != 0x00 {
		t.Errorf("init: OLAT not cleared")
	}
	if chip.shadow != 0x00 || chip.direction != 0xFF {
		t.Errorf("init: shadows = %#x/%#x, want 0x00/0xFF", chip.shadow, chip.direction)
	}
	for _, w := range conn.writes {
		if w[0] == m08RegIODIR {
			t.Errorf("init must not write IODIR: %v", conn.writes)
		}
	}

	conn.setRegister(m08RegGPIO, 0xA5)
	if v, err := chip.ReadPort(0); err != nil || v != 0xA5 {
		t.Errorf("ReadPort = %#x, %v, want 0xA5", v, err)
	}
	if v, _ := chip.ReadPort(1); v != 0xA5 {
		t.Errorf("ReadPort(1) should ignore the port argument, got %#x", v)
	}

	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatal(err)
	}
	if conn.registers[m08RegOLAT] != 0x3C || chip.shadow != 0x3C {
		t.Errorf("WritePort: OLAT=%#x shadow=%#x", conn.registers[m08RegOLAT], chip.shadow)
	}

	// GP7 is a normal bidirectional pin.
	_ = chip.Pin(7).Mode(MCP23008Output)
	if conn.registers[m08RegIODIR] != 0x7F {
		t.Errorf("GP7 output: IODIR = %#x, want 0x7F", conn.registers[m08RegIODIR])
	}
	_ = chip.Pin(7).Mode(MCP23008Input)
	if conn.registers[m08RegIODIR] != 0xFF {
		t.Errorf("GP7 input: IODIR = %#x, want 0xFF", conn.registers[m08RegIODIR])
	}

	// OLAT is written before IODIR when a pin becomes an output.
	_ = chip.WritePort(0, 0x00)
	before := len(conn.writes)
	_ = chip.Pin(1).Mode(MCP23008Output)
	if len(conn.writes)-before != 2 || conn.writes[before][0] != m08RegOLAT || conn.writes[before+1][0] != m08RegIODIR {
		t.Errorf("direction change order: %v", conn.writes[before:])
	}

	_ = chip.WritePort(0, 0x00)
	conn.setRegister(m08RegGPIO, 0x04)
	if v, err := chip.Pin(2).Read(); err != nil || !v {
		t.Errorf("Pin(2).Read = %v, %v, want true", v, err)
	}
	_ = chip.Pin(0).Write(true)
	_ = chip.Pin(2).Write(true)
	if conn.registers[m08RegOLAT] != 0x05 {
		t.Errorf("OLAT = %#x, want 0x05", conn.registers[m08RegOLAT])
	}
	_ = chip.Pin(0).Write(false)
	if conn.registers[m08RegOLAT] != 0x04 {
		t.Errorf("OLAT = %#x, want 0x04", conn.registers[m08RegOLAT])
	}
	_ = chip.Pin(0).Toggle()
	if conn.registers[m08RegOLAT] != 0x05 {
		t.Errorf("toggle on: OLAT = %#x, want 0x05", conn.registers[m08RegOLAT])
	}
	_ = chip.Pin(0).Toggle()
	if conn.registers[m08RegOLAT] != 0x04 {
		t.Errorf("toggle off: OLAT = %#x, want 0x04", conn.registers[m08RegOLAT])
	}

	// connection.OutputPin / InputPin satisfaction.
	var _ connection.OutputPin = chip.Pin(3)
	var _ connection.InputPin = chip.Pin(3)

	// Pull-up per pin.
	_ = chip.ConfigurePullup(0x00)
	_ = chip.Pin(5).Mode(MCP23008InputPullup)
	if conn.registers[m08RegGPPU] != 0x20 {
		t.Errorf("INPUT_PULLUP: GPPU = %#x, want 0x20", conn.registers[m08RegGPPU])
	}

	_ = chip.ConfigurePullup(0xF0)
	_ = chip.ConfigurePolarity(0x0F)
	_ = chip.SetDefaultValue(0x11)
	_ = chip.EnablePinInterrupt(0xF0)
	if conn.registers[m08RegGPPU] != 0xF0 || conn.registers[m08RegIPOL] != 0x0F ||
		conn.registers[m08RegDEFVAL] != 0x11 || conn.registers[m08RegGPINTEN] != 0xF0 {
		t.Errorf("config registers wrong: %v", conn.registers)
	}

	_ = chip.ConfigureIntPin(false, true)
	if conn.registers[m08RegIOCON] != 0x02 {
		t.Errorf("INTPOL: IOCON = %#x", conn.registers[m08RegIOCON])
	}
	_ = chip.ConfigureIntPin(true, true)
	if conn.registers[m08RegIOCON] != 0x04 {
		t.Errorf("ODR: IOCON = %#x", conn.registers[m08RegIOCON])
	}
	_ = chip.ConfigureIntPin(false, false)
	_ = chip.SetSequential(false)
	if conn.registers[m08RegIOCON] != 0x20 {
		t.Errorf("SEQOP off: IOCON = %#x", conn.registers[m08RegIOCON])
	}
	_ = chip.SetSequential(true)
	if conn.registers[m08RegIOCON] != 0x00 {
		t.Errorf("SEQOP on: IOCON = %#x", conn.registers[m08RegIOCON])
	}

	conn.setRegister(m08RegINTF, 0x10)
	conn.setRegister(m08RegINTCAP, 0xFF)
	if v, err := chip.PollInterrupt(); err != nil || v != 0x10 {
		t.Errorf("PollInterrupt = %#x, %v, want 0x10", v, err)
	}
	conn.setRegister(m08RegINTCAP, 0x22)
	if v, err := chip.ReadCapture(); err != nil || v != 0x22 {
		t.Errorf("ReadCapture = %#x, %v, want 0x22", v, err)
	}

	_ = chip.WriteRegister(m08RegIODIR, 0x0F)
	_ = chip.WriteRegister(m08RegOLAT, 0x81)
	if chip.direction != 0x0F || chip.shadow != 0x81 {
		t.Errorf("WriteRegister shadows: %#x/%#x", chip.direction, chip.shadow)
	}
	if v, _ := chip.ReadRegister(m08RegOLAT); v != 0x81 {
		t.Errorf("ReadRegister = %#x", v)
	}

	// OnInterrupt with no armed pins errors; Watch on an output errors.
	chip2, _ := NewMCP23008Full(newMockConnection(), 0x20)
	if err := chip2.OnInterrupt(func(uint8) {}, IntOnChange); err == nil {
		t.Errorf("OnInterrupt with no armed pins should fail")
	}
	_ = chip2.Pin(0).Mode(MCP23008Output)
	if err := chip2.Pin(0).Watch(connection.Change, func(MCP23008FullPin) {}); err == nil {
		t.Errorf("Watch on an output pin should fail")
	}

	// handleEdge dispatches the callback and a matching watch.
	chip3, _ := NewMCP23008Full(newMockConnection(), 0x20)
	c3 := chip3.conn.(*mockConnection)
	fired := false
	if err := chip3.Pin(4).Watch(connection.Falling, func(MCP23008FullPin) { fired = true }); err != nil {
		t.Fatal(err)
	}
	if c3.registers[m08RegGPINTEN] != 0x10 {
		t.Errorf("Watch should arm GPINTEN, got %#x", c3.registers[m08RegGPINTEN])
	}
	c3.setRegister(m08RegINTF, 0x10)
	c3.setRegister(m08RegINTCAP, 0x00)
	var got uint8
	chip3.callback = func(s uint8) { got = s }
	chip3.handleEdge()
	if !fired || got != 0x10 {
		t.Errorf("handleEdge: fired=%v status=%#x", fired, got)
	}
	_ = chip3.Pin(4).Unwatch()
	if c3.registers[m08RegGPINTEN] != 0x00 {
		t.Errorf("Unwatch should clear GPINTEN, got %#x", c3.registers[m08RegGPINTEN])
	}

	if err := chip.Reset(); err != nil {
		t.Fatal(err)
	}
	if conn.registers[m08RegIODIR] != 0xFF || conn.registers[m08RegOLAT] != 0x00 ||
		conn.registers[m08RegGPPU] != 0x00 || conn.registers[m08RegGPINTEN] != 0x00 {
		t.Errorf("Reset registers wrong: %v", conn.registers)
	}
	if chip.shadow != 0 || chip.direction != 0xFF {
		t.Errorf("Reset shadows wrong")
	}
}

func TestMCP23S08Framing(t *testing.T) {
	spi := newSPIMock08()
	chip, err := NewMCP23S08Full(spi, 2)
	if err != nil {
		t.Fatalf("NewMCP23S08Full: %v", err)
	}
	last := func() []byte { return spi.writes[len(spi.writes)-1] }

	if len(spi.writes) < 2 ||
		!bytes.Equal(spi.writes[0], []byte{0x40, 0x05, 0x08}) ||
		!bytes.Equal(spi.writes[1], []byte{0x44, 0x0A, 0x00}) {
		t.Errorf("init frames: %v", spi.writes)
	}

	_ = chip.WritePort(0, 0x5A)
	if !bytes.Equal(last(), []byte{0x44, 0x0A, 0x5A}) {
		t.Errorf("WritePort frame: %v", last())
	}

	spi.registers[m08RegGPIO] = 0xC3
	if v, err := chip.ReadPort(0); err != nil || v != 0xC3 {
		t.Errorf("ReadPort = %#x, %v", v, err)
	}
	if !bytes.Equal(last(), []byte{0x45, 0x09}) {
		t.Errorf("read header: %v", last())
	}

	_ = chip.ConfigurePullup(0x0F)
	if !bytes.Equal(last(), []byte{0x44, 0x06, 0x0F}) {
		t.Errorf("ConfigurePullup frame: %v", last())
	}
	_ = chip.ConfigureIntPin(true, false)
	if !bytes.Equal(last(), []byte{0x44, 0x05, 0x0C}) {
		t.Errorf("IOCON must keep HAEN: %v", last())
	}
	_ = chip.Reset()
	found := false
	for _, w := range spi.writes {
		if bytes.Equal(w, []byte{0x44, 0x05, 0x08}) {
			found = true
		}
	}
	if !found {
		t.Errorf("Reset must rewrite IOCON to HAEN")
	}

	if m, err := NewMCP23S08Minimal(newSPIMock08(), 0); err != nil || m == nil {
		t.Errorf("NewMCP23S08Minimal: %v", err)
	}
	var _ *MCP23S08Minimal = (*MCP23008Minimal)(nil)
}
