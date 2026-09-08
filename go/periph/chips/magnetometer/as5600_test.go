package magnetometer

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

func TestAS5600FullAPI(t *testing.T) {
	conn := newMockConnection()
	// STATUS: MD=1 (magnet detected), MH=0, ML=0.
	conn.setRegister(as5600RegStatus, 0x08)

	sensor, err := NewAs5600Full(conn)
	if err != nil {
		t.Fatalf("NewAs5600Full: %v", err)
	}

	if md, err := sensor.IsMagnetDetected(); err != nil || !md {
		t.Errorf("IsMagnetDetected() = %v, %v, want true, nil", md, err)
	}
	if strong, err := sensor.IsMagnetTooStrong(); err != nil || strong {
		t.Errorf("IsMagnetTooStrong() = %v, %v, want false, nil", strong, err)
	}
	if weak, err := sensor.IsMagnetTooWeak(); err != nil || weak {
		t.Errorf("IsMagnetTooWeak() = %v, %v, want false, nil", weak, err)
	}

	// ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291.
	conn.setRegister(as5600RegAngleH, 0x01, 0x23)
	if r, err := sensor.AngleRaw(); err != nil || r != 291 {
		t.Errorf("AngleRaw() = %v, %v, want 291, nil", r, err)
	}
	if a, err := sensor.Angle(); err != nil || a != 291.0*360.0/4096.0 {
		t.Errorf("Angle() = %v, %v, want %v, nil", a, err, 291.0*360.0/4096.0)
	}

	// RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees.
	conn.setRegister(as5600RegRawAngleH, 0x02, 0x00)
	if r, err := sensor.RawAngle(); err != nil || r != 512 {
		t.Errorf("RawAngle() = %v, %v, want 512, nil", r, err)
	}
	if d, err := sensor.RawAngleDegrees(); err != nil || d != 45.0 {
		t.Errorf("RawAngleDegrees() = %v, %v, want 45.0, nil", d, err)
	}

	conn.setRegister(as5600RegAGC, 128)
	if v, err := sensor.AGC(); err != nil || v != 128 {
		t.Errorf("AGC() = %v, %v, want 128, nil", v, err)
	}

	// MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100.
	conn.setRegister(as5600RegMagnitudeH, 0x00, 0x64)
	if v, err := sensor.Magnitude(); err != nil || v != 100 {
		t.Errorf("Magnitude() = %v, %v, want 100, nil", v, err)
	}

	// STATUS: MD=1, MH=1 (magnet too strong).
	conn.setRegister(as5600RegStatus, 0x28)
	if strong, err := sensor.IsMagnetTooStrong(); err != nil || !strong {
		t.Errorf("IsMagnetTooStrong() = %v, %v, want true, nil", strong, err)
	}
	if sb, err := sensor.StatusByte(); err != nil || sb != 0x28 {
		t.Errorf("StatusByte() = %v, %v, want 0x28, nil", sb, err)
	}

	// Configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5).
	conn.setRegister(as5600RegConfH, 0xC5, 0x00)
	if err := sensor.Configure(1, 2, 1, 3, 2, 5, true); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if conn.registers[as5600RegConfH] != 0xF6 || conn.registers[as5600RegConfL] != 0xD9 {
		t.Errorf("Configure: CONF registers wrong: %v", conn.registers)
	}

	if err := sensor.SetZeroPosition(1000); err != nil {
		t.Fatalf("SetZeroPosition: %v", err)
	}
	if v, err := sensor.ZeroPosition(); err != nil || v != 1000 {
		t.Errorf("ZeroPosition() = %v, %v, want 1000, nil", v, err)
	}

	if err := sensor.SetMaxPosition(2000); err != nil {
		t.Fatalf("SetMaxPosition: %v", err)
	}
	if v, err := sensor.MaxPosition(); err != nil || v != 2000 {
		t.Errorf("MaxPosition() = %v, %v, want 2000, nil", v, err)
	}

	if err := sensor.SetMaxAngle(2048); err != nil {
		t.Fatalf("SetMaxAngle: %v", err)
	}
	if v, err := sensor.MaxAngle(); err != nil || v != 2048 {
		t.Errorf("MaxAngle() = %v, %v, want 2048, nil", v, err)
	}

	conn.setRegister(as5600RegZMCO, 0x02)
	if v, err := sensor.BurnCount(); err != nil || v != 2 {
		t.Errorf("BurnCount() = %v, %v, want 2, nil", v, err)
	}

	// BurnAngle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80 first.
	if err := sensor.BurnAngle(); err != nil {
		t.Fatalf("BurnAngle: %v", err)
	}
	burnWrites := 0
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == as5600RegBurn && w[1] == 0x80 {
			burnWrites++
		}
	}
	if burnWrites != 1 {
		t.Errorf("BurnAngle: expected exactly one BURN=0x80 write, found %d", burnWrites)
	}

	// BurnSetting(): requires ZMCO=0.
	conn.setRegister(as5600RegZMCO, 0x00)
	if err := sensor.BurnSetting(); err != nil {
		t.Fatalf("BurnSetting: %v", err)
	}
	foundBurnSetting := false
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == as5600RegBurn && w[1] == 0x40 {
			foundBurnSetting = true
		}
	}
	if !foundBurnSetting {
		t.Errorf("BurnSetting: expected a BURN=0x40 write, writes were %v", conn.writes)
	}

	// BurnAngle() must error when magnet not detected.
	conn.setRegister(as5600RegStatus, 0x00)
	if err := sensor.BurnAngle(); err == nil {
		t.Errorf("BurnAngle(): expected error when magnet not detected")
	}

	// BurnAngle() must error when ZMCO limit (3) reached.
	conn.setRegister(as5600RegStatus, 0x08)
	conn.setRegister(as5600RegZMCO, 0x03)
	if err := sensor.BurnAngle(); err == nil {
		t.Errorf("BurnAngle(): expected error when ZMCO=3")
	}

	// BurnSetting() must error when ZMCO != 0.
	conn.setRegister(as5600RegZMCO, 0x01)
	if err := sensor.BurnSetting(); err == nil {
		t.Errorf("BurnSetting(): expected error when ZMCO != 0")
	}

	// NewAs5600Full must error when no magnet is detected.
	noMagnetConn := newMockConnection()
	noMagnetConn.setRegister(as5600RegStatus, 0x00)
	if _, err := NewAs5600Full(noMagnetConn); err == nil {
		t.Errorf("NewAs5600Full(): expected error when magnet not detected")
	}
}
