package gyroscope

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// l3gd20hMockConn implements connection.Connection for testing.
type l3gd20hMockConn struct {
	regs map[uint8][]byte
}

func newL3gd20hMockConn() *l3gd20hMockConn {
	return &l3gd20hMockConn{regs: make(map[uint8][]byte)}
}

func (m *l3gd20hMockConn) setReg(reg uint8, data []byte) {
	m.regs[reg] = data
}

func (m *l3gd20hMockConn) Write(data []byte) error {
	return nil
}

func (m *l3gd20hMockConn) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *l3gd20hMockConn) WriteRead(data []byte, n int) ([]byte, error) {
	if len(data) == 0 {
		return make([]byte, n), nil
	}
	reg := data[0] & 0x3F // strip SPI/auto-increment control bits
	if val, ok := m.regs[reg]; ok {
		return val[:n], nil
	}
	return make([]byte, n), nil
}

func (m *l3gd20hMockConn) Close() error { return nil }
func (m *l3gd20hMockConn) Enable()       {}
func (m *l3gd20hMockConn) Disable()      {}
func (m *l3gd20hMockConn) IsEnabled() bool { return true }
func (m *l3gd20hMockConn) IntPin() connection.InputPin { return nil }
func (m *l3gd20hMockConn) EnPin() connection.OutputPin { return nil }

func TestL3GD20H(t *testing.T) {
	mock := newL3gd20hMockConn()
	mock.setReg(l3gd20hRegWHOAMI, []byte{0xD7})
	mock.setReg(l3gd20hRegCtrlReg1, []byte{l3gd20hCtrlReg1Default})
	mock.setReg(l3gd20hRegCtrlReg4, []byte{l3gd20hCtrlReg4Default})
	mock.setReg(l3gd20hRegOutXL, []byte{0x00, 0x01, 0x00, 0x02, 0x00, 0x03})

	gyro, err := NewL3GD20HFull(mock, false)
	if err != nil {
		t.Fatalf("init failed: %v", err)
	}

	// Test AngularRate
	x, y, z, err := gyro.AngularRate()
	if err != nil {
		t.Fatalf("AngularRate error: %v", err)
	}
	if x == 0 && y == 0 && z == 0 {
		t.Errorf("AngularRate returned zeros")
	}

	// Test Configure
	err = gyro.Configure(L3GD20HODR190Hz, 0, L3GD20HFS500DPS)
	if err != nil {
		t.Fatalf("Configure error: %v", err)
	}

	// Test AngularRateRaw
	mock.setReg(l3gd20hRegOutXL, []byte{0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00})
	rx, ry, rz, err := gyro.AngularRateRaw()
	if err != nil {
		t.Fatalf("AngularRateRaw error: %v", err)
	}
	if rx != -32768 || ry != 32767 || rz != 0 {
		t.Errorf("AngularRateRaw got %d %d %d, want -32768 32767 0", rx, ry, rz)
	}

	// Test Temperature
	mock.setReg(l3gd20hRegOutTemp, []byte{0x80})
	temp, err := gyro.Temperature()
	if err != nil {
		t.Fatalf("Temperature error: %v", err)
	}
	if temp != -128 {
		t.Errorf("Temperature got %d, want -128", temp)
	}

	// Test DataReady
	mock.setReg(l3gd20hRegStatus, []byte{0x08})
	drdy, err := gyro.DataReady()
	if err != nil {
		t.Fatalf("DataReady error: %v", err)
	}
	if !drdy {
		t.Errorf("DataReady false, want true")
	}

	// Test ConfigureHighpass
	err = gyro.ConfigureHighpass(L3GD20HHPMReference, 5)
	if err != nil {
		t.Fatalf("ConfigureHighpass error: %v", err)
	}

	// Test EnableHighpass
	err = gyro.EnableHighpass(true)
	if err != nil {
		t.Fatalf("EnableHighpass error: %v", err)
	}
	err = gyro.EnableHighpass(false)
	if err != nil {
		t.Fatalf("DisableHighpass error: %v", err)
	}

	// Test ConfigureFIFO
	err = gyro.ConfigureFIFO(L3GD20HFIFOFIFO, 10)
	if err != nil {
		t.Fatalf("ConfigureFIFO error: %v", err)
	}

	// Test EnableFIFO
	err = gyro.EnableFIFO(true)
	if err != nil {
		t.Fatalf("EnableFIFO error: %v", err)
	}
	err = gyro.EnableFIFO(false)
	if err != nil {
		t.Fatalf("DisableFIFO error: %v", err)
	}

	// Test FIFOLevel
	mock.setReg(l3gd20hRegFifoSrc, []byte{0x05})
	level, err := gyro.FIFOLevel()
	if err != nil {
		t.Fatalf("FIFOLevel error: %v", err)
	}
	if level != 5 {
		t.Errorf("FIFOLevel got %d, want 5", level)
	}

	// Test ReadFIFO
	mock.setReg(l3gd20hRegOutXL, []byte{
		0x10, 0x00, 0x20, 0x00, 0x30, 0x00,
		0x40, 0x00, 0x50, 0x00, 0x60, 0x00,
		0x70, 0x00, 0x80, 0x00, 0x90, 0x00,
		0xA0, 0x00, 0xB0, 0x00, 0xC0, 0x00,
		0xD0, 0x00, 0xE0, 0x00, 0xF0, 0x00,
	})
	samples, err := gyro.ReadFIFO()
	if err != nil {
		t.Fatalf("ReadFIFO error: %v", err)
	}
	if len(samples) != 5 {
		t.Errorf("ReadFIFO got %d samples, want 5", len(samples))
	}

	// Test SetPowerMode
	err = gyro.SetPowerMode(L3GD20HPowerNormal)
	if err != nil {
		t.Fatalf("SetPowerMode normal error: %v", err)
	}
	err = gyro.SetPowerMode(L3GD20HPowerSleep)
	if err != nil {
		t.Fatalf("SetPowerMode sleep error: %v", err)
	}
	err = gyro.SetPowerMode(L3GD20HPowerPowerDown)
	if err != nil {
		t.Fatalf("SetPowerMode power_down error: %v", err)
	}
}