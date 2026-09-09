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

func TestINA219FullAPI(t *testing.T) {
	conn := newMockConnection()

	// rShunt=0.1, maxCurrent=2.0 -> currentLSB=2.0/32768,
	// cal=uint16(0.04096/(currentLSB*rShunt)) & 0xFFFE = 0x1A36.
	sensor, err := NewINA219Full(conn, 0.1, 2.0)
	if err != nil {
		t.Fatalf("NewINA219Full: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; !bytesEqual(last, []byte{0x05, 0x1A, 0x36}) {
		t.Errorf("init: expected Calibration write [5 1A 36], got %v", last)
	}

	// Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0.
	conn.setRegister(ina219RegBus, 0x1F, 0x42)
	if v, err := sensor.Voltage(); err != nil || v != 4.0 {
		t.Errorf("Voltage() = %v, %v, want 4.0, nil", v, err)
	}
	if v, err := sensor.ConversionReady(); err != nil || !v {
		t.Errorf("ConversionReady() = %v, %v, want true, nil", v, err)
	}
	if v, err := sensor.Overflow(); err != nil || v {
		t.Errorf("Overflow() = %v, %v, want false, nil", v, err)
	}

	// Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1.
	conn.setRegister(ina219RegBus, 0x1F, 0x41)
	if v, err := sensor.Overflow(); err != nil || !v {
		t.Errorf("Overflow() = %v, %v, want true, nil", v, err)
	}

	// Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V.
	conn.setRegister(ina219RegShunt, 0xFE, 0x0C)
	if v, err := sensor.ShuntVoltage(); err != nil || v != -0.005 {
		t.Errorf("ShuntVoltage() = %v, %v, want -0.005, nil", v, err)
	}

	// Current: raw=1000 (0x03E8) -> 1000 * currentLSB.
	conn.setRegister(ina219RegCurrent, 0x03, 0xE8)
	wantCurrent := float32(1000) * (float32(2.0) / 32768.0)
	if v, err := sensor.Current(); err != nil || v != wantCurrent {
		t.Errorf("Current() = %v, %v, want %v, nil", v, err, wantCurrent)
	}

	// Power: raw=2000 (0x07D0) -> 2000 * 20 * currentLSB.
	conn.setRegister(ina219RegPower, 0x07, 0xD0)
	wantPower := float32(2000) * 20.0 * (float32(2.0) / 32768.0)
	if v, err := sensor.Power(); err != nil || v != wantPower {
		t.Errorf("Power() = %v, %v, want %v, nil", v, err, wantPower)
	}

	// Configure(0, 1, 0x0B, 0x02, 5) -> config = 0x0D95; re-writes Calibration.
	if err := sensor.Configure(0, 1, 0x0B, 0x02, 5); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if cfg := conn.writes[len(conn.writes)-2]; !bytesEqual(cfg, []byte{0x00, 0x0D, 0x95}) {
		t.Errorf("Configure: expected CONFIG write [0 D 95], got %v", cfg)
	}
	if cal := conn.writes[len(conn.writes)-1]; !bytesEqual(cal, []byte{0x05, 0x1A, 0x36}) {
		t.Errorf("Configure: expected Calibration re-write [5 1A 36], got %v", cal)
	}

	// Shutdown(): MODE forced to 0, other CONFIG bits preserved (0x0D95 -> 0x0D90).
	if err := sensor.Shutdown(); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; !bytesEqual(last, []byte{0x00, 0x0D, 0x90}) {
		t.Errorf("Shutdown: expected CONFIG write [0 D 90], got %v", last)
	}

	// Wake(): restores the previously configured mode (5) -> 0x0D95.
	if err := sensor.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; !bytesEqual(last, []byte{0x00, 0x0D, 0x95}) {
		t.Errorf("Wake: expected CONFIG write [0 D 95], got %v", last)
	}

	// Trigger(): re-writes the current config unchanged.
	if err := sensor.Trigger(); err != nil {
		t.Fatalf("Trigger: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; !bytesEqual(last, []byte{0x00, 0x0D, 0x95}) {
		t.Errorf("Trigger: expected CONFIG write [0 D 95], got %v", last)
	}

	// Reset(): sets RST, re-writes Calibration (no config restore in this driver).
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if rst := conn.writes[len(conn.writes)-2]; !bytesEqual(rst, []byte{0x00, 0x80, 0x00}) {
		t.Errorf("Reset: expected CONFIG write [0 80 0], got %v", rst)
	}
	if cal := conn.writes[len(conn.writes)-1]; !bytesEqual(cal, []byte{0x05, 0x1A, 0x36}) {
		t.Errorf("Reset: expected Calibration re-write [5 1A 36], got %v", cal)
	}
}
