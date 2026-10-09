package accelerometer

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// bma150MockConnection is an in-memory fake connection.RegisterConnection
// for unit tests — no hardware, no bus.
type bma150MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	regReads  [][2]uint32
}

func newBMA150MockConnection() *bma150MockConnection {
	return &bma150MockConnection{registers: map[byte]byte{}}
}

func (m *bma150MockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *bma150MockConnection) Write(data []byte) error {
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

func (m *bma150MockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *bma150MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *bma150MockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *bma150MockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *bma150MockConnection) Close() error                { return nil }
func (m *bma150MockConnection) Enable()                     {}
func (m *bma150MockConnection) Disable()                    {}
func (m *bma150MockConnection) IsEnabled() bool             { return true }
func (m *bma150MockConnection) IntPin() connection.InputPin { return nil }
func (m *bma150MockConnection) EnPin() connection.OutputPin { return nil }

func newBMA150Connection() *bma150MockConnection {
	c := newBMA150MockConnection()
	c.setRegister(bma150RegChipID, 0x02)
	c.setRegister(bma150RegRangeBW, 0x00)
	// raw_x = 0x200 -> -2 g; raw_y = 0; raw_z = 0x100 -> 1 g.
	c.setRegister(bma150RegAccXLSB, 0x00, 0x80)
	c.setRegister(bma150RegAccYLSB, 0x00, 0x00)
	c.setRegister(bma150RegAccZLSB, 0x00, 0x40)
	return c
}

func lastWriteToBMA150(conn *bma150MockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func abs32BMA150(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func abs32BMA150i(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func TestBMA150MinimalConstructionAndRead(t *testing.T) {
	conn := newBMA150Connection()
	chip, err := NewBMA150Minimal(conn)
	if err != nil {
		t.Fatalf("NewBMA150Minimal: %v", err)
	}
	// Init writes RANGE_BW = (0x00 & 0xE0) | 0x00 | 0x02 = 0x02.
	if v, ok := lastWriteToBMA150(conn, bma150RegRangeBW); !ok || v != 0x02 {
		t.Errorf("RANGE_BW write = %#x, ok=%v, want 0x02", v, ok)
	}
	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32BMA150(x-(-2.0)) > 1e-6 || abs32BMA150(y-0.0) > 1e-6 || abs32BMA150(z-1.0) > 1e-6 {
		t.Errorf("Read() = (%v, %v, %v), want ~(-2, 0, 1)", x, y, z)
	}
}

func TestBMA150MinimalConstructionBadChipID(t *testing.T) {
	conn := newBMA150MockConnection()
	conn.setRegister(bma150RegChipID, 0xFF)
	if _, err := NewBMA150Minimal(conn); err == nil {
		t.Error("NewBMA150Minimal with bad CHIP_ID: expected error, got nil")
	}
}

func TestBMA150FullRangeAndBandwidth(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	// (0x02 & 0xE0) | 0x08 | (0x02 & 0x07) = 0x0A
	if v, _ := lastWriteToBMA150(conn, bma150RegRangeBW); v != 0x0A {
		t.Errorf("SetRange(4) RANGE_BW write = %#x, want 0x0A", v)
	}
	conn.setRegister(bma150RegRangeBW, 0x0A)
	if err := full.SetBandwidth(190); err != nil {
		t.Fatalf("SetBandwidth: %v", err)
	}
	// (0x0A & 0xF8) | 0x03 = 0x0B
	if v, _ := lastWriteToBMA150(conn, bma150RegRangeBW); v != 0x0B {
		t.Errorf("SetBandwidth(190) RANGE_BW write = %#x, want 0x0B", v)
	}
}

func TestBMA150FullReadRaw(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	rx, ry, rz, err := full.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if rx != -512 || ry != 0 || rz != 256 {
		t.Errorf("ReadRaw() = (%d, %d, %d), want (-512, 0, 256)", rx, ry, rz)
	}
}

func TestBMA150FullReadTemperature(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	conn.setRegister(bma150RegTemp, 0x40)
	temp, err := full.ReadTemperature()
	if err != nil {
		t.Fatalf("ReadTemperature: %v", err)
	}
	if abs32BMA150(temp-2.0) > 1e-6 {
		t.Errorf("ReadTemperature() = %v, want 2.0", temp)
	}
}

func TestBMA150FullLowGAndHighG(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	// range=2: setLowG(0.4, 40): round(0.4 * 255 / 2) = 51.
	if err := full.SetLowG(0.4, 40, 0, 0); err != nil {
		t.Fatalf("SetLowG: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegLGThres); v != 51 {
		t.Errorf("LG_THRES write = %d, want 51", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegLGDur); v != 40 {
		t.Errorf("LG_DUR write = %d, want 40", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegIntCtrl); v&BMA150SourceLowG == 0 {
		t.Errorf("INT_CTRL write = %#x, want SOURCE_LOW_G set", v)
	}
	// setHighG(4.0, 2): round(4.0 * 255 / 2) = 510 -> 255.
	if err := full.SetHighG(4.0, 2, 0, 0); err != nil {
		t.Fatalf("SetHighG: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegHGThres); v != 255 {
		t.Errorf("HG_THRES write = %d, want 255 (clamped)", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegHGDur); v != 2 {
		t.Errorf("HG_DUR write = %d, want 2", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegIntCtrl); v&BMA150SourceHighG == 0 {
		t.Errorf("INT_CTRL write = %#x, want SOURCE_HIGH_G set", v)
	}
}

func TestBMA150FullAnyMotion(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.SetAnyMotion(0.5, 3); err != nil {
		t.Fatalf("SetAnyMotion: %v", err)
	}
	// scale = 256/256 = 1.0; round(0.5 / 0.0156) = 32.
	if v, _ := lastWriteToBMA150(conn, bma150RegAnyMotionThres); v != 32 {
		t.Errorf("ANY_MOTION_THRES write = %d, want 32", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegHystDur); v&0xC0 != 0x40 {
		t.Errorf("HYST_DUR write = %#x, want bits 7:6 = 0x40 (3 samples)", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegConfig); v&0x40 == 0 {
		t.Errorf("CONFIG write = %#x, want bit 6 (enable_adv_INT) set", v)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegIntCtrl); v&0x40 == 0 {
		t.Errorf("INT_CTRL write = %#x, want bit 6 (any-motion enable) set", v)
	}
}

func TestBMA150FullLatchAndClear(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.SetLatch(true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegConfig); v&0x10 == 0 {
		t.Errorf("CONFIG write = %#x, want bit 4 (latch_INT) set", v)
	}
	if err := full.ClearInterrupt(); err != nil {
		t.Fatalf("ClearInterrupt: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegCtrl); v&0x40 == 0 {
		t.Errorf("CTRL write = %#x, want bit 6 (reset_INT) set", v)
	}
}

func TestBMA150FullSleepWakeAndWakeUp(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.Sleep(); err != nil {
		t.Fatalf("Sleep: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegCtrl); v&0x01 == 0 {
		t.Errorf("Sleep CTRL write = %#x, want bit 0 (sleep) set", v)
	}
	if err := full.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegCtrl); v&0x01 != 0 {
		t.Errorf("Wake CTRL write = %#x, want sleep bit cleared", v)
	}
	conn.setRegister(bma150RegConfig, 0x00)
	if err := full.SetWakeUp(true, 80); err != nil {
		t.Fatalf("SetWakeUp: %v", err)
	}
	// (0x00 & 0xF8) | 0x02 | 0x01 = 0x03
	if v, _ := lastWriteToBMA150(conn, bma150RegConfig); (v&0x03) != 0x03 || (v&0x06) != 0x02 {
		t.Errorf("SetWakeUp(80) CONFIG write = %#x, want bits 0,1,2 = 0x03 with 0x02 pause", v)
	}
}

func TestBMA150FullSoftReset(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegCtrl); v&0x02 == 0 {
		t.Errorf("SoftReset CTRL write = %#x, want bit 1 (soft_reset) set", v)
	}
}

func TestBMA150FullSelfTest(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	conn.setRegister(bma150RegStatus, 0x80)
	st, err := full.SelfTest()
	if err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if !st {
		t.Errorf("SelfTest() = false, want true (STATUS.st_result set)")
	}
}

func TestBMA150FullVersionAndCustomer(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	conn.setRegister(bma150RegVersion, 0xAB)
	al, ml, err := full.ReadVersion()
	if err != nil {
		t.Fatalf("ReadVersion: %v", err)
	}
	if al != 0x0A || ml != 0x0B {
		t.Errorf("ReadVersion() = (%d, %d), want (0xA, 0xB)", al, ml)
	}
	conn.setRegister(bma150RegCustomer1, 0xA5)
	c1, err := full.ReadCustomer(0)
	if err != nil || c1 != 0xA5 {
		t.Errorf("ReadCustomer(0) = %d, %v, want 0xA5, nil", c1, err)
	}
	if err := full.WriteCustomer(1, 0x5A); err != nil {
		t.Fatalf("WriteCustomer: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegCustomer2); v != 0x5A {
		t.Errorf("WriteCustomer(1) CUSTOMER_2 write = %#x, want 0x5A", v)
	}
}

func TestBMA150FullSetShadow(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	if err := full.SetShadow(true); err != nil {
		t.Fatalf("SetShadow: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegConfig); v&0x08 == 0 {
		t.Errorf("SetShadow(true) CONFIG write = %#x, want bit 3 (shadow_dis) set", v)
	}
	if err := full.SetShadow(false); err != nil {
		t.Fatalf("SetShadow: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegConfig); v&0x08 != 0 {
		t.Errorf("SetShadow(false) CONFIG write = %#x, want bit 3 (shadow_dis) cleared", v)
	}
}

// Avoid an "imported and not used" error for math in case all uses are stripped.
var _ = math.Pi
var _ = abs32BMA150i

// counter_LG is INT_CTRL bits 3:2, counter_HG is bits 5:4.
func TestBMA150FullDebounceCounterBits(t *testing.T) {
	conn := newBMA150Connection()
	full, err := NewBMA150Full(conn)
	if err != nil {
		t.Fatalf("NewBMA150Full: %v", err)
	}
	conn.setRegister(bma150RegIntCtrl, 0x00)
	if err := full.SetLowG(0.4, 40, 0, 2); err != nil {
		t.Fatalf("SetLowG: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegIntCtrl); v != 0x09 {
		t.Errorf("INT_CTRL after SetLowG(counter=2) = %#x, want 0x09", v)
	}
	conn.setRegister(bma150RegIntCtrl, 0x00)
	if err := full.SetHighG(2.0, 2, 0, 2); err != nil {
		t.Fatalf("SetHighG: %v", err)
	}
	if v, _ := lastWriteToBMA150(conn, bma150RegIntCtrl); v != 0x22 {
		t.Errorf("INT_CTRL after SetHighG(counter=2) = %#x, want 0x22", v)
	}
}
