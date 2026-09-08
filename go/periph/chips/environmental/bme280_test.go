package environmental

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockRegConnection is an in-memory fake connection.Connection for unit
// tests — no hardware, no bus. Named distinctly from aht21_test.go's
// mockConnection (which only supports AHT21's plain streamed protocol):
// BME280/BME680 use register-addressed reads (WriteRead([]byte{reg}, n)),
// backed by a byte-addressable registers map. Preload it with setRegister.
//
// Every Write call is appended to writes for assertions, and 2+ byte
// writes are also applied to registers so a later WriteRead sees them.
type mockRegConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newMockRegConnection() *mockRegConnection {
	return &mockRegConnection{registers: map[byte]byte{}}
}

func (m *mockRegConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockRegConnection) Write(data []byte) error {
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

func (m *mockRegConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *mockRegConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockRegConnection) Close() error                { return nil }
func (m *mockRegConnection) Enable()                     {}
func (m *mockRegConnection) Disable()                    {}
func (m *mockRegConnection) IsEnabled() bool             { return true }
func (m *mockRegConnection) IntPin() connection.InputPin { return nil }
func (m *mockRegConnection) EnPin() connection.OutputPin { return nil }

func lastRegWrite(writes [][]byte, reg byte) []byte {
	var last []byte
	for _, w := range writes {
		if len(w) >= 1 && w[0] == reg {
			last = w
		}
	}
	return last
}

// Calibration NVM block 1 (26 bytes from 0x88), from the BMP280 datasheet's
// worked example (digT1=27504, digT2=26435, digT3=-1000, digP1=36477,
// digP2=-10685, digP3=3024, digP4=2855, digP5=140, digP6=-7, digP7=15500,
// digP8=-14600, digP9=6000), plus digH1=75 at 0xA1.
var bme280Cal1 = []byte{
	0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
	0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
	0x70, 0x17, 0x00, 0x4B,
}

// Calibration NVM block 2 (7 bytes from 0xE1): digH2=384, digH3=0,
// digH4=301, digH5=50, digH6=30.
var bme280Cal2 = []byte{0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E}

// ADC burst (8 bytes from 0xF7): adcP=415148, adcT=519888, adcH=32768.
var bme280Adc = []byte{0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00}

const (
	bme280ExpectedT = 25.08
	bme280ExpectedP = 1006.5325390625
	bme280ExpectedH = 79.0869140625
)

func closeEnough(a, b, tol float64) bool {
	return math.Abs(a-b) < tol
}

func TestBME280FullAPI(t *testing.T) {
	conn := newMockRegConnection()
	conn.setRegister(bme280RegCalStart, bme280Cal1...)
	conn.setRegister(bme280RegCalH2, bme280Cal2...)
	conn.setRegister(bme280RegData, bme280Adc...)

	sensor, err := NewBME280Full(conn)
	if err != nil {
		t.Fatalf("NewBME280Full: %v", err)
	}

	ctrlHumWrites := 0
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == bme280RegCtrlHum {
			ctrlHumWrites++
		}
	}
	if ctrlHumWrites < 1 {
		t.Errorf("init: expected at least one ctrl_hum write")
	}
	if v := conn.registers[bme280RegCtrlHum]; v != 1 {
		t.Errorf("init: ctrl_hum = 0x%02X, want 0x01", v)
	}
	if v := conn.registers[bme280RegCtrlMeas]; v != ((1 << 5) | (1 << 2) | 0) {
		t.Errorf("init: ctrl_meas = 0x%02X, want 0x24", v)
	}

	temp, err := sensor.Temperature()
	if err != nil || !closeEnough(float64(temp), bme280ExpectedT, 0.01) {
		t.Errorf("Temperature() = %v, %v, want ~%v", temp, err, bme280ExpectedT)
	}
	press, err := sensor.Pressure()
	if err != nil || !closeEnough(float64(press), bme280ExpectedP, 0.01) {
		t.Errorf("Pressure() = %v, %v, want ~%v", press, err, bme280ExpectedP)
	}
	hum, err := sensor.Humidity()
	if err != nil || !closeEnough(float64(hum), bme280ExpectedH, 0.01) {
		t.Errorf("Humidity() = %v, %v, want ~%v", hum, err, bme280ExpectedH)
	}

	if last := lastRegWrite(conn.writes, bme280RegCtrlMeas); last == nil || last[1]&0x03 != 1 {
		t.Errorf("trigger: expected last ctrl_meas write with mode=forced, got %v", last)
	}

	if err := sensor.Configure(2, 3, 1, 3, 2, 5); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if v := conn.registers[bme280RegCtrlHum]; v != 1 {
		t.Errorf("Configure: ctrl_hum = 0x%02X, want 0x01", v)
	}
	if v := conn.registers[bme280RegConfig]; v != ((5 << 5) | (2 << 2)) {
		t.Errorf("Configure: config = 0x%02X, want 0xA8", v)
	}
	if v := conn.registers[bme280RegCtrlMeas]; v != ((2 << 5) | (3 << 2) | 3) {
		t.Errorf("Configure: ctrl_meas = 0x%02X, want 0x4F", v)
	}

	if err := sensor.SetOversampling(3, 4, 2); err != nil {
		t.Fatalf("SetOversampling: %v", err)
	}
	if v := conn.registers[bme280RegCtrlHum]; v != 2 {
		t.Errorf("SetOversampling: ctrl_hum = 0x%02X, want 0x02", v)
	}
	if v := conn.registers[bme280RegCtrlMeas]; v != ((3 << 5) | (4 << 2) | 3) {
		t.Errorf("SetOversampling: ctrl_meas = 0x%02X, want 0x73", v)
	}

	if err := sensor.SetMode(1); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if v := conn.registers[bme280RegCtrlMeas]; v != ((3 << 5) | (4 << 2) | 1) {
		t.Errorf("SetMode: ctrl_meas = 0x%02X, want 0x71", v)
	}

	if err := sensor.SetFilter(3); err != nil {
		t.Fatalf("SetFilter: %v", err)
	}
	if v := conn.registers[bme280RegConfig]; v != ((5 << 5) | (3 << 2)) {
		t.Errorf("SetFilter: config = 0x%02X, want 0xAC", v)
	}

	if err := sensor.SetStandby(6); err != nil {
		t.Fatalf("SetStandby: %v", err)
	}
	if v := conn.registers[bme280RegConfig]; v != ((6 << 5) | (3 << 2)) {
		t.Errorf("SetStandby: config = 0x%02X, want 0xCC", v)
	}

	conn.setRegister(bme280RegStatus, 0x08)
	if v, _ := sensor.Status(); v != 0x08 {
		t.Errorf("Status() = 0x%02X, want 0x08", v)
	}

	alt, err := sensor.Altitude(1013.25)
	if err != nil || !closeEnough(float64(alt), 56.07668235692459, 0.05) {
		t.Errorf("Altitude() = %v, %v, want ~56.08", alt, err)
	}
	slp, err := sensor.SeaLevelPressure(56.07668235692459)
	if err != nil || !closeEnough(float64(slp), 1013.25, 0.05) {
		t.Errorf("SeaLevelPressure() = %v, %v, want ~1013.25", slp, err)
	}
	dp, err := sensor.DewPoint()
	if err != nil || !closeEnough(float64(dp), 21.191706255732008, 0.05) {
		t.Errorf("DewPoint() = %v, %v, want ~21.19", dp, err)
	}

	conn.setRegister(bme280RegID, bme280ChipID)
	if v, _ := sensor.ChipID(); v != 0x60 {
		t.Errorf("ChipID() = 0x%02X, want 0x60", v)
	}

	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	sawReset := false
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == bme280RegReset && w[1] == bme280ResetCmd {
			sawReset = true
		}
	}
	if !sawReset {
		t.Errorf("Reset: expected reset command write")
	}
	if v := conn.registers[bme280RegCtrlHum]; v != 2 {
		t.Errorf("Reset: ctrl_hum = 0x%02X, want 0x02 (reapplied)", v)
	}
	if v := conn.registers[bme280RegConfig]; v != ((6 << 5) | (3 << 2)) {
		t.Errorf("Reset: config = 0x%02X, want 0xCC (reapplied)", v)
	}
	if v := conn.registers[bme280RegCtrlMeas]; v != ((3 << 5) | (4 << 2) | 1) {
		t.Errorf("Reset: ctrl_meas = 0x%02X, want 0x71 (reapplied)", v)
	}
}
