package accelerometer

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// adxl362MockConnection is an in-memory fake connection.Connection for unit
// tests. ADXL362's SPI framing is opcode+address+data (WRITE 0x0A addr data,
// READ 0x0B addr data), unlike RFM9x's single-byte-is-the-address framing --
// Write's/WriteRead's real target register is the SECOND byte, not the first.
type adxl362MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newADXL362MockConnection() *adxl362MockConnection {
	return &adxl362MockConnection{registers: map[byte]byte{}}
}

func (m *adxl362MockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *adxl362MockConnection) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if len(data) >= 3 && data[0] == adxl362CmdWriteReg {
		reg := data[1]
		for i, v := range data[2:] {
			m.registers[reg+byte(i)] = v
		}
	}
	return nil
}

func (m *adxl362MockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *adxl362MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	m.writes = append(m.writes, append([]byte(nil), data...))
	out := make([]byte, n)
	var reg byte
	if len(data) == 2 && data[0] == adxl362CmdReadReg {
		reg = data[1]
	} else if len(data) > 0 {
		reg = data[0]
	} else {
		return out, nil
	}
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *adxl362MockConnection) Close() error                { return nil }
func (m *adxl362MockConnection) Enable()                     {}
func (m *adxl362MockConnection) Disable()                    {}
func (m *adxl362MockConnection) IsEnabled() bool             { return true }
func (m *adxl362MockConnection) IntPin() connection.InputPin { return nil }
func (m *adxl362MockConnection) EnPin() connection.OutputPin { return nil }

func bytesEqualADXL362(a, b []byte) bool {
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

func newADXL362Connection() *adxl362MockConnection {
	c := newADXL362MockConnection()
	c.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x01) // DEVID_AD, DEVID_MST, PARTID, REVID
	return c
}

func TestADXL362MinimalConstructionAndRead(t *testing.T) {
	conn := newADXL362Connection()
	chip, err := NewADXL362Minimal(conn)
	if err != nil {
		t.Fatalf("NewADXL362Minimal: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-2], []byte{0x0A, 0x2C, 0x13}) {
		t.Errorf("init FILTER_CTL write = %v, want [0x0A 0x2C 0x13]", conn.writes[len(conn.writes)-2])
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2D, 0x02}) {
		t.Errorf("init POWER_CTL write = %v, want [0x0A 0x2D 0x02]", conn.writes[len(conn.writes)-1])
	}

	conn.setRegister(0x0E, 0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07) // x=100,y=-50,z=2000 raw
	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32(x-0.1) > 1e-6 || abs32(y-(-0.05)) > 1e-6 || abs32(z-2.0) > 1e-6 {
		t.Errorf("Read() = (%v, %v, %v), want (0.1, -0.05, 2.0)", x, y, z)
	}
}

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func TestADXL362FullDeviceIDAndSoftReset(t *testing.T) {
	conn := newADXL362Connection()
	full, err := NewADXL362Full(conn)
	if err != nil {
		t.Fatalf("NewADXL362Full: %v", err)
	}

	conn.setRegister(0x00, 0xAD, 0x1D, 0xF2, 0x07)
	ad, mst, pid, rev, err := full.DeviceID()
	if err != nil {
		t.Fatalf("DeviceID: %v", err)
	}
	if ad != 0xAD || mst != 0x1D || pid != 0xF2 || rev != 0x07 {
		t.Errorf("DeviceID() = (%#x %#x %#x %#x), want (0xAD 0x1D 0xF2 0x07)", ad, mst, pid, rev)
	}

	if err := full.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x1F, 0x52}) {
		t.Errorf("SoftReset write = %v, want [0x0A 0x1F 0x52]", conn.writes[len(conn.writes)-1])
	}
}

func TestADXL362FullSetRangeSetOdr(t *testing.T) {
	conn := newADXL362Connection()
	full, _ := NewADXL362Full(conn)

	conn.setRegister(0x2C, 0x13)
	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange(4): %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2C, 0x53}) {
		t.Errorf("SetRange(4) write = %v, want [0x0A 0x2C 0x53]", conn.writes[len(conn.writes)-1])
	}

	conn.setRegister(0x2C, 0x53)
	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange(8): %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2C, 0x93}) {
		t.Errorf("SetRange(8) write = %v, want [0x0A 0x2C 0x93]", conn.writes[len(conn.writes)-1])
	}

	conn.setRegister(0x2C, 0x93)
	if err := full.SetODR(60); err != nil { // nearest of 50/100 -> 50 Hz (code 0x02)
		t.Fatalf("SetODR: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2C, 0x92}) {
		t.Errorf("SetODR(60) write = %v, want [0x0A 0x2C 0x92]", conn.writes[len(conn.writes)-1])
	}
}

