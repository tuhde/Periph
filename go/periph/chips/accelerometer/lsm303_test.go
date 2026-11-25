package accelerometer

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// lsm303MockConnection is an in-memory fake connection.RegisterConnection
// for unit tests — no hardware, no bus.
type lsm303MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	regReads  [][2]uint32
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

func (m *lsm303MockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

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
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
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

// newLSM303Mocks returns an accelerometer mock and a magnetometer mock with
// the magnetometer identity preloaded.
func newLSM303Mocks() (*lsm303MockConnection, *lsm303MockConnection) {
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(lsm303RegIraM, 0x48, 0x34, 0x33)
	return accel, mag
}

// setAccelOutput preloads the accelerometer output registers with 12-bit
// counts.
//
// The high-resolution mode writes the 12-bit value left-justified into the
// 16-bit register pair.
func setAccelOutput(conn *lsm303MockConnection, x, y, z int16) {
	buf := make([]byte, 6)
	for i, v := range []int16{x, y, z} {
		s := v << 4
		buf[i*2] = byte(s & 0xFF)
		buf[i*2+1] = byte((s >> 8) & 0xFF)
	}
	conn.setRegister(lsm303RegOutXLA|lsm303AccelAutoIncrement, buf...)
}

// setMagOutput preloads the magnetometer output registers with 12-bit counts.
//
// The magnetometer writes the 12-bit value right-justified into the 16-bit
// register pair and sends X, Z, Y on the wire.
func setMagOutput(conn *lsm303MockConnection, x, y, z int16) {
	buf := make([]byte, 6)
	for i, v := range []int16{x, z, y} {
		buf[i*2] = byte((v >> 8) & 0xFF)
		buf[i*2+1] = byte(v & 0xFF)
	}
	conn.setRegister(lsm303RegOutXHM, buf...)
}

// lastWriteToLSM303 returns the value of the last write to reg.
//
// The mock accumulates every write since construction, so "the register now
// holds X" has to look at the last write to that register.
func lastWriteToLSM303(conn *lsm303MockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

// anyWriteToLSM303 reports whether any write to reg carried the given bits.
func anyWriteToLSM303(conn *lsm303MockConnection, reg byte, bits byte) bool {
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == reg && w[1]&bits != 0 {
			return true
		}
	}
	return false
}

func abs32LSM303(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func closeEnoughLSM303(a, b float32) bool {
	return abs32LSM303(a-b) < 1e-4
}

func TestLSM303MinimalConstruction(t *testing.T) {
	accel, mag := newLSM303Mocks()
	if _, err := NewLSM303Minimal(accel, mag); err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl4A); !ok || v != 0x88 {
		t.Errorf("CTRL4_A write = %#x, ok=%v, want 0x88", v, ok)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x57 {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x57", v, ok)
	}
	if v, ok := lastWriteToLSM303(mag, lsm303RegCraM); !ok || v != 0x10 {
		t.Errorf("CRA_M write = %#x, ok=%v, want 0x10", v, ok)
	}
	if v, ok := lastWriteToLSM303(mag, lsm303RegCrbM); !ok || v != 0x20 {
		t.Errorf("CRB_M write = %#x, ok=%v, want 0x20", v, ok)
	}
}

func TestLSM303MinimalBadIdentity(t *testing.T) {
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(lsm303RegIraM, 0x00, 0x00, 0x00)
	if _, err := NewLSM303Minimal(accel, mag); err == nil {
		t.Error("NewLSM303Minimal with bad identity: expected error, got nil")
	}
}

func TestLSM303MinimalReadAcceleration(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	setAccelOutput(accel, 1000, -2000, 2000)
	x, y, z, err := chip.ReadAcceleration()
	if err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if !closeEnoughLSM303(x, 1.0) || !closeEnoughLSM303(y, -2.0) || !closeEnoughLSM303(z, 2.0) {
		t.Errorf("ReadAcceleration() = (%v, %v, %v), want ~(1, -2, 2)", x, y, z)
	}
	// The multi-byte read must set the auto-increment bit.
	found := false
	for _, r := range accel.regReads {
		if r[0] == uint32(lsm303RegOutXLA|lsm303AccelAutoIncrement) && r[1] == 6 {
			found = true
		}
	}
	if !found {
		t.Error("ReadAcceleration did not use the auto-increment bit")
	}
}

func TestLSM303MinimalReadMagnetic(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	setMagOutput(mag, 1000, 3000, 2000)
	x, y, z, err := chip.ReadMagnetic()
	if err != nil {
		t.Fatalf("ReadMagnetic: %v", err)
	}
	if !closeEnoughLSM303(x, 1000.0/1100.0*100) ||
		!closeEnoughLSM303(y, 3000.0/1100.0*100) ||
		!closeEnoughLSM303(z, 2000.0/980.0*100) {
		t.Errorf("ReadMagnetic() = (%v, %v, %v), want the ±130 uT scaled values", x, y, z)
	}
	// The magnetometer pointer must not carry the auto-increment bit.
	for _, r := range mag.regReads {
		if r[0] == uint32(lsm303RegOutXHM|lsm303AccelAutoIncrement) {
			t.Error("ReadMagnetic used the auto-increment bit")
		}
	}
}

func TestLSM303FullRangeAndDataRate(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl4A, 0x88)
	if err := chip.SetRange(8); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl4A); !ok || v != 0xA8 {
		t.Errorf("CTRL4_A write = %#x, ok=%v, want 0xA8", v, ok)
	}
	if err := chip.SetRange(3); err == nil {
		t.Error("SetRange(3): expected error, got nil")
	}

	accel.setRegister(lsm303RegCtrl1A, 0x57)
	if err := chip.SetDataRate(400); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x77 {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x77", v, ok)
	}
	if err := chip.SetDataRate(123); err == nil {
		t.Error("SetDataRate(123): expected error, got nil")
	}
}

