package accelerometer

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

type lsm303MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newLSM303MockConnection() *lsm303MockConnection {
	return &lsm303MockConnection{registers: map[byte]byte{}}
}

func (m *lsm303MockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *lsm303MockConnection) Write(data []byte) error {
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

func (m *lsm303MockConnection) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *lsm303MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *lsm303MockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *lsm303MockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *lsm303MockConnection) Close() error                { return nil }
func (m *lsm303MockConnection) Enable()                     {}
func (m *lsm303MockConnection) Disable()                    {}
func (m *lsm303MockConnection) IsEnabled() bool             { return true }
func (m *lsm303MockConnection) IntPin() connection.InputPin { return nil }
func (m *lsm303MockConnection) EnPin() connection.OutputPin { return nil }

func newLSM303Mocks() (*lsm303MockConnection, *lsm303MockConnection) {
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(0x0A, 0x48, 0x34, 0x33)
	accel.setRegister(0x28|0x80, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00)
	mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4)
	return accel, mag
}

func lastLSM303Write(m *lsm303MockConnection, reg byte) (byte, bool) {
	var value byte
	found := false
	for _, w := range m.writes {
		if len(w) == 2 && w[0] == reg {
			value = w[1]
			found = true
		}
	}
	return value, found
}

func TestLSM303Minimal(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatal(err)
	}
	if v, ok := lastLSM303Write(accel, 0x23); !ok || v != 0x88 {
		t.Errorf("CTRL_REG4 default = %#x", v)
	}
	if v, ok := lastLSM303Write(accel, 0x20); !ok || v != 0x57 {
		t.Errorf("CTRL_REG1 default = %#x", v)
	}
	if v, ok := lastLSM303Write(mag, 0x01); !ok || v != 0x20 {
		t.Errorf("CRB default = %#x", v)
	}
	x, y, z, err := chip.ReadAcceleration()
	if err != nil {
		t.Fatal(err)
	}
	if math.Abs(x-0.001) > 1e-9 || math.Abs(y-0.002) > 1e-9 || math.Abs(z-0.003) > 1e-9 {
		t.Errorf("acceleration = %v %v %v", x, y, z)
	}
	mx, my, mz, err := chip.ReadMagnetic()
	if err != nil {
		t.Fatal(err)
	}
	if math.Abs(mx-100) > 1e-6 || math.Abs(my+100) > 1e-6 || math.Abs(mz-100) > 1e-6 {
		t.Errorf("magnetic = %v %v %v", mx, my, mz)
	}
}

func TestLSM303Full(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatal(err)
	}
	if err := chip.SetRange(4); err != nil {
		t.Fatal(err)
	}
	x, _, _, _ := chip.ReadAcceleration()
	if math.Abs(x-0.002) > 1e-9 {
		t.Errorf("4g sensitivity x = %v", x)
	}
	if err := chip.SetDataRate(400); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x20); v>>4 != 0x07 {
		t.Errorf("ODR code = %#x", v)
	}
	if err := chip.SetMode(LSM303ModeNormal); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x23); v&0x08 != 0 {
		t.Errorf("HR not cleared: %#x", v)
	}
	if err := chip.SetRange(2); err != nil {
		t.Fatal(err)
	}
	if err := chip.SetMotionInterrupt(1, 0x3F, 0.5, 0, true); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x32); v != 32 {
		t.Errorf("threshold code = %d", v)
	}
	if err := chip.SetFreeFall(1, 0.35, 30); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x30); v != 0x95 {
		t.Errorf("free-fall cfg = %#x", v)
	}
	if err := chip.SetClick(0, 0x04, 0.5, 10, 20, 50); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x38); v&0x20 == 0 {
		t.Errorf("click cfg = %#x", v)
	}
	if err := chip.EnableInterrupt(SourceDataReady, 1); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(accel, 0x22); v&0x10 == 0 {
		t.Errorf("CTRL_REG3 = %#x", v)
	}
	if err := chip.EnableInterrupt(SourceMagDataReady, 1); err == nil {
		t.Error("mag DRDY should not be routable")
	}
	if err := chip.SetMagRange(470); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(mag, 0x01); v != 0xA0 {
		t.Errorf("CRB 470uT = %#x", v)
	}
	mag.setRegister(0x03, 0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C)
	mx, _, _, _ := chip.ReadMagnetic()
	if math.Abs(mx-100) > 1e-6 {
		t.Errorf("mag gain x = %v", mx)
	}
	if err := chip.EnableTemperature(true); err != nil {
		t.Fatal(err)
	}
	if err := chip.SetMagDataRate(30); err != nil {
		t.Fatal(err)
	}
	if v, _ := lastLSM303Write(mag, 0x00); v&0x1C != 0x14 || v&0x80 == 0 {
		t.Errorf("CRA = %#x", v)
	}
	id, err := chip.ReadMagIdentity()
	if err != nil || id[0] != 0x48 || id[1] != 0x34 || id[2] != 0x33 {
		t.Errorf("identity = %v, err %v", id, err)
	}
	mag.setRegister(0x31, 0x00, 0x80)
	temp, err := chip.ReadTemperatureDelta()
	if err != nil || math.Abs(temp-1.0) > 1e-9 {
		t.Errorf("temperature = %v, err %v", temp, err)
	}
	if err := chip.Reboot(); err != nil {
		t.Fatal(err)
	}
}

func TestLSM303BadIdentity(t *testing.T) {
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	if _, err := NewLSM303Minimal(accel, mag); err == nil {
		t.Error("expected identity error")
	}
}
