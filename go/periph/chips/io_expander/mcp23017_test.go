package ioexpander

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. Shared by every *_test.go file in this package.
//
// MCP23017 reads are register-addressed (WriteRead), so mockConnection's
// registers map can be preloaded directly via setRegister. PCF8574/PCF8575
// issue plain Read/Write calls (no register pointer) — their tests preload
// responses with queueRead instead; each Read(n) call pops the next queued
// entry, falling back to n zero bytes when the queue is empty.
//
// Every Write call (register writes and plain command writes alike) is
// appended to writes for assertions, and 2+ byte writes are also applied to
// registers so a later WriteRead sees them.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func TestMCP23017FullAPI(t *testing.T) {
	conn := newMockConnection()
	chip, err := NewMCP23017Full(conn, 0x20)
	if err != nil {
		t.Fatalf("NewMCP23017Full: %v", err)
	}

	// Init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7 forced
	// output-only), IPOLA/IPOLB=0x00, GPPUA/GPPUB=0x00.
	if conn.registers[mcpRegOLATA] != 0x00 || conn.registers[mcpRegOLATB] != 0x00 {
		t.Errorf("init: OLAT not cleared: %v", conn.registers)
	}
	if conn.registers[mcpRegIODIRA] != 0x7F || conn.registers[mcpRegIODIRB] != 0x7F {
		t.Errorf("init: IODIR should be 0x7F (GPx7 output-only): %v", conn.registers)
	}
	if conn.registers[mcpRegIPOLA] != 0x00 || conn.registers[mcpRegGPPUA] != 0x00 {
		t.Errorf("init: IPOLA/GPPUA should be 0x00: %v", conn.registers)
	}

	// ReadPort(0)/(1) -> GPIOA/GPIOB.
	conn.setRegister(mcpRegGPIOA, 0xA5)
	if v, err := chip.ReadPort(0); err != nil || v != 0xA5 {
		t.Errorf("ReadPort(0) = %#x, %v, want 0xA5, nil", v, err)
	}
	conn.setRegister(mcpRegGPIOB, 0x5A)
	if v, err := chip.ReadPort(1); err != nil || v != 0x5A {
		t.Errorf("ReadPort(1) = %#x, %v, want 0x5A, nil", v, err)
	}

	// WritePort updates OLAT register and shadow.
	if err := chip.WritePort(0, 0x3C); err != nil {
		t.Fatalf("WritePort: %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x3C || chip.shadow[0] != 0x3C {
		t.Errorf("WritePort(0, 0x3C): register=%#x shadow=%#x, want 0x3C both",
			conn.registers[mcpRegOLATA], chip.shadow[0])
	}

	// Pin() read on PORTA and PORTB.
	conn.setRegister(mcpRegGPIOA, 0x01)
	pin0 := chip.Pin(0)
	if high, err := pin0.Get(); err != nil || !high {
		t.Errorf("pin0.Get() = %v, %v, want true, nil", high, err)
	}
	conn.setRegister(mcpRegGPIOB, 0x02)
	pin9 := chip.Pin(9)
	if high, err := pin9.Get(); err != nil || !high {
		t.Errorf("pin9.Get() = %v, %v, want true, nil", high, err)
	}

	// Pin Set preserves other output bits (shadow read-modify-write).
	if err := chip.WritePort(0, 0x00); err != nil {
		t.Fatalf("WritePort reset: %v", err)
	}
	if err := pin0.Set(true); err != nil {
		t.Fatalf("pin0.Set(true): %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x01 {
		t.Errorf("pin0 on: OLATA = %#x, want 0x01", conn.registers[mcpRegOLATA])
	}
	pin2 := chip.Pin(2)
	if err := pin2.Set(true); err != nil {
		t.Fatalf("pin2.Set(true): %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x05 {
		t.Errorf("pin2 on preserves pin0: OLATA = %#x, want 0x05", conn.registers[mcpRegOLATA])
	}
	if err := pin0.Set(false); err != nil {
		t.Fatalf("pin0.Set(false): %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x04 {
		t.Errorf("pin0 off preserves pin2: OLATA = %#x, want 0x04", conn.registers[mcpRegOLATA])
	}

	// Toggle inverts the shadow bit and writes it.
	if err := pin0.Toggle(); err != nil {
		t.Fatalf("pin0.Toggle(): %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x05 {
		t.Errorf("pin0 toggle on: OLATA = %#x, want 0x05", conn.registers[mcpRegOLATA])
	}
	if err := pin0.Toggle(); err != nil {
		t.Fatalf("pin0.Toggle(): %v", err)
	}
	if conn.registers[mcpRegOLATA] != 0x04 {
		t.Errorf("pin0 toggle off: OLATA = %#x, want 0x04", conn.registers[mcpRegOLATA])
	}

	// Full: ConfigurePullup / ConfigurePolarity.
	if err := chip.ConfigurePullup(0, 0xFF); err != nil {
		t.Fatalf("ConfigurePullup: %v", err)
	}
	if conn.registers[mcpRegGPPUA] != 0xFF {
		t.Errorf("ConfigurePullup: GPPUA = %#x, want 0xFF", conn.registers[mcpRegGPPUA])
	}
	if err := chip.ConfigurePolarity(1, 0x0F); err != nil {
		t.Fatalf("ConfigurePolarity: %v", err)
	}
	if conn.registers[mcpRegIPOLB] != 0x0F {
		t.Errorf("ConfigurePolarity: IPOLB = %#x, want 0x0F", conn.registers[mcpRegIPOLB])
	}

	// PollInterrupt(port): reads INTF then INTCAP (discarded); returns INTF value.
	conn.setRegister(mcpRegINTFA, 0x08)
	conn.setRegister(mcpRegINTCAPA, 0xFF)
	if v, err := chip.PollInterrupt(0); err != nil || v != 0x08 {
		t.Errorf("PollInterrupt(0) = %#x, %v, want 0x08, nil", v, err)
	}

	// ReadCapture(port): reads INTCAP directly.
	conn.setRegister(mcpRegINTCAPB, 0x22)
	if v, err := chip.ReadCapture(1); err != nil || v != 0x22 {
		t.Errorf("ReadCapture(1) = %#x, %v, want 0x22, nil", v, err)
	}
}