func TestLSM303FullModeAndAxes(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl1A, 0x57)
	accel.setRegister(lsm303RegCtrl4A, 0x88)
	if err := chip.SetMode(LSM303ModeLowPower); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x5F {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x5F", v, ok)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl4A); !ok || v != 0x80 {
		t.Errorf("CTRL4_A write = %#x, ok=%v, want 0x80", v, ok)
	}
	if err := chip.SetMode(9); err == nil {
		t.Error("SetMode(9): expected error, got nil")
	}

	accel.setRegister(lsm303RegCtrl1A, 0x57)
	if err := chip.SetAxes(true, false, true); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x55 {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x55", v, ok)
	}
}

func TestLSM303FullPowerDownAndStatus(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl1A, 0x57)
	if err := chip.PowerDown(); err != nil {
		t.Fatalf("PowerDown: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x07 {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x07", v, ok)
	}

	accel.setRegister(lsm303RegStatusA, 0x08)
	ok, err := chip.NewDataAvailable()
	if err != nil {
		t.Fatalf("NewDataAvailable: %v", err)
	}
	if !ok {
		t.Error("NewDataAvailable() = false, want true")
	}
	status, err := chip.ReadStatus()
	if err != nil {
		t.Fatalf("ReadStatus: %v", err)
	}
	if status != 0x08 {
		t.Errorf("ReadStatus() = %#x, want 0x08", status)
	}
}

