package accelerometer

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// adxl345MockConnection is an in-memory fake connection.Connection for unit
// tests — no hardware, no bus.
type adxl345MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	regReads  [][2]uint32
}

func newADXL345MockConnection() *adxl345MockConnection {
	return &adxl345MockConnection{registers: map[byte]byte{}}
}

func (m *adxl345MockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *adxl345MockConnection) Write(data []byte) error {
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

func (m *adxl345MockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *adxl345MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *adxl345MockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *adxl345MockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *adxl345MockConnection) Close() error                { return nil }
func (m *adxl345MockConnection) Enable()                     {}
func (m *adxl345MockConnection) Disable()                    {}
func (m *adxl345MockConnection) IsEnabled() bool             { return true }
func (m *adxl345MockConnection) IntPin() connection.InputPin { return nil }
func (m *adxl345MockConnection) EnPin() connection.OutputPin { return nil }

func newADXL345Connection() *adxl345MockConnection {
	c := newADXL345MockConnection()
	c.setRegister(adxl345RegDevID, 0xE5)
	c.setRegister(adxl345RegDataX0, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00) // x=1,y=2,z=3
	return c
}

func lastWriteToADXL345(conn *adxl345MockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func abs32ADXL345(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func TestADXL345MinimalConstructionAndRead(t *testing.T) {
	conn := newADXL345Connection()
	chip, err := NewADXL345Minimal(conn)
	if err != nil {
		t.Fatalf("NewADXL345Minimal: %v", err)
	}

	if v, ok := lastWriteToADXL345(conn, adxl345RegDataFormat); !ok || v != 0x08 {
		t.Errorf("DATA_FORMAT write = %#x, ok=%v, want 0x08", v, ok)
	}
	if v, ok := lastWriteToADXL345(conn, adxl345RegBWRate); !ok || v != 0x0A {
		t.Errorf("BW_RATE write = %#x, ok=%v, want 0x0A", v, ok)
	}
	if v, ok := lastWriteToADXL345(conn, adxl345RegPowerCtl); !ok || v != 0x08 {
		t.Errorf("POWER_CTL write = %#x, ok=%v, want 0x08", v, ok)
	}

	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32ADXL345(x-0.0039) > 1e-6 || abs32ADXL345(y-0.0078) > 1e-6 || abs32ADXL345(z-0.0117) > 1e-6 {
		t.Errorf("Read() = (%v, %v, %v), want ~(0.0039, 0.0078, 0.0117)", x, y, z)
	}
}

func TestADXL345MinimalConstructionBadDevID(t *testing.T) {
	conn := newADXL345MockConnection()
	conn.setRegister(adxl345RegDevID, 0x00)
	if _, err := NewADXL345Minimal(conn); err == nil {
		t.Error("NewADXL345Minimal with bad DEVID: expected error, got nil")
	}
}

func TestADXL345FullRangeDataRateLowPowerOffset(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}

	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegDataFormat); v != (0x08 | 0x01) {
		t.Errorf("SetRange(4) DATA_FORMAT write = %#x, want 0x09", v)
	}

	conn.setRegister(adxl345RegBWRate, 0x0A)
	if err := full.SetDataRate(100); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegBWRate); v != 0x0A {
		t.Errorf("SetDataRate(100) BW_RATE write = %#x, want 0x0A", v)
	}

	if err := full.SetLowPower(true); err != nil {
		t.Fatalf("SetLowPower: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegBWRate); v&0x10 != 0x10 {
		t.Errorf("SetLowPower(true) BW_RATE write = %#x, want bit 4 set", v)
	}

	if err := full.SetOffset(0.5, -0.5, 0.0); err != nil {
		t.Fatalf("SetOffset: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegOFSX); v != 32 {
		t.Errorf("SetOffset x write = %d, want 32", v)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegOFSY); v != 0xE0 {
		t.Errorf("SetOffset y write = %#x, want 0xE0", v)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegOFSZ); v != 0 {
		t.Errorf("SetOffset z write = %d, want 0", v)
	}
}

func TestADXL345FullTapAndFreeFall(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}
	conn.setRegister(adxl345RegIntEnable, 0x00)
	conn.setRegister(adxl345RegIntMap, 0x00)

	if err := full.SetTapDetection(0.5, 10.0, 0x07, false); err != nil {
		t.Fatalf("SetTapDetection: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegThreshTap); v != 8 {
		t.Errorf("THRESH_TAP write = %d, want 8", v)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegIntEnable); v&ADXL345IntSingleTap == 0 {
		t.Errorf("INT_ENABLE write = %#x, want SINGLE_TAP set", v)
	}

	// 0.3g / 62.5mg = 4.8 -> rounds to 5.
	if err := full.SetFreeFall(0.3, 100); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegThreshFF); v != 5 {
		t.Errorf("THRESH_FF write = %d, want 5 (round, not truncate)", v)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegTimeFF); v != 20 {
		t.Errorf("TIME_FF write = %d, want 20", v)
	}
}

func TestADXL345FullActivityAndInactivity(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}
	conn.setRegister(adxl345RegActInactCtl, 0x00)
	conn.setRegister(adxl345RegIntEnable, 0x00)
	conn.setRegister(adxl345RegIntMap, 0x00)

	if err := full.SetActivity(0.5, 0x70, true); err != nil {
		t.Fatalf("SetActivity: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegActInactCtl); v != 0xF0 {
		t.Errorf("ACT_INACT_CTL write = %#x, want 0xF0", v)
	}

	// time_sec=2.7 -> rounds to 3, not truncated to 2.
	if err := full.SetInactivity(0.5, 2.7, 0x07, false); err != nil {
		t.Fatalf("SetInactivity: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegTimeInact); v != 3 {
		t.Errorf("TIME_INACT write = %d, want 3 (round, not truncate)", v)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegActInactCtl); v != 0xF7 {
		t.Errorf("ACT_INACT_CTL write = %#x, want 0xF7", v)
	}
}

func TestADXL345FullInterruptRoutingAndSource(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}
	conn.setRegister(adxl345RegIntEnable, 0x00)
	conn.setRegister(adxl345RegIntMap, 0x00)

	if err := full.SetInterrupt(ADXL345IntWatermark, true, 2); err != nil {
		t.Fatalf("SetInterrupt: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegIntMap); v&ADXL345IntWatermark == 0 {
		t.Errorf("INT_MAP write = %#x, want WATERMARK routed to INT2", v)
	}

	conn.setRegister(adxl345RegIntSource, 0x44)
	src, err := full.ReadInterruptSource()
	if err != nil || src != 0x44 {
		t.Errorf("ReadInterruptSource() = %#x, %v, want 0x44, nil", src, err)
	}
}

func TestADXL345FullFifo(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}

	if err := full.SetFifoMode(ADXL345FifoStream, 16); err != nil {
		t.Fatalf("SetFifoMode: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegFifoCtl); v != (ADXL345FifoStream | 16) {
		t.Errorf("FIFO_CTL write = %#x, want %#x", v, ADXL345FifoStream|16)
	}

	conn.setRegister(adxl345RegFifoStatus, 3)
	n, err := full.FifoCount()
	if err != nil || n != 3 {
		t.Errorf("FifoCount() = %d, %v, want 3, nil", n, err)
	}

	samples, err := full.ReadFifo(4)
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if len(samples) != 3 {
		t.Fatalf("ReadFifo() returned %d samples, want 3", len(samples))
	}
	if abs32ADXL345(samples[0][0]-0.0039) > 1e-6 {
		t.Errorf("ReadFifo() sample 0 x = %v, want ~0.0039", samples[0][0])
	}

	// Truncation to maxSamples.
	truncated, err := full.ReadFifo(2)
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if len(truncated) != 2 {
		t.Errorf("ReadFifo(2) returned %d samples, want 2 (truncated)", len(truncated))
	}
}

func TestADXL345FullSleepLinkAutoSleepSelfTest(t *testing.T) {
	conn := newADXL345Connection()
	full, err := NewADXL345Full(conn)
	if err != nil {
		t.Fatalf("NewADXL345Full: %v", err)
	}

	if err := full.SetSleep(true, 8); err != nil {
		t.Fatalf("SetSleep: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegPowerCtl); v&0x04 != 0x04 {
		t.Errorf("SetSleep(true) POWER_CTL write = %#x, want Sleep bit set", v)
	}

	if err := full.SetSleep(false, 8); err != nil {
		t.Fatalf("SetSleep: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegPowerCtl); v&0x04 != 0x00 {
		t.Errorf("SetSleep(false) POWER_CTL write = %#x, want Sleep bit clear", v)
	}

	if err := full.SetLinkMode(true); err != nil {
		t.Fatalf("SetLinkMode: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegPowerCtl); v&0x40 != 0x40 {
		t.Errorf("SetLinkMode(true) POWER_CTL write = %#x, want Link bit set", v)
	}

	if err := full.SetAutoSleep(true); err != nil {
		t.Fatalf("SetAutoSleep: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegPowerCtl); v&0x20 != 0x20 {
		t.Errorf("SetAutoSleep(true) POWER_CTL write = %#x, want AUTO_SLEEP bit set", v)
	}

	if err := full.SelfTest(true); err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegDataFormat); v&0x80 != 0x80 {
		t.Errorf("SelfTest(true) DATA_FORMAT write = %#x, want SELF_TEST bit set", v)
	}
	if err := full.SelfTest(false); err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if v, _ := lastWriteToADXL345(conn, adxl345RegDataFormat); v&0x80 != 0x00 {
		t.Errorf("SelfTest(false) DATA_FORMAT write = %#x, want SELF_TEST bit clear", v)
	}
}

// SPI command-byte framing (R/W|MB|A5..A0) lives in SPIConnection.ReadReg/
// WriteReg now; the driver must address registers through RegisterConnection
// and fetch the six data bytes in one burst so MB gets set on SPI.
func TestADXL345RegisterBurst(t *testing.T) {
	conn := newADXL345Connection()
	chip, err := NewADXL345Minimal(conn)
	if err != nil {
		t.Fatalf("NewADXL345Minimal: %v", err)
	}
	conn.regReads = nil
	if _, _, _, err := chip.Read(); err != nil {
		t.Fatalf("Read: %v", err)
	}
	if len(conn.regReads) != 1 || conn.regReads[0] != [2]uint32{uint32(adxl345RegDataX0), 6} {
		t.Errorf("Read register reads = %v, want one 6-byte burst at DATAX0", conn.regReads)
	}
}