func TestADXL362FullRead8bitTemperatureStatus(t *testing.T) {
	conn := newADXL362Connection()
	full, _ := NewADXL362Full(conn)
	conn.setRegister(0x2C, 0x92)
	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange(8): %v", err)
	}

	conn.setRegister(0x08, 100, 206, 50) // x=100, y=-50 (0xCE), z=50
	x, y, z, err := full.Read8bit()
	if err != nil {
		t.Fatalf("Read8bit: %v", err)
	}
	sens8 := float32(0.004255) * 16
	if abs32(x-100*sens8) > 1e-3 || abs32(y-(-50)*sens8) > 1e-3 || abs32(z-50*sens8) > 1e-3 {
		t.Errorf("Read8bit() = (%v, %v, %v)", x, y, z)
	}

	conn.setRegister(0x14, 0xAB, 0x01) // raw 427 = 30 C
	temp, err := full.Temperature()
	if err != nil {
		t.Fatalf("Temperature: %v", err)
	}
	if abs32(temp-30.0) > 0.01 {
		t.Errorf("Temperature() = %v, want ~30.0", temp)
	}

	conn.setRegister(0x0B, 0x41) // AWAKE + DATA_READY
	status, err := full.Status()
	if err != nil || status != 0x41 {
		t.Errorf("Status() = %#x, %v, want 0x41, nil", status, err)
	}
	awake, err := full.Awake()
	if err != nil || !awake {
		t.Errorf("Awake() = %v, %v, want true, nil", awake, err)
	}
}

func TestADXL362FullFifo(t *testing.T) {
	conn := newADXL362Connection()
	full, _ := NewADXL362Full(conn)

	conn.setRegister(0x0C, 0xFF, 0x01) // 0x1FF = 511
	n, err := full.FifoEntries()
	if err != nil || n != 0x1FF {
		t.Fatalf("FifoEntries() = %v, %v, want 0x1FF, nil", n, err)
	}

	if err := full.ConfigureFifo(ADXL362FifoStream, true, 300); err != nil {
		t.Fatalf("ConfigureFifo: %v", err)
	}
	w := conn.writes
	if !bytesEqualADXL362(w[len(w)-2], []byte{0x0A, 0x28, 0x0E}) {
		t.Errorf("ConfigureFifo FIFO_CONTROL write = %v, want [0x0A 0x28 0x0E]", w[len(w)-2])
	}
	if !bytesEqualADXL362(w[len(w)-1], []byte{0x0A, 0x29, 0x2C}) {
		t.Errorf("ConfigureFifo FIFO_SAMPLES write = %v, want [0x0A 0x29 0x2C]", w[len(w)-1])
	}

	conn.setRegister(0x0C, 2, 0) // 2 entries
	conn.setRegister(0x0D, 100, 0, 171, 193)
	entries, err := full.ReadFifo()
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("ReadFifo() len = %d, want 2", len(entries))
	}
	if entries[0].Axis != ADXL362AxisX || abs32(entries[0].Value-100*0.001) > 1e-6 {
		t.Errorf("entries[0] = %+v", entries[0])
	}
	if entries[1].Axis != ADXL362AxisTemp || abs32(entries[1].Value-30.0) > 0.01 {
		t.Errorf("entries[1] = %+v", entries[1])
	}
}

func TestADXL362FullActivityThreshold11BitRegression(t *testing.T) {
	// Regression: THRESH_ACT_H/THRESH_INACT_H are documented as bits [10:8]
	// (3 bits, 11-bit total threshold) -- must not clamp to 10-bit (0x3FF)
	// or mask the H byte with 0x03.
	conn := newADXL362Connection()
	full, _ := NewADXL362Full(conn)
	conn.setRegister(0x2C, 0x92)
	if err := full.SetRange(2); err != nil {
		t.Fatalf("SetRange(2): %v", err)
	}

	conn.setRegister(0x27, 0x00)
	if err := full.SetActivityThreshold(1.5, true); err != nil {
		t.Fatalf("SetActivityThreshold: %v", err)
	}
	// raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
	// writes[len-2] is the readReg(ACT_INACT_CTL) command phase, not a write.
	w := conn.writes
	if !bytesEqualADXL362(w[len(w)-4], []byte{0x0A, 0x20, 0xDC}) {
		t.Errorf("THRESH_ACT_L write = %v, want [0x0A 0x20 0xDC]", w[len(w)-4])
	}
	if !bytesEqualADXL362(w[len(w)-3], []byte{0x0A, 0x21, 0x05}) {
		t.Errorf("THRESH_ACT_H write = %v, want [0x0A 0x21 0x05] (11-bit)", w[len(w)-3])
	}
	if !bytesEqualADXL362(w[len(w)-1], []byte{0x0A, 0x27, 0x02}) {
		t.Errorf("ACT_INACT_CTL write = %v, want [0x0A 0x27 0x02]", w[len(w)-1])
	}
}

func TestADXL362FullLinkLoopInterruptSelfTest(t *testing.T) {
	conn := newADXL362Connection()
	full, _ := NewADXL362Full(conn)

	conn.setRegister(0x27, 0x05)
	if err := full.SetLinkLoopMode(ADXL362LinkLoopLoop); err != nil {
		t.Fatalf("SetLinkLoopMode: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x27, 0x35}) {
		t.Errorf("SetLinkLoopMode write = %v, want [0x0A 0x27 0x35]", conn.writes[len(conn.writes)-1])
	}

	conn.setRegister(0x2A, 0x00)
	if err := full.SetInterrupt(1, ADXL362SourceAwake, true); err != nil {
		t.Fatalf("SetInterrupt: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2A, 0x40}) {
		t.Errorf("SetInterrupt write = %v, want [0x0A 0x2A 0x40]", conn.writes[len(conn.writes)-1])
	}

	conn.setRegister(0x2E, 0x00)
	if err := full.SelfTest(true); err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if !bytesEqualADXL362(conn.writes[len(conn.writes)-1], []byte{0x0A, 0x2E, 0x01}) {
		t.Errorf("SelfTest write = %v, want [0x0A 0x2E 0x01]", conn.writes[len(conn.writes)-1])
	}
}