func TestLSM303FullHighPass(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// mode=2 (0x80), cutoff=1 (0x10), FDS (0x08), HPCLICK (0x04), HPIS1 (0x01).
	if err := chip.SetHighPass(2, 1, true, true, false, true); err != nil {
		t.Fatalf("SetHighPass: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl2A); !ok || v != 0x9D {
		t.Errorf("CTRL2_A write = %#x, ok=%v, want 0x9D", v, ok)
	}
	if err := chip.SetHighPass(4, 0, false, false, false, false); err == nil {
		t.Error("SetHighPass(4): expected error, got nil")
	}

	accel.setRegister(lsm303RegReferenceA, 0x42)
	ref, err := chip.ResetHighPass()
	if err != nil {
		t.Fatalf("ResetHighPass: %v", err)
	}
	if ref != 0x42 {
		t.Errorf("ResetHighPass() = %#x, want 0x42", ref)
	}
	if err := chip.SetReference(0x37); err != nil {
		t.Fatalf("SetReference: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegReferenceA); !ok || v != 0x37 {
		t.Errorf("REFERENCE_A write = %#x, ok=%v, want 0x37", v, ok)
	}
}

func TestLSM303FullFifo(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl5A, 0x00)
	if err := chip.SetFifo(LSM303FifoStream, 16, false); err != nil {
		t.Fatalf("SetFifo: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegFifoCtrlA); !ok || v != 0x90 {
		t.Errorf("FIFO_CTRL_A write = %#x, ok=%v, want 0x90", v, ok)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl5A); !ok || v != 0x40 {
		t.Errorf("CTRL5_A write = %#x, ok=%v, want 0x40", v, ok)
	}
	if err := chip.SetFifo(LSM303FifoStream, 99, false); err == nil {
		t.Error("SetFifo with watermark 99: expected error, got nil")
	}

	accel.setRegister(lsm303RegFifoSrcA, 0x85)
	level, err := chip.FifoLevel()
	if err != nil {
		t.Fatalf("FifoLevel: %v", err)
	}
	if level != 5 {
		t.Errorf("FifoLevel() = %d, want 5", level)
	}

	accel.setRegister(lsm303RegFifoSrcA, 0xE7)
	wm, ov, empty, lvl, err := chip.ReadFifoStatus()
	if err != nil {
		t.Fatalf("ReadFifoStatus: %v", err)
	}
	if !wm || !ov || !empty || lvl != 7 {
		t.Errorf("ReadFifoStatus() = (%v, %v, %v, %d), want (true, true, true, 7)", wm, ov, empty, lvl)
	}

	setAccelOutput(accel, 1000, -2000, 2000)
	samples, err := chip.ReadFifo(2)
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if len(samples) != 2 {
		t.Fatalf("ReadFifo returned %d samples, want 2", len(samples))
	}
	if !closeEnoughLSM303(samples[0][0], 1.0) {
		t.Errorf("ReadFifo()[0].x = %v, want 1.0", samples[0][0])
	}
}

func TestLSM303FullMotionInterrupt(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// The threshold code is relative to the current range: 0.5 g * 128 / 2.
	if err := chip.SetMotionInterrupt(1, 0x02, 0.5, 10, false); err != nil {
		t.Fatalf("SetMotionInterrupt: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegInt1CfgA); !ok || v != 0x02 {
		t.Errorf("INT1_CFG_A write = %#x, ok=%v, want 0x02", v, ok)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegInt1ThsA); !ok || v != 0x20 {
		t.Errorf("INT1_THS_A write = %#x, ok=%v, want 0x20", v, ok)
	}
	if err := chip.SetMotionInterrupt(3, 0x02, 0.5, 10, false); err == nil {
		t.Error("SetMotionInterrupt with generator 3: expected error, got nil")
	}
	if err := chip.SetMotionInterrupt(1, 0x40, 0.5, 10, false); err == nil {
		t.Error("SetMotionInterrupt with events 0x40: expected error, got nil")
	}

	if err := chip.SetFreeFall(1, 0.35, 30); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegInt1CfgA); !ok || v != 0x95 {
		t.Errorf("INT1_CFG_A write = %#x, ok=%v, want 0x95", v, ok)
	}

	accel.setRegister(lsm303RegCtrl5A, 0x00)
	if err := chip.SetLatch(1, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl5A); !ok || v != 0x08 {
		t.Errorf("CTRL5_A write = %#x, ok=%v, want 0x08", v, ok)
	}

	accel.setRegister(lsm303RegInt1SourceA, 0x41)
	src, err := chip.ReadInterruptSource(1)
	if err != nil {
		t.Fatalf("ReadInterruptSource: %v", err)
	}
	if src != 0x41 {
		t.Errorf("ReadInterruptSource(1) = %#x, want 0x41", src)
	}
}

func TestLSM303FullClick(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// double & LSM303ClickAxisZ -> 0x20.
	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegClickCfgA); !ok || v != 0x20 {
		t.Errorf("CLICK_CFG_A write = %#x, ok=%v, want 0x20", v, ok)
	}
	// The threshold is relative to the ±2 g range: 0.8 g * 128 / 2 = 51.
	if v, ok := lastWriteToLSM303(accel, lsm303RegClickThsA); !ok || v != 51 {
		t.Errorf("CLICK_THS_A write = %#x, ok=%v, want 51", v, ok)
	}
	if err := chip.SetClick(0x08, 0, 0.5, 10, 20, 50); err == nil {
		t.Error("SetClick with mask 0x08: expected error, got nil")
	}

	accel.setRegister(lsm303RegClickSrcA, 0x42)
	src, err := chip.ReadClickSource()
	if err != nil {
		t.Fatalf("ReadClickSource: %v", err)
	}
	if src != 0x42 {
		t.Errorf("ReadClickSource() = %#x, want 0x42", src)
	}
}

func TestLSM303FullMagnetometer(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	mag.setRegister(lsm303RegCrbM, 0x20)
	if err := chip.SetMagRange(400); err != nil {
		t.Fatalf("SetMagRange: %v", err)
	}
	if v, ok := lastWriteToLSM303(mag, lsm303RegCrbM); !ok || v != 0x80 {
		t.Errorf("CRB_M write = %#x, ok=%v, want 0x80", v, ok)
	}
	if err := chip.SetMagRange(123); err == nil {
		t.Error("SetMagRange(123): expected error, got nil")
	}

	mag.setRegister(lsm303RegCraM, 0x10)
	if err := chip.SetMagDataRate(30); err != nil {
		t.Fatalf("SetMagDataRate: %v", err)
	}
	// 30 Hz is the sixth entry of [0.75, 1.5, 3, 7.5, 15, 30, 75, 220].
	if v, ok := lastWriteToLSM303(mag, lsm303RegCraM); !ok || v != 0x14 {
		t.Errorf("CRA_M write = %#x, ok=%v, want 0x14", v, ok)
	}
	if err := chip.SetMagDataRate(12); err == nil {
		t.Error("SetMagDataRate(12): expected error, got nil")
	}

	mag.setRegister(lsm303RegMrM, 0x00)
	if err := chip.SetMagMode(LSM303MagModeSleep); err != nil {
		t.Fatalf("SetMagMode: %v", err)
	}
	if v, ok := lastWriteToLSM303(mag, lsm303RegMrM); !ok || v != 0x02 {
		t.Errorf("MR_M write = %#x, ok=%v, want 0x02", v, ok)
	}

	mag.setRegister(lsm303RegSrM, 0x03)
	drdy, lock, err := chip.ReadMagStatus()
	if err != nil {
		t.Fatalf("ReadMagStatus: %v", err)
	}
	if !drdy || !lock {
		t.Errorf("ReadMagStatus() = (%v, %v), want (true, true)", drdy, lock)
	}

	identity, err := chip.ReadMagIdentity()
	if err != nil {
		t.Fatalf("ReadMagIdentity: %v", err)
	}
	if identity != LSM303MagIdentity {
		t.Errorf("ReadMagIdentity() = %v, want %v", identity, LSM303MagIdentity)
	}

	mag.setRegister(lsm303RegCraM, 0x10)
	if err := chip.EnableTemperature(true); err != nil {
		t.Fatalf("EnableTemperature: %v", err)
	}
	if v, ok := lastWriteToLSM303(mag, lsm303RegCraM); !ok || v != 0x90 {
		t.Errorf("CRA_M write = %#x, ok=%v, want 0x90", v, ok)
	}

	mag.setRegister(lsm303RegTempOutHM, 0x01, 0x00)
	temp, err := chip.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if !closeEnoughLSM303(temp, 2.0) {
		t.Errorf("ReadTemperatureDelta() = %v, want 2.0", temp)
	}
}

func TestLSM303FullHeading(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// X = 1100 counts (1 gauss), Y = 0 -> heading 0 deg (north).
	setMagOutput(mag, 1100, 0, 0)
	heading, err := chip.Heading(false)
	if err != nil {
		t.Fatalf("Heading: %v", err)
	}
	if math.Abs(float64(heading)) > 1e-3 {
		t.Errorf("Heading() = %v, want 0", heading)
	}
	// X = 0, Y = 1100 counts -> heading 90 deg (east).
	setMagOutput(mag, 0, 1100, 0)
	heading, err = chip.Heading(false)
	if err != nil {
		t.Fatalf("Heading: %v", err)
	}
	if math.Abs(float64(heading-90)) > 1e-3 {
		t.Errorf("Heading() = %v, want 90", heading)
	}
	// X = 0, Y = -1100 counts -> heading 270 deg (west).
	setMagOutput(mag, 0, -1100, 0)
	heading, err = chip.Heading(false)
	if err != nil {
		t.Fatalf("Heading: %v", err)
	}
	if math.Abs(float64(heading-270)) > 1e-3 {
		t.Errorf("Heading() = %v, want 270", heading)
	}
}

func TestLSM303FullInterruptRouting(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl3A, 0x00)
	if err := chip.EnableInterrupt(LSM303SourceClick, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl3A); !ok || v != 0x80 {
		t.Errorf("CTRL3_A write = %#x, ok=%v, want 0x80", v, ok)
	}
	accel.setRegister(lsm303RegCtrl3A, 0x80)
	if err := chip.DisableInterrupt(LSM303SourceClick, 1); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl3A); !ok || v != 0x00 {
		t.Errorf("CTRL3_A write = %#x, ok=%v, want 0x00", v, ok)
	}
	if err := chip.EnableInterrupt(LSM303SourceMagDataReady, 1); err == nil {
		t.Error("EnableInterrupt(DRDY): expected error, got nil")
	}
	if err := chip.EnableInterrupt(LSM303SourceClick, 3); err == nil {
		t.Error("EnableInterrupt with pin 3: expected error, got nil")
	}

	// The polarity bit is H_LACTIVE in CTRL6_A.
	accel.setRegister(lsm303RegCtrl6A, 0x00)
	if err := chip.SetInterruptPolarity(true); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl6A); !ok || v != 0x02 {
		t.Errorf("CTRL6_A write = %#x, ok=%v, want 0x02", v, ok)
	}
}

