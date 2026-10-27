package accelerometer

import (
	"reflect"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// lsm303MockConnection is an in-memory fake connection.RegisterConnection
// for unit tests — no hardware, no bus. One instance stands in for the
// accelerometer slave (0x19), another for the magnetometer slave (0x1E).
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

// newLSM303Minimal creates a Minimal over fresh mocks with the magnetometer
// identity set up.
func newLSM303Minimal(t *testing.T) (*lsm303MockConnection, *lsm303MockConnection, *LSM303Minimal) {
	t.Helper()
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(lsm303RegIraM, 0x48, 0x34, 0x33)
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	return accel, mag, chip
}

// newLSM303Full creates a Full over fresh mocks with the magnetometer
// identity set up.
func newLSM303Full(t *testing.T) (*lsm303MockConnection, *lsm303MockConnection, *LSM303Full) {
	t.Helper()
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(lsm303RegIraM, 0x48, 0x34, 0x33)
	chip, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	return accel, mag, chip
}

func lastWriteToLSM303(conn *lsm303MockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func abs32LSM303(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func abs64LSM303(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

func expectWrite(t *testing.T, conn *lsm303MockConnection, reg byte, want byte, what string) {
	t.Helper()
	v, ok := lastWriteToLSM303(conn, reg)
	if !ok {
		t.Errorf("%s: no write to register %#x", what, reg)
		return
	}
	if v != want {
		t.Errorf("%s: register %#x write = %#x, want %#x", what, reg, v, want)
	}
}

// setAccelSample writes a 6-byte acceleration window at the burst
// sub-address 0xA8: xLSB xMSB yLSB yMSB zLSB zMSB.
func setAccelSample(accel *lsm303MockConnection, xlsb, xmsb, ylsb, ymsb, zlsb, zmsb byte) {
	accel.setRegister(lsm303RegOutXLA|0x80, xlsb, xmsb, ylsb, ymsb, zlsb, zmsb)
}

// setMagSample writes a 6-byte magnetic window at 0x03: the wire order is
// X high/low, Z high/low, Y high/low.
func setMagSample(mag *lsm303MockConnection, xh, xl, zh, zl, yh, yl byte) {
	mag.setRegister(lsm303RegOutXHM, xh, xl, zh, zl, yh, yl)
}

func TestLSM303MinimalInitRegisters(t *testing.T) {
	accel, mag, _ := newLSM303Minimal(t)

	// Accelerometer: control registers cleared, then CTRL4 = 0x88 (BDU=1,
	// FS=00 ±2 g, HR=1), CTRL1 = 0x57 (ODR 100 Hz, all axes enabled).
	expectWrite(t, accel, lsm303RegCtrl2A, 0x00, "init")
	expectWrite(t, accel, lsm303RegCtrl3A, 0x00, "init")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x00, "init")
	expectWrite(t, accel, lsm303RegCtrl6A, 0x00, "init")
	expectWrite(t, accel, lsm303RegFifoCtrlA, 0x00, "init")
	expectWrite(t, accel, lsm303RegCtrl4A, 0x88, "init")
	expectWrite(t, accel, lsm303RegCtrl1A, 0x57, "init")
	// Magnetometer: CRA = 0x10 (15 Hz, temp off), CRB = 0x20 (±1.3 gauss),
	// MR = 0x00 (continuous — the part resets into sleep).
	expectWrite(t, mag, lsm303RegCraM, 0x10, "init")
	expectWrite(t, mag, lsm303RegCrbM, 0x20, "init")
	expectWrite(t, mag, lsm303RegMrM, 0x00, "init")

	// The identity read must be a 3-byte read from IRA_REG_M.
	for _, r := range mag.regReads {
		if r[0] == uint32(lsm303RegIraM) && r[1] == 3 {
			return
		}
	}
	t.Error("init did not read 3 identity bytes from IRA_REG_M (0x0A)")
}

func TestLSM303MinimalConstructionBadID(t *testing.T) {
	accel := newLSM303MockConnection()
	mag := newLSM303MockConnection()
	mag.setRegister(lsm303RegIraM, 0x48, 0x34, 0x35)
	if _, err := NewLSM303Minimal(accel, mag); err == nil {
		t.Error("NewLSM303Minimal with bad magnetometer ID: expected error, got nil")
	}
}

func TestLSM303MinimalReadAcceleration(t *testing.T) {
	accel, _, chip := newLSM303Minimal(t)
	// High-resolution (shift 4), ±2 g (1 mg/LSB):
	// x = 0x1000 >> 4 = 256 -> 0.256 g, y = 0xF000 >> 4 = -256 -> -0.256 g,
	// z = 0x0800 >> 4 = 128 -> 0.128 g.
	setAccelSample(accel, 0x00, 0x10, 0x00, 0xF0, 0x00, 0x08)
	x, y, z, err := chip.ReadAcceleration()
	if err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if abs32LSM303(x-0.256) > 1e-4 || abs32LSM303(y+0.256) > 1e-4 || abs32LSM303(z-0.128) > 1e-4 {
		t.Errorf("ReadAcceleration() = (%v, %v, %v), want ~(0.256, -0.256, 0.128)", x, y, z)
	}
}

func TestLSM303MinimalReadMagnetic(t *testing.T) {
	_, mag, chip := newLSM303Minimal(t)
	// Wire order X, Z, Y, high byte first; ±1.3 gauss gains 1100 (X/Y) and
	// 980 (Z) LSB/gauss.
	// x = 1100 -> 100 µT, z = 980 -> 100 µT, y = 550 -> 50 µT.
	setMagSample(mag, 0x04, 0x4C, 0x03, 0xD4, 0x02, 0x26)
	x, y, z, err := chip.ReadMagnetic()
	if err != nil {
		t.Fatalf("ReadMagnetic: %v", err)
	}
	if abs32LSM303(x-100.0) > 1e-3 || abs32LSM303(y-50.0) > 1e-3 || abs32LSM303(z-100.0) > 1e-3 {
		t.Errorf("ReadMagnetic() = (%v, %v, %v), want ~(100, 50, 100)", x, y, z)
	}
}

func TestLSM303FullSetRange(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	// (0x88 & 0xCF) | (2 << 4) = 0xA8
	expectWrite(t, accel, lsm303RegCtrl4A, 0xA8, "SetRange(8)")
	if err := full.SetRange(5); err == nil {
		t.Error("SetRange(5): expected error, got nil")
	}
	// Sensitivity must follow the range: 4 mg/LSB at ±8 g high-resolution.
	setAccelSample(accel, 0x00, 0x10, 0, 0, 0, 0)
	x, _, _, err := full.ReadAcceleration()
	if err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if abs32LSM303(x-1.024) > 1e-3 {
		t.Errorf("ReadAcceleration() after SetRange(8) = %v, want ~1.024 (4 mg/LSB)", x)
	}
}

func TestLSM303FullSetDataRate(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetDataRate(200); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	// (0x57 & 0x0F) | (0x6 << 4) = 0x67
	expectWrite(t, accel, lsm303RegCtrl1A, 0x67, "SetDataRate(200)")
	for _, rate := range []int{0, 2, 300} {
		if err := full.SetDataRate(rate); err == nil {
			t.Errorf("SetDataRate(%d): expected error, got nil", rate)
		}
	}
	if err := full.SetDataRate(1620); err == nil {
		t.Error("SetDataRate(1620) in high-resolution mode: expected error, got nil")
	}
	if err := full.SetDataRate(5376); err == nil {
		t.Error("SetDataRate(5376) in high-resolution mode: expected error, got nil")
	}
	// 1344 Hz is valid in normal / high-resolution mode.
	if err := full.SetDataRate(1344); err != nil {
		t.Fatalf("SetDataRate(1344): %v", err)
	}
	// (0x67 & 0x0F) | (0x9 << 4) = 0x97
	expectWrite(t, accel, lsm303RegCtrl1A, 0x97, "SetDataRate(1344)")
	if err := full.SetMode(0); err != nil {
		t.Fatalf("SetMode(0): %v", err)
	}
	if err := full.SetDataRate(1344); err == nil {
		t.Error("SetDataRate(1344) in low-power mode: expected error, got nil")
	}
	if err := full.SetDataRate(5376); err != nil {
		t.Fatalf("SetDataRate(5376): %v", err)
	}
	// (0x9F & 0x0F) | (0x9 << 4) = 0x9F in low-power = 5376 Hz (LPen stays set).
	expectWrite(t, accel, lsm303RegCtrl1A, 0x9F, "SetDataRate(5376)")
	if err := full.SetDataRate(1620); err != nil {
		t.Fatalf("SetDataRate(1620): %v", err)
	}
	// (0x9F & 0x0F) | (0x8 << 4) = 0x8F
	expectWrite(t, accel, lsm303RegCtrl1A, 0x8F, "SetDataRate(1620)")
}

func TestLSM303FullSetMode(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetMode(0); err != nil {
		t.Fatalf("SetMode(0): %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl1A, 0x5F, "SetMode(0) CTRL1 (LPen set)")
	expectWrite(t, accel, lsm303RegCtrl4A, 0x80, "SetMode(0) CTRL4 (HR clear)")
	if err := full.SetMode(1); err != nil {
		t.Fatalf("SetMode(1): %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl1A, 0x57, "SetMode(1) CTRL1")
	expectWrite(t, accel, lsm303RegCtrl4A, 0x80, "SetMode(1) CTRL4")
	if err := full.SetMode(2); err != nil {
		t.Fatalf("SetMode(2): %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl1A, 0x57, "SetMode(2) CTRL1")
	expectWrite(t, accel, lsm303RegCtrl4A, 0x88, "SetMode(2) CTRL4 (HR set)")
	if err := full.SetMode(3); err == nil {
		t.Error("SetMode(3): expected error, got nil")
	}
}

func TestLSM303FullSetAxes(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetAxes(false, true, true); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	// (0x57 & 0xF8) | 0x06 = 0x56
	expectWrite(t, accel, lsm303RegCtrl1A, 0x56, "SetAxes(false, true, true)")
	if err := full.SetAxes(false, false, false); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl1A, 0x50, "SetAxes(false, false, false)")
	if err := full.SetAxes(true, true, true); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl1A, 0x57, "SetAxes(true, true, true)")
}

func TestLSM303FullPowerDown(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.PowerDown(); err != nil {
		t.Fatalf("PowerDown: %v", err)
	}
	// 0x57 & 0x0F = 0x07
	expectWrite(t, accel, lsm303RegCtrl1A, 0x07, "PowerDown")
}

func TestLSM303FullReadAccelerationRaw(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	setAccelSample(accel, 0x00, 0x10, 0x00, 0xF0, 0x00, 0x08)
	x, y, z, err := full.ReadAccelerationRaw()
	if err != nil {
		t.Fatalf("ReadAccelerationRaw: %v", err)
	}
	if x != 256 || y != -256 || z != 128 {
		t.Errorf("ReadAccelerationRaw() = (%d, %d, %d), want (256, -256, 128)", x, y, z)
	}
}

func TestLSM303FullNewDataAvailableAndStatus(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	accel.setRegister(lsm303RegStatusA, 0x08)
	ready, err := full.NewDataAvailable()
	if err != nil || !ready {
		t.Errorf("NewDataAvailable() with ZYXDA set = %v, %v, want true, nil", ready, err)
	}
	accel.setRegister(lsm303RegStatusA, 0x00)
	ready, err = full.NewDataAvailable()
	if err != nil || ready {
		t.Errorf("NewDataAvailable() with ZYXDA clear = %v, %v, want false, nil", ready, err)
	}
	accel.setRegister(lsm303RegStatusA, 0xA5)
	status, err := full.ReadStatus()
	if err != nil || status != 0xA5 {
		t.Errorf("ReadStatus() = %#x, %v, want 0xA5, nil", status, err)
	}
}

func TestLSM303FullHighPass(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetHighPass(1, 2, true, true, true, true); err != nil {
		t.Fatalf("SetHighPass: %v", err)
	}
	// (1 << 6) | (2 << 4) | FDS | HPCLICK | HPIS2 | HPIS1 = 0x6F
	expectWrite(t, accel, lsm303RegCtrl2A, 0x6F, "SetHighPass(1, 2, all on)")
	if err := full.SetHighPass(0, 0, false, false, false, false); err != nil {
		t.Fatalf("SetHighPass: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl2A, 0x00, "SetHighPass(0, 0, all off)")

	accel.setRegister(lsm303RegReferenceA, 0x33)
	v, err := full.ResetHighPass()
	if err != nil || v != 0x33 {
		t.Errorf("ResetHighPass() = %#x, %v, want 0x33, nil", v, err)
	}
	if err := full.SetReference(0xAB); err != nil {
		t.Fatalf("SetReference: %v", err)
	}
	expectWrite(t, accel, lsm303RegReferenceA, 0xAB, "SetReference")
}

func TestLSM303FullSetFifo(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetFifo(2, 15, true); err != nil {
		t.Fatalf("SetFifo: %v", err)
	}
	// (2 << 6) | 0x20 | 15 = 0xAF, CTRL5 FIFO_EN = 0x40
	expectWrite(t, accel, lsm303RegFifoCtrlA, 0xAF, "SetFifo(2, 15, true)")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x40, "SetFifo(2, 15, true) CTRL5")
	if err := full.SetFifo(0, 0, false); err != nil {
		t.Fatalf("SetFifo: %v", err)
	}
	expectWrite(t, accel, lsm303RegFifoCtrlA, 0x00, "SetFifo(0, 0, false)")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x00, "SetFifo(0, 0, false) CTRL5")
}

func TestLSM303FullFifoLevelAndStatus(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	accel.setRegister(lsm303RegFifoSrcA, 0xC3)
	level, err := full.FifoLevel()
	if err != nil || level != 3 {
		t.Errorf("FifoLevel() = %d, %v, want 3, nil", level, err)
	}
	watermark, overrun, empty, level, err := full.ReadFifoStatus()
	if err != nil {
		t.Fatalf("ReadFifoStatus: %v", err)
	}
	if !watermark || !overrun || empty || level != 3 {
		t.Errorf("ReadFifoStatus() = (%v, %v, %v, %d), want (true, true, false, 3)",
			watermark, overrun, empty, level)
	}
}

func TestLSM303FullReadFifo(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	// FSS = 2 unread samples.
	accel.setRegister(lsm303RegFifoSrcA, 0x02)
	setAccelSample(accel, 0x00, 0x10, 0x00, 0xF0, 0x00, 0x08)
	samples, err := full.ReadFifo(0)
	if err != nil {
		t.Fatalf("ReadFifo(0): %v", err)
	}
	if len(samples) != 2 {
		t.Fatalf("ReadFifo(0) returned %d samples, want 2", len(samples))
	}
	want := [3]float32{0.256, -0.256, 0.128}
	for i, s := range samples {
		if abs32LSM303(s[0]-want[0]) > 1e-4 || abs32LSM303(s[1]-want[1]) > 1e-4 || abs32LSM303(s[2]-want[2]) > 1e-4 {
			t.Errorf("ReadFifo(0) sample %d = %v, want ~%v", i, s, want)
		}
	}
	// An explicit count overrides FSS.
	accel.setRegister(lsm303RegFifoSrcA, 0x02)
	truncated, err := full.ReadFifo(1)
	if err != nil {
		t.Fatalf("ReadFifo(1): %v", err)
	}
	if len(truncated) != 1 {
		t.Errorf("ReadFifo(1) returned %d samples, want 1 (truncated)", len(truncated))
	}
}

func TestLSM303FullSetFreeFall(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetFreeFall(1, 0.35, 30); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	// INT1_CFG = 0x95 (AOI | ZLIE | YLIE | XLIE); at ±2 g,
	// round(0.35 * 128 / 2) = 22; at 100 Hz, round(30 * 100 / 1000) = 3.
	expectWrite(t, accel, lsm303RegInt1CfgA, 0x95, "SetFreeFall(1) CFG")
	expectWrite(t, accel, lsm303RegInt1ThsA, 22, "SetFreeFall(1) THS")
	expectWrite(t, accel, lsm303RegInt1DurA, 3, "SetFreeFall(1) DUR")
	if err := full.SetFreeFall(2, 0.35, 30); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	expectWrite(t, accel, lsm303RegInt2CfgA, 0x95, "SetFreeFall(2) CFG")
}

func TestLSM303FullSetMotionInterrupt(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetMotionInterrupt(1, 0x2A, 1.0, 100, true); err != nil {
		t.Fatalf("SetMotionInterrupt: %v", err)
	}
	// CFG = 0x80 (AOI) | 0x2A; at ±2 g, round(1.0 * 128 / 2) = 64;
	// at 100 Hz, round(100 * 100 / 1000) = 10.
	expectWrite(t, accel, lsm303RegInt1CfgA, 0xAA, "SetMotionInterrupt CFG")
	expectWrite(t, accel, lsm303RegInt1ThsA, 64, "SetMotionInterrupt THS")
	expectWrite(t, accel, lsm303RegInt1DurA, 10, "SetMotionInterrupt DUR")
	// OR mode (no AOI bit), events mask clamped to 6 bits.
	if err := full.SetMotionInterrupt(2, 0xFF, 0.5, 0, false); err != nil {
		t.Fatalf("SetMotionInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegInt2CfgA, 0x3F, "SetMotionInterrupt OR-mode CFG")
	expectWrite(t, accel, lsm303RegInt2DurA, 0, "SetMotionInterrupt DUR 0")
	if err := full.SetMotionInterrupt(3, 0x2A, 1.0, 0, false); err == nil {
		t.Error("SetMotionInterrupt(3, ...): expected error, got nil")
	}
}

func TestLSM303FullSetOrientation(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetOrientation(1, false, false); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	// 6D movement: 0x40 | 0x3F = 0x7F, D4D_INT1 cleared.
	expectWrite(t, accel, lsm303RegInt1CfgA, 0x7F, "SetOrientation(1, movement) CFG")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x00, "SetOrientation(1, 4D off) CTRL5")
	if err := full.SetOrientation(2, true, true); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	// 6D position: 0x80 | 0x7F = 0xFF; D4D_INT2 set.
	expectWrite(t, accel, lsm303RegInt2CfgA, 0xFF, "SetOrientation(2, position) CFG")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x01, "SetOrientation(2, 4D on) CTRL5")
	if err := full.SetOrientation(1, true, true); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	expectWrite(t, accel, lsm303RegInt1CfgA, 0xFF, "SetOrientation(1, position) CFG")
	expectWrite(t, accel, lsm303RegCtrl5A, 0x05, "SetOrientation(1, 4D on) CTRL5")
	if err := full.SetOrientation(3, false, false); err == nil {
		t.Error("SetOrientation(3, ...): expected error, got nil")
	}
}

func TestLSM303FullSetLatchAndSources(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetLatch(1, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl5A, 0x08, "SetLatch(1, true)")
	if err := full.SetLatch(2, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl5A, 0x0A, "SetLatch(2, true)")
	if err := full.SetLatch(1, false); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl5A, 0x02, "SetLatch(1, false)")
	if err := full.SetLatch(3, true); err == nil {
		t.Error("SetLatch(3, true): expected error, got nil")
	}

	accel.setRegister(lsm303RegInt1SrcA, 0x55)
	src, err := full.ReadInterruptSource(1)
	if err != nil || src != 0x55 {
		t.Errorf("ReadInterruptSource(1) = %#x, %v, want 0x55, nil", src, err)
	}
	accel.setRegister(lsm303RegClickSrcA, 0x66)
	src, err = full.ReadClickSource()
	if err != nil || src != 0x66 {
		t.Errorf("ReadClickSource() = %#x, %v, want 0x66, nil", src, err)
	}
	if _, err := full.ReadInterruptSource(3); err == nil {
		t.Error("ReadInterruptSource(3): expected error, got nil")
	}
}

func TestLSM303FullSetClick(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetClick(1, 4, 0.8, 20, 40, 200); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	// X single = XS (bit 1), Z double = ZD (bit 4) → 0x12; at ±2 g,
	// round(0.8 * 128 / 2) = 51; at 100 Hz: limit 2, latency 4, window 20.
	expectWrite(t, accel, lsm303RegClickCfgA, 0x12, "SetClick CFG")
	expectWrite(t, accel, lsm303RegClickThsA, 51, "SetClick THS")
	expectWrite(t, accel, lsm303RegTimeLimitA, 2, "SetClick TIME_LIMIT")
	expectWrite(t, accel, lsm303RegTimeLatA, 4, "SetClick TIME_LATENCY")
	expectWrite(t, accel, lsm303RegTimeWinA, 20, "SetClick TIME_WINDOW")
	// Axis masks spread: Y single = YS (bit 3), Y double = YD (bit 2) → 0x0C.
	if err := full.SetClick(2, 2, 0.5, 10, 20, 50); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	expectWrite(t, accel, lsm303RegClickCfgA, 0x0C, "SetClick(Y) CFG")
	if err := full.SetClick(8, 0, 0.5, 10, 20, 50); err == nil {
		t.Error("SetClick(8, ...): expected error, got nil")
	}
}

func TestLSM303FullSetInterruptPolarity(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.SetInterruptPolarity(true); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0x02, "SetInterruptPolarity(true)")
	if err := full.SetInterruptPolarity(false); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0x00, "SetInterruptPolarity(false)")
}

func TestLSM303FullSetMagRange(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	if err := full.SetMagRange(400); err != nil {
		t.Fatalf("SetMagRange: %v", err)
	}
	// GN = 100 → CRB = 0x80
	expectWrite(t, mag, lsm303RegCrbM, 0x80, "SetMagRange(400)")
	if err := full.SetMagRange(300); err == nil {
		t.Error("SetMagRange(300): expected error, got nil")
	}
	// Gains must follow the range: 450 (X/Y) and 400 (Z) LSB/gauss at ±400 µT.
	setMagSample(mag, 0x01, 0xC2, 0x01, 0x90, 0x01, 0xC2)
	x, y, z, err := full.ReadMagnetic()
	if err != nil {
		t.Fatalf("ReadMagnetic: %v", err)
	}
	// x = 450/450*100 = 100 µT, y = 450/450*100 = 100 µT, z = 400/400*100 = 100 µT.
	if abs32LSM303(x-100.0) > 1e-3 || abs32LSM303(y-100.0) > 1e-3 || abs32LSM303(z-100.0) > 1e-3 {
		t.Errorf("ReadMagnetic() after SetMagRange(400) = (%v, %v, %v), want ~(100, 100, 100)", x, y, z)
	}
}

func TestLSM303FullSetMagDataRate(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	if err := full.SetMagDataRate(75); err != nil {
		t.Fatalf("SetMagDataRate: %v", err)
	}
	// (0x10 & 0x80) | (0x6 << 2) = 0x18
	expectWrite(t, mag, lsm303RegCraM, 0x18, "SetMagDataRate(75)")
	if err := full.SetMagDataRate(20); err == nil {
		t.Error("SetMagDataRate(20): expected error, got nil")
	}
	// TEMP_EN must survive the rate change.
	if err := full.EnableTemperature(true); err != nil {
		t.Fatalf("EnableTemperature: %v", err)
	}
	expectWrite(t, mag, lsm303RegCraM, 0x98, "EnableTemperature(true)")
	if err := full.SetMagDataRate(30); err != nil {
		t.Fatalf("SetMagDataRate: %v", err)
	}
	// (0x98 & 0x80) | (0x5 << 2) = 0x94
	expectWrite(t, mag, lsm303RegCraM, 0x94, "SetMagDataRate(30) with TEMP_EN")
}

func TestLSM303FullSetMagMode(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	if err := full.SetMagMode(1); err != nil {
		t.Fatalf("SetMagMode: %v", err)
	}
	expectWrite(t, mag, lsm303RegMrM, 0x01, "SetMagMode(1)")
	if err := full.SetMagMode(0); err != nil {
		t.Fatalf("SetMagMode: %v", err)
	}
	expectWrite(t, mag, lsm303RegMrM, 0x00, "SetMagMode(0)")
	if err := full.SetMagMode(3); err == nil {
		t.Error("SetMagMode(3): expected error, got nil")
	}
}

func TestLSM303FullReadMagneticRaw(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	setMagSample(mag, 0x04, 0x4C, 0x03, 0xD4, 0x02, 0x26)
	x, y, z, err := full.ReadMagneticRaw()
	if err != nil {
		t.Fatalf("ReadMagneticRaw: %v", err)
	}
	if x != 1100 || y != 550 || z != 980 {
		t.Errorf("ReadMagneticRaw() = (%d, %d, %d), want (1100, 550, 980) in X, Y, Z order", x, y, z)
	}
	// Negative X: 0xFC18 → -1000.
	setMagSample(mag, 0xFC, 0x18, 0, 0, 0, 0)
	x, _, _, err = full.ReadMagneticRaw()
	if err != nil {
		t.Fatalf("ReadMagneticRaw: %v", err)
	}
	if x != -1000 {
		t.Errorf("ReadMagneticRaw() x = %d, want -1000", x)
	}
}

func TestLSM303FullMagDataReadyAndStatus(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	mag.setRegister(lsm303RegSrM, 0x03)
	drdy, lock, err := full.ReadMagStatus()
	if err != nil || !drdy || !lock {
		t.Errorf("ReadMagStatus() with SR=0x03 = (%v, %v), %v, want (true, true), nil", drdy, lock, err)
	}
	ready, err := full.MagDataReady()
	if err != nil || !ready {
		t.Errorf("MagDataReady() with DRDY set = %v, %v, want true, nil", ready, err)
	}
	mag.setRegister(lsm303RegSrM, 0x00)
	ready, err = full.MagDataReady()
	if err != nil || ready {
		t.Errorf("MagDataReady() with DRDY clear = %v, %v, want false, nil", ready, err)
	}
}

func TestLSM303FullReadMagIdentity(t *testing.T) {
	_, _, full := newLSM303Full(t)
	id, err := full.ReadMagIdentity()
	if err != nil {
		t.Fatalf("ReadMagIdentity: %v", err)
	}
	if !reflect.DeepEqual(id, [3]byte{0x48, 0x34, 0x33}) {
		t.Errorf("ReadMagIdentity() = %v, want [72 52 51]", id)
	}
}

func TestLSM303FullTemperature(t *testing.T) {
	_, mag, full := newLSM303Full(t)
	// 0x0100 >> 4 = 16 → 2.0 °C.
	mag.setRegister(lsm303RegTempHM, 0x01, 0x00)
	delta, err := full.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if abs32LSM303(delta-2.0) > 1e-4 {
		t.Errorf("ReadTemperatureDelta() = %v, want 2.0", delta)
	}
	// First use must have enabled TEMP_EN (read-modify-write keeps DO bits).
	expectWrite(t, mag, lsm303RegCraM, 0x90, "ReadTemperatureDelta auto-enable")
	// Negative: 0xFFF0 >> 4 = -1 → -0.125 °C.
	mag.setRegister(lsm303RegTempHM, 0xFF, 0xF0)
	delta, err = full.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if abs32LSM303(delta+0.125) > 1e-4 {
		t.Errorf("ReadTemperatureDelta() = %v, want -0.125", delta)
	}
	// Explicit disable.
	if err := full.EnableTemperature(false); err != nil {
		t.Fatalf("EnableTemperature(false): %v", err)
	}
	expectWrite(t, mag, lsm303RegCraM, 0x10, "EnableTemperature(false)")
}

func TestLSM303FullHeading(t *testing.T) {
	accel, mag, full := newLSM303Full(t)
	// Flat 45°: mx = my = 1100 counts (100 µT each), mz = 0; gravity on Z
	// (z = 0x4000 >> 4 = 1024 counts = 1.024 g).
	setMagSample(mag, 0x04, 0x4C, 0x00, 0x00, 0x04, 0x4C)
	setAccelSample(accel, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40)
	h, err := full.Heading(false)
	if err != nil {
		t.Fatalf("Heading(false): %v", err)
	}
	if abs64LSM303(h-45.0) > 1e-6 {
		t.Errorf("Heading(false) = %v, want 45", h)
	}
	h, err = full.Heading(true)
	if err != nil {
		t.Fatalf("Heading(true): %v", err)
	}
	if abs64LSM303(h-45.0) > 1e-6 {
		t.Errorf("Heading(true) flat = %v, want 45", h)
	}
	// 0–360 wrap: my negative, mx 0 → atan2(-1, 0) = -90 → 270.
	setMagSample(mag, 0x00, 0x00, 0x00, 0x00, 0xFB, 0xB4)
	h, err = full.Heading(false)
	if err != nil {
		t.Fatalf("Heading(false): %v", err)
	}
	if abs64LSM303(h-270.0) > 1e-6 {
		t.Errorf("Heading(false) wrap = %v, want 270", h)
	}
}

func TestLSM303FullEnableDisableInterrupt(t *testing.T) {
	accel, _, full := newLSM303Full(t)

	if err := full.EnableInterrupt(SourceDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl3A, 0x10, "EnableInterrupt(SourceDataReady, 1) CTRL3")
	expectWrite(t, accel, lsm303RegCtrl6A, 0x00, "EnableInterrupt(SourceDataReady, 1) CTRL6")

	if err := full.EnableInterrupt(SourceClick, 2); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0x80, "EnableInterrupt(SourceClick, 2) CTRL6")

	if err := full.EnableInterrupt(SourceIA1, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl3A, 0x50, "EnableInterrupt(SourceIA1, 1) CTRL3")

	if err := full.EnableInterrupt(SourceIA2, 2); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0xA0, "EnableInterrupt(SourceIA2, 2) CTRL6")

	if err := full.DisableInterrupt(SourceIA1, 1); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl3A, 0x10, "DisableInterrupt(SourceIA1, 1) CTRL3")
	if err := full.DisableInterrupt(SourceClick, 2); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0x20, "DisableInterrupt(SourceClick, 2) CTRL6")

	// Error paths.
	if err := full.EnableInterrupt(SourceMagDataReady, 1); err == nil {
		t.Error("EnableInterrupt(SourceMagDataReady): expected error, got nil")
	}
	if err := full.DisableInterrupt(SourceMagDataReady, 2); err == nil {
		t.Error("DisableInterrupt(SourceMagDataReady): expected error, got nil")
	}
	if err := full.EnableInterrupt(SourceBoot, 1); err == nil {
		t.Error("EnableInterrupt(SourceBoot, 1): expected error (INT2-only), got nil")
	}
	if err := full.EnableInterrupt(SourceBoot, 2); err != nil {
		t.Fatalf("EnableInterrupt(SourceBoot, 2): %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl6A, 0x30, "EnableInterrupt(SourceBoot, 2) CTRL6")
	if err := full.EnableInterrupt(0x0100, 1); err == nil {
		t.Error("EnableInterrupt(0x0100): expected error, got nil")
	}
	if err := full.DisableInterrupt(SourceDataReady, 3); err == nil {
		t.Error("DisableInterrupt(..., 3): expected error, got nil")
	}
}

func TestLSM303FullPollInterrupt(t *testing.T) {
	accel, mag, full := newLSM303Full(t)

	// Nothing enabled: only the always-polled magnetometer DRDY is reported.
	mag.setRegister(lsm303RegSrM, 0x01)
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != SourceMagDataReady {
		t.Errorf("PollInterrupt() with no sources enabled = 0x%04X, want 0x%04X", status, SourceMagDataReady)
	}

	for _, source := range []uint16{SourceDataReady, SourceWatermark, SourceOverrun, SourceIA1, SourceIA2, SourceClick} {
		if err := full.EnableInterrupt(source, 1); err != nil {
			t.Fatalf("EnableInterrupt(0x%04X): %v", source, err)
		}
	}
	accel.setRegister(lsm303RegStatusA, 0x08)   // ZYXDA
	accel.setRegister(lsm303RegFifoSrcA, 0xC0)  // WTM + OVRN
	accel.setRegister(lsm303RegInt1SrcA, 0x40)  // IA
	accel.setRegister(lsm303RegInt2SrcA, 0x40)  // IA
	accel.setRegister(lsm303RegClickSrcA, 0x40) // IA
	want := uint16(0x0001 | 0x0002 | 0x0004 | 0x0008 | 0x0010 | 0x0020 | 0x0080)
	status, err = full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != want {
		t.Errorf("PollInterrupt() = 0x%04X, want 0x%04X", status, want)
	}
}

func TestLSM303FullOnOffInterrupt(t *testing.T) {
	accel, _, full := newLSM303Full(t)

	if err := full.OnInterrupt(nil, 1); err == nil {
		t.Error("OnInterrupt(nil, 1): expected error, got nil")
	}
	if err := full.OnInterrupt(func(uint16) {}, 3); err == nil {
		t.Error("OnInterrupt(cb, 3): expected error, got nil")
	}
	if err := full.OffInterrupt(3); err == nil {
		t.Error("OffInterrupt(3): expected error, got nil")
	}

	// Enable a source, arm data-ready, and subscribe on INT1. The mock has
	// no IntPin, so the 5 ms polling fallback drives delivery.
	if err := full.EnableInterrupt(SourceDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	accel.setRegister(lsm303RegStatusA, 0x08)

	calls := make(chan uint16, 8)
	if err := full.OnInterrupt(func(status uint16) { calls <- status }, 1); err != nil {
		t.Fatalf("OnInterrupt: %v", err)
	}
	// First poll: data-ready set, magnetometer DRDY clear → status 0x0001.
	select {
	case status := <-calls:
		if status != SourceDataReady {
			t.Errorf("callback status = 0x%04X, want 0x%04X", status, SourceDataReady)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("OnInterrupt: no callback within 2 s")
	}
	// Clear the source: the polling fallback reports the change to 0 exactly
	// once, then stays quiet while the status mask is unchanged.
	accel.setRegister(lsm303RegStatusA, 0x00)
	select {
	case status := <-calls:
		if status != 0 {
			t.Errorf("callback status after clear = 0x%04X, want 0", status)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("OnInterrupt: no change callback within 2 s")
	}
	time.Sleep(30 * time.Millisecond)
	if n := len(calls); n != 0 {
		t.Errorf("callback fired %d more times with unchanged status, want 0", n)
	}

	// Unsubscribe stops delivery.
	if err := full.OffInterrupt(1); err != nil {
		t.Fatalf("OffInterrupt: %v", err)
	}
	accel.setRegister(lsm303RegStatusA, 0x08)
	time.Sleep(30 * time.Millisecond)
	if n := len(calls); n != 0 {
		t.Errorf("callback fired %d times after OffInterrupt, want 0", n)
	}

	// A second subscription starts a new delivery path and is cleaned up.
	if err := full.OnInterrupt(func(uint16) {}, 2); err != nil {
		t.Fatalf("OnInterrupt(pin 2): %v", err)
	}
	if err := full.OffInterrupt(2); err != nil {
		t.Fatalf("OffInterrupt(pin 2): %v", err)
	}
}

func TestLSM303FullReboot(t *testing.T) {
	accel, _, full := newLSM303Full(t)
	if err := full.Reboot(); err != nil {
		t.Fatalf("Reboot: %v", err)
	}
	expectWrite(t, accel, lsm303RegCtrl5A, 0x80, "Reboot")
}
