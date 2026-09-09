package pressure

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests -
// no hardware, no bus. See go/periph/chips/power/ina226_test.go for the
// fuller doc comment; this is a per-package copy since Go has no shared
// test-only package across `pressure` and `power`.
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

func preloadCalibration(conn *mockConnection) {
	// Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
	// AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
	// MB=-32768, MC=-8711, MD=2868.
	conn.setRegister(bmp180RegCalStart,
		0x01, 0x98, // AC1 = 408
		0xFF, 0xB8, // AC2 = -72
		0xC7, 0xD1, // AC3 = -14383
		0x7F, 0xE5, // AC4 = 32741
		0x7F, 0xF5, // AC5 = 32757
		0x5A, 0x71, // AC6 = 23153
		0x18, 0x2E, // B1 = 6190
		0x00, 0x04, // B2 = 4
		0x80, 0x00, // MB = -32768
		0xDD, 0xF9, // MC = -8711
		0x0B, 0x34, // MD = 2868
	)
}

func TestBMP180FullAPI(t *testing.T) {
	conn := newMockConnection()
	preloadCalibration(conn)
	// Unlike Python/C++/JS/Rust, the Go constructor verifies the chip ID
	// register before reading calibration.
	conn.setRegister(bmp180RegID, 0x55)

	sensor, err := NewBmp180Full(conn)
	if err != nil {
		t.Fatalf("NewBmp180Full: %v", err)
	}

	// Pressure() re-reads OUT_MSB for both UT (2 bytes) and UP (3 bytes)
	// from the same register within one call, and this mock always returns
	// the register map's current contents - it cannot hand back a different
	// UT then a different UP within a single call. So UT and the top 16
	// bits of UP are necessarily the same value here (0x6CFA = 27898); the
	// expected T/p below are computed from the real compensation formula
	// with UT=UP=27898, not the datasheet's mismatched worked example.
	conn.setRegister(bmp180RegOutMsb, 0x6C, 0xFA)
	if v, err := sensor.Temperature(); err != nil || v != 15.0 {
		t.Errorf("Temperature() = %v, %v, want 15.0, nil", v, err)
	}
	if w := lastWriteTo(conn.writes, bmp180RegCtrlMeas); w == nil || w[1] != bmp180CmdTemp {
		t.Errorf("Temperature: expected CTRL_MEAS=CMD_TEMP, got %v", w)
	}

	conn.setRegister(bmp180RegOutMsb, 0x6C, 0xFA)
	if v, err := sensor.Pressure(); err != nil || math.Abs(v-820.8) > 1e-6 {
		t.Errorf("Pressure() = %v, %v, want 820.8, nil", v, err)
	}
	if w := lastWriteTo(conn.writes, bmp180RegCtrlMeas); w == nil || w[1] != bmp180CmdPressOss0 {
		t.Errorf("Pressure: expected CTRL_MEAS=CMD_PRESS_OSS0, got %v", w)
	}

	// ChipID(): expect 0x55.
	conn.setRegister(bmp180RegID, 0x55)
	if v, err := sensor.ChipID(); err != nil || v != 0x55 {
		t.Errorf("ChipID() = %v, %v, want 0x55, nil", v, err)
	}

	// Oversampling()/SetOversampling()
	if v := sensor.Oversampling(); v != 0 {
		t.Errorf("Oversampling() = %v, want 0", v)
	}
	sensor.SetOversampling(OssStandard)
	if v := sensor.Oversampling(); v != 1 {
		t.Errorf("Oversampling() after SetOversampling(1) = %v, want 1", v)
	}
	sensor.SetOversampling(0) // restore ULP

	// Altitude(): Pressure() re-reads UT/UP internally.
	conn.setRegister(bmp180RegOutMsb, 0x6C, 0xFA)
	if v, err := sensor.Altitude(); err != nil || math.Abs(v-1741.7604174) > 0.5 {
		t.Errorf("Altitude() = %v, %v, want ~1741.76, nil", v, err)
	}

	// SeaLevelPressure(altitudeM=100)
	conn.setRegister(bmp180RegOutMsb, 0x6C, 0xFA)
	if v, err := sensor.SeaLevelPressure(100); err != nil || math.Abs(v-830.599010429) > 0.5 {
		t.Errorf("SeaLevelPressure(100) = %v, %v, want ~830.60, nil", v, err)
	}

	// Reset(): writes soft-reset command, then re-reads calibration coefficients.
	preloadCalibration(conn)
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp180RegSoftReset); w == nil || w[1] != bmp180SoftResetCmd {
		t.Errorf("Reset: expected soft-reset write, got %v", w)
	}
	calReads := 0
	for _, w := range conn.writes {
		if len(w) == 1 && w[0] == bmp180RegCalStart {
			calReads++
		}
	}
	if calReads < 2 {
		t.Errorf("Reset: expected calibration to be re-read (>=2 reads), got %d", calReads)
	}

	// Invalid calibration data (a coefficient of 0x0000) is rejected at
	// construction time.
	badConn := newMockConnection()
	badConn.setRegister(bmp180RegID, 0x55)
	badConn.setRegister(bmp180RegCalStart,
		0x00, 0x00, // AC1 = 0 (invalid)
		0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
		0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34,
	)
	if _, err := NewBmp180Full(badConn); err == nil {
		t.Errorf("NewBmp180Full: expected error for invalid calibration (AC1=0)")
	}
}
