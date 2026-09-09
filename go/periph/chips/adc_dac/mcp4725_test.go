package adcdac

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the two access patterns chip drivers in this repo use:
//   - Register-addressed reads (WriteRead([]byte{reg}, n)): backed by a
//     byte-addressable registers map. Preload it with setRegister.
//   - Plain streamed reads (Read(n), no register address): backed by a FIFO
//     queue. Preload responses with queueRead; each Read(n) call pops the
//     next one. Falls back to n zero bytes if the queue is empty.
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

func lastWrite(writes [][]byte) []byte {
	if len(writes) == 0 {
		return nil
	}
	return writes[len(writes)-1]
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestMCP4725FullAPI(t *testing.T) {
	conn := newMockConnection()
	dac, err := NewMCP4725Full(conn)
	if err != nil {
		t.Fatalf("NewMCP4725Full: %v", err)
	}

	// SetVoltage(0.5): code = uint16(0.5*4095.0) truncates to 2047 (0x7FF), not 2048.
	// Fast Write byte1=0x07, byte2=0xFF.
	if err := dac.SetVoltage(0.5); err != nil {
		t.Fatalf("SetVoltage: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x07, 0xFF}) {
		t.Errorf("SetVoltage(0.5) write = %v, want [0x07 0xFF]", lastWrite(conn.writes))
	}

	if err := dac.SetVoltage(2.0); err != nil {
		t.Fatalf("SetVoltage: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x0F, 0xFF}) {
		t.Errorf("SetVoltage(2.0) clamp write = %v, want [0x0F 0xFF]", lastWrite(conn.writes))
	}

	// SetRaw(4095) -> byte1=0x0F, byte2=0xFF.
	if err := dac.SetRaw(4095); err != nil {
		t.Fatalf("SetRaw: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x0F, 0xFF}) {
		t.Errorf("SetRaw(4095) write = %v, want [0x0F 0xFF]", lastWrite(conn.writes))
	}

	if err := dac.SetRaw(5000); err != nil {
		t.Fatalf("SetRaw: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x0F, 0xFF}) {
		t.Errorf("SetRaw(5000) clamp write = %v, want [0x0F 0xFF]", lastWrite(conn.writes))
	}

	// SetVoltageEEPROM(0.5): code truncates to 2047 (0x7FF). Write DAC+EEPROM:
	// byte1=0x60, byte2=code>>4=0x7F, byte3=(code&0xF)<<4=0xF0.
	if err := dac.SetVoltageEEPROM(0.5); err != nil {
		t.Fatalf("SetVoltageEEPROM: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x60, 0x7F, 0xF0}) {
		t.Errorf("SetVoltageEEPROM(0.5) write = %v, want [0x60 0x7F 0xF0]", lastWrite(conn.writes))
	}

	// SetRawEEPROM(4095) -> byte2=0xFF, byte3=0xF0.
	if err := dac.SetRawEEPROM(4095); err != nil {
		t.Fatalf("SetRawEEPROM: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x60, 0xFF, 0xF0}) {
		t.Errorf("SetRawEEPROM(4095) write = %v, want [0x60 0xFF 0xF0]", lastWrite(conn.writes))
	}

	// Read(): rdy_bsy=1, por=1, pd_dac=2, code=0x123, eeprom byte4=0x40
	// (0100_0000) -> PD1:PD0 at bits 6:5 = 2, eeprom_code=0xAB.
	conn.setRegister(0x00, 0xC8, 0x12, 0x30, 0x40, 0xAB)
	state, err := dac.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if state.Code != 0x123 {
		t.Errorf("Read().Code = 0x%X, want 0x123", state.Code)
	}
	if want := float32(0x123) / 4095.0; state.VoltageFraction < want-1e-6 || state.VoltageFraction > want+1e-6 {
		t.Errorf("Read().VoltageFraction = %v, want %v", state.VoltageFraction, want)
	}
	if state.PowerDown != 2 {
		t.Errorf("Read().PowerDown = %d, want 2", state.PowerDown)
	}
	if state.EEPROMCode != 0xAB {
		t.Errorf("Read().EEPROMCode = 0x%X, want 0xAB", state.EEPROMCode)
	}
	if state.EEPROMPowerDown != 2 {
		t.Errorf("Read().EEPROMPowerDown = %d, want 2", state.EEPROMPowerDown)
	}
	if !state.EEPROMReady {
		t.Errorf("Read().EEPROMReady = false, want true")
	}

	// SetPowerDown(2): reads current 2-byte DAC code (0x0AB), Fast Writes with PD=2.
	conn.setRegister(0x00, 0x00, 0xAB)
	if err := dac.SetPowerDown(2); err != nil {
		t.Fatalf("SetPowerDown: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x20, 0xAB}) {
		t.Errorf("SetPowerDown(2) write = %v, want [0x20 0xAB]", lastWrite(conn.writes))
	}

	// WakeUp() / Reset(): General Call commands -> [ADDR_GENERAL_CALL, cmd].
	if err := dac.WakeUp(); err != nil {
		t.Fatalf("WakeUp: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00, 0x09}) {
		t.Errorf("WakeUp write = %v, want [0x00 0x09]", lastWrite(conn.writes))
	}
	if err := dac.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00, 0x06}) {
		t.Errorf("Reset write = %v, want [0x00 0x06]", lastWrite(conn.writes))
	}

	// IsEEPROMReady(): RDY/BSY bit.
	conn.setRegister(0x00, 0x80)
	if ready, err := dac.IsEEPROMReady(); err != nil || !ready {
		t.Errorf("IsEEPROMReady() = %v, %v, want true, nil", ready, err)
	}
	conn.setRegister(0x00, 0x00)
	if ready, err := dac.IsEEPROMReady(); err != nil || ready {
		t.Errorf("IsEEPROMReady() = %v, %v, want false, nil", ready, err)
	}
}