func TestLSM303FullPollInterrupt(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegStatusA, 0x08) // ZYXDA -> data ready
	accel.setRegister(lsm303RegFifoSrcA, 0x80) // WTM -> watermark
	mag.setRegister(lsm303RegSrM, 0x00)         // no magnetometer DRDY
	status, err := chip.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status&LSM303SourceDataReady == 0 {
		t.Error("PollInterrupt did not report the data-ready source")
	}
	if status&LSM303SourceWatermark == 0 {
		t.Error("PollInterrupt did not report the watermark source")
	}
	if status&LSM303SourceOverrun != 0 {
		t.Error("PollInterrupt reported a spurious overrun source")
	}
}

func TestLSM303FullReboot(t *testing.T) {
	accel, mag := newLSM303Mocks()
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegister(lsm303RegCtrl5A, 0x00)
	if err := chip.Reboot(); err != nil {
		t.Fatalf("Reboot: %v", err)
	}
	// The reboot re-runs init(), which rewrites CTRL5_A afterwards, so look
	// for a write with the BOOT bit set rather than at the last write.
	if !anyWriteToLSM303(accel, lsm303RegCtrl5A, lsm303Ctrl5Boot) {
		t.Error("Reboot did not set the BOOT bit")
	}
	if v, ok := lastWriteToLSM303(accel, lsm303RegCtrl1A); !ok || v != 0x57 {
		t.Errorf("CTRL1_A write = %#x, ok=%v, want 0x57", v, ok)
	}
}
