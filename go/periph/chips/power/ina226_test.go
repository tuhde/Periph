package power

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

func lastWriteTo(writes [][]byte, reg byte) []byte {
	var last []byte
	for _, w := range writes {
		if len(w) >= 1 && w[0] == reg {
			last = w
		}
	}
	return last
}

func TestINA226FullAPI(t *testing.T) {
	conn := newMockConnection()

	// Construction: rShunt=0.1, maxCurrent=2.0 (defaults) -> currentLSB=6.103515625e-5,
	// cal=uint16(0.00512/(currentLSB*0.1))=838 (0x0346). Constructor writes CONFIG then CAL.
	sensor, err := NewINA226Full(conn, 0.1, 2.0)
	if err != nil {
		t.Fatalf("NewINA226Full: %v", err)
	}

	configWrite := lastWriteTo(conn.writes, ina226RegConfig)
	if configWrite == nil || configWrite[1] != 0x41 || configWrite[2] != 0x27 {
		t.Errorf("init: expected CONFIG write 0x4127, got %v", configWrite)
	}
	calWrite := lastWriteTo(conn.writes, ina226RegCal)
	if calWrite == nil || calWrite[1] != 0x03 || calWrite[2] != 0x46 {
		t.Errorf("init: expected CAL write 0x0346, got %v", calWrite)
	}

	// Voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
	conn.setRegister(ina226RegBus, 0x19, 0x00)
	if v, err := sensor.Voltage(); err != nil || v != 8.0 {
		t.Errorf("Voltage() = %v, %v, want 8.0, nil", v, err)
	}

	// ShuntVoltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 V
	conn.setRegister(ina226RegShunt, 0xFF, 0x9C)
	if v, err := sensor.ShuntVoltage(); err != nil || v != float32(-100)*2.5e-6 {
		t.Errorf("ShuntVoltage() = %v, %v, want %v, nil", v, err, float32(-100)*2.5e-6)
	}

	// Current: raw signed = 1000 (0x03E8) -> 1000 * currentLSB
	conn.setRegister(ina226RegCurrent, 0x03, 0xE8)
	wantCurrent := float32(1000) * sensor.currentLSB
	if v, err := sensor.Current(); err != nil || v != wantCurrent {
		t.Errorf("Current() = %v, %v, want %v, nil", v, err, wantCurrent)
	}

	// Power: raw = 500 (0x01F4) -> 500 * 25 * currentLSB
	conn.setRegister(ina226RegPower, 0x01, 0xF4)
	wantPower := float32(500) * 25.0 * sensor.currentLSB
	if v, err := sensor.Power(); err != nil || v != wantPower {
		t.Errorf("Power() = %v, %v, want %v, nil", v, err, wantPower)
	}

	// Configure(2, 3, 5, 6) -> config = 0x04EE
	if err := sensor.Configure(2, 3, 5, 6); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if cfg := lastWriteTo(conn.writes, ina226RegConfig); cfg == nil || cfg[1] != 0x04 || cfg[2] != 0xEE {
		t.Errorf("Configure: expected CONFIG=0x04EE, got %v", cfg)
	}

	// ConversionReady(): CVRF bit (0x0008) set
	conn.setRegister(ina226RegMask, 0x00, 0x08)
	if v, err := sensor.ConversionReady(); err != nil || !v {
		t.Errorf("ConversionReady() = %v, %v, want true, nil", v, err)
	}

	// Overflow(): OVF bit (0x0004) set
	conn.setRegister(ina226RegMask, 0x00, 0x04)
	if v, err := sensor.Overflow(); err != nil || !v {
		t.Errorf("Overflow() = %v, %v, want true, nil", v, err)
	}

	// SetAlert(POL, 1.5, true, true): raw = uint16(1.5/(25*currentLSB)) = 983 (0x03D7);
	// mask = POL | 0x0002 | 0x0001 = 0x0803
	if err := sensor.SetAlert(POL, 1.5, true, true); err != nil {
		t.Fatalf("SetAlert: %v", err)
	}
	if m := lastWriteTo(conn.writes, ina226RegMask); m == nil || m[1] != 0x08 || m[2] != 0x03 {
		t.Errorf("SetAlert: expected Mask/Enable=0x0803, got %v", m)
	}
	if a := lastWriteTo(conn.writes, ina226RegAlert); a == nil || a[1] != 0x03 || a[2] != 0xD7 {
		t.Errorf("SetAlert: expected Alert Limit=0x03D7, got %v", a)
	}

	// AlertFlags(): raw Mask/Enable register
	conn.setRegister(ina226RegMask, 0x08, 0x03)
	if v, err := sensor.AlertFlags(); err != nil || v != 0x0803 {
		t.Errorf("AlertFlags() = %v, %v, want 0x0803, nil", v, err)
	}

	// Reset(): writes CONFIG=0x8000, then re-writes CAL
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	n := len(conn.writes)
	if n < 2 || conn.writes[n-2][1] != 0x80 || conn.writes[n-2][2] != 0x00 {
		t.Errorf("Reset: expected CONFIG=0x8000, got %v", conn.writes[n-2])
	}
	if conn.writes[n-1][1] != 0x03 || conn.writes[n-1][2] != 0x46 {
		t.Errorf("Reset: expected CAL=0x0346, got %v", conn.writes[n-1])
	}

	// Shutdown(): reads CONFIG, saves mode, writes CONFIG & 0xFFF8
	conn.setRegister(ina226RegConfig, 0x41, 0x27)
	if err := sensor.Shutdown(); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; last[1] != 0x41 || last[2] != 0x20 {
		t.Errorf("Shutdown: expected CONFIG=0x4120, got %v", last)
	}

	// Wake(): reads CONFIG, writes back with saved mode restored
	conn.setRegister(ina226RegConfig, 0x41, 0x20)
	if err := sensor.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; last[1] != 0x41 || last[2] != 0x27 {
		t.Errorf("Wake: expected CONFIG=0x4127, got %v", last)
	}

	// ManufacturerID() / DieID()
	conn.setRegister(ina226RegMfrID, 0x54, 0x49)
	if v, err := sensor.ManufacturerID(); err != nil || v != 0x5449 {
		t.Errorf("ManufacturerID() = %v, %v, want 0x5449, nil", v, err)
	}
	conn.setRegister(ina226RegDieID, 0x22, 0x60)
	if v, err := sensor.DieID(); err != nil || v != 0x2260 {
		t.Errorf("DieID() = %v, %v, want 0x2260, nil", v, err)
	}
}
