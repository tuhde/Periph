package accelerometer

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mma8451qMockConnection is an in-memory fake connection.RegisterConnection
// for unit tests — no hardware, no bus.
//
// The RST bit of CTRL_REG2 self-clears in hardware; the mock reproduces that
// so the driver's reset loop terminates.
type mma8451qMockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	regReads  [][2]uint32
}

func newMMA8451QMockConnection() *mma8451qMockConnection {
	return &mma8451qMockConnection{registers: map[byte]byte{}}
}

func (m *mma8451qMockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mma8451qMockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
		}
	}
	// CTRL_REG2.RST self-clears.
	if len(data) == 2 && data[0] == mma8451qRegCtrlReg2 && data[1]&mma8451qCtrl2RST != 0 {
		m.registers[mma8451qRegCtrlReg2] = 0x00
	}
	return nil
}

func (m *mma8451qMockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *mma8451qMockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mma8451qMockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
	out, err := m.WriteRead([]byte{byte(reg)}, length)
	if err != nil {
		return out, err
	}
	// Reading a sample from 0x01 pops one entry from the FIFO, so the
	// F_CNT field of the status register goes down.
	if reg == uint32(mma8451qRegOutXMSB) {
		if st := m.registers[mma8451qRegStatus]; st&0x3F > 0 {
			m.registers[mma8451qRegStatus] = st - 1
		}
	}
	// In 8-bit fast-read mode (F_READ set) the auto-increment skips the LSB
	// registers, so a 3-byte burst from 0x01 yields the X, Y and Z MSB
	// registers.
	if reg == uint32(mma8451qRegOutXMSB) && length == 3 &&
		m.registers[mma8451qRegCtrlReg1]&mma8451qCtrl1FRead != 0 {
		out[0] = m.registers[mma8451qRegOutXMSB]
		out[1] = m.registers[mma8451qRegOutYMSB]
		out[2] = m.registers[mma8451qRegOutZMSB]
	}
	return out, nil
}

func (m *mma8451qMockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *mma8451qMockConnection) Close() error                { return nil }
func (m *mma8451qMockConnection) Enable()                     {}
func (m *mma8451qMockConnection) Disable()                    {}
func (m *mma8451qMockConnection) IsEnabled() bool             { return true }
func (m *mma8451qMockConnection) IntPin() connection.InputPin { return nil }
func (m *mma8451qMockConnection) EnPin() connection.OutputPin { return nil }

// newMMA8451QConnection returns a mock with a valid WHO_AM_I and a
// one-g sample on the Z axis (4096 counts at ±2 g).
func newMMA8451QConnection() *mma8451qMockConnection {
	c := newMMA8451QMockConnection()
	c.setRegister(mma8451qRegWhoAmI, mma8451qWhoAmIValue)
	// 14-bit data: X = -0.5 g (-2048 counts), Y = 0, Z = +1 g (4096 counts).
	// raw16 = counts << 2.
	c.setRegister(mma8451qRegOutXMSB, 0xE0, 0x00)
	c.setRegister(mma8451qRegOutYMSB, 0x00, 0x00)
	c.setRegister(mma8451qRegOutZMSB, 0x40, 0x00)
	return c
}

// lastWriteToMMA8451Q returns the last value written to reg.
func lastWriteToMMA8451Q(conn *mma8451qMockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

// allWritesToMMA8451Q returns every value written to reg, in order.
func allWritesToMMA8451Q(conn *mma8451qMockConnection, reg byte) []byte {
	out := []byte{}
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == reg {
			out = append(out, w[1])
		}
	}
	return out
}

func abs32MMA8451Q(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func TestMMA8451QMinimalInitSequence(t *testing.T) {
	conn := newMMA8451QConnection()
	if _, err := NewMMA8451QMinimal(conn); err != nil {
		t.Fatalf("NewMMA8451QMinimal: %v", err)
	}
	// The initialization sequence is: WHO_AM_I read, CTRL_REG2 = 0x40 (RST),
	// XYZ_DATA_CFG = 0x00, CTRL_REG2 = 0x00, CTRL_REG1 = 0x19.
	ctrl2 := allWritesToMMA8451Q(conn, mma8451qRegCtrlReg2)
	if len(ctrl2) != 2 || ctrl2[0] != 0x40 || ctrl2[1] != 0x00 {
		t.Errorf("CTRL_REG2 writes = %v, want [0x40 0x00]", ctrl2)
	}
	if v, ok := lastWriteToMMA8451Q(conn, mma8451qRegXYZDataCfg); !ok || v != 0x00 {
		t.Errorf("XYZ_DATA_CFG write = %#x (ok=%v), want 0x00", v, ok)
	}
	if v, ok := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); !ok || v != 0x19 {
		t.Errorf("CTRL_REG1 write = %#x (ok=%v), want 0x19", v, ok)
	}
	// WHO_AM_I must have been read.
	found := false
	for _, r := range conn.regReads {
		if r[0] == uint32(mma8451qRegWhoAmI) && r[1] == 1 {
			found = true
		}
	}
	if !found {
		t.Error("WHO_AM_I was not read during init")
	}
}

func TestMMA8451QMinimalBadWhoAmI(t *testing.T) {
	conn := newMMA8451QMockConnection()
	conn.setRegister(mma8451qRegWhoAmI, 0x00)
	if _, err := NewMMA8451QMinimal(conn); err == nil {
		t.Error("NewMMA8451QMinimal with a bad WHO_AM_I: expected an error, got nil")
	}
}

func TestMMA8451QMinimalRead14Bit(t *testing.T) {
	conn := newMMA8451QConnection()
	chip, err := NewMMA8451QMinimal(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QMinimal: %v", err)
	}
	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32MMA8451Q(x-(-0.5)) > 1e-6 || abs32MMA8451Q(y-0.0) > 1e-6 || abs32MMA8451Q(z-1.0) > 1e-6 {
		t.Errorf("Read() = (%v, %v, %v), want ~(-0.5, 0, 1)", x, y, z)
	}
	// The data must be read in one 6-byte burst starting at 0x01.
	found := false
	for _, r := range conn.regReads {
		if r[0] == uint32(mma8451qRegOutXMSB) && r[1] == 6 {
			found = true
		}
	}
	if !found {
		t.Error("Read() did not burst-read 6 bytes from 0x01")
	}
}

func TestMMA8451QFullReadRaw(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	rx, ry, rz, err := full.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if rx != -2048 || ry != 0 || rz != 4096 {
		t.Errorf("ReadRaw() = (%d, %d, %d), want (-2048, 0, 4096)", rx, ry, rz)
	}
}

func TestMMA8451QFullSetRange(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange(4): %v", err)
	}
	// FS = 01 for ±4 g, HPF_OUT = 0.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegXYZDataCfg); v != 0x01 {
		t.Errorf("XYZ_DATA_CFG after SetRange(4) = %#x, want 0x01", v)
	}
	// The cached sensitivity must follow: ±4 g is 2048 counts/g.
	conn.setRegister(mma8451qRegOutZMSB, 0x20, 0x00) // 2048 counts
	x, y, z, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32MMA8451Q(z-1.0) > 1e-6 {
		t.Errorf("Read() z = %v at ±4 g, want 1.0", z)
	}
	_ = x
	_ = y

	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange(8): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegXYZDataCfg); v != 0x02 {
		t.Errorf("XYZ_DATA_CFG after SetRange(8) = %#x, want 0x02", v)
	}
	if err := full.SetRange(3); err == nil {
		t.Error("SetRange(3): expected an error, got nil")
	}
}

func TestMMA8451QFullSetRange8WithLowNoise(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetLowNoise(true); err != nil {
		t.Fatalf("SetLowNoise(true): %v", err)
	}
	if err := full.SetRange(8); err == nil {
		t.Error("SetRange(8) with low noise on: expected an error, got nil")
	}
}

func TestMMA8451QFullSetDataRate(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetDataRate(50); err != nil {
		t.Fatalf("SetDataRate(50): %v", err)
	}
	// CTRL_REG1 after init is 0x19 (DR = 011). DR = 100 for 50 Hz gives
	// 0x20 | 0x19 & ~0x38 = 0x01 -> 0x21.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x21 {
		t.Errorf("CTRL_REG1 after SetDataRate(50) = %#x, want 0x21", v)
	}
	if err := full.SetDataRate(123); err == nil {
		t.Error("SetDataRate(123): expected an error, got nil")
	}
}

func TestMMA8451QFullSetOversampling(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	sleepMode := uint8(2)
	if err := full.SetOversampling(1, &sleepMode); err != nil {
		t.Fatalf("SetOversampling(1, 2): %v", err)
	}
	// MODS = 01 (bits 1:0), SMODS = 10 (bits 4:3) -> 0x10 | 0x01 = 0x11.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg2); v != 0x11 {
		t.Errorf("CTRL_REG2 after SetOversampling(1, 2) = %#x, want 0x11", v)
	}
	if err := full.SetOversampling(4, nil); err == nil {
		t.Error("SetOversampling(4): expected an error, got nil")
	}
}

func TestMMA8451QFullLowNoiseAndResolution(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetLowNoise(true); err != nil {
		t.Fatalf("SetLowNoise(true): %v", err)
	}
	// LNOISE is bit 2 of CTRL_REG1: 0x19 | 0x04 = 0x1D.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x1D {
		t.Errorf("CTRL_REG1 after SetLowNoise(true) = %#x, want 0x1D", v)
	}
	if err := full.SetLowNoise(false); err != nil {
		t.Fatalf("SetLowNoise(false): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x19 {
		t.Errorf("CTRL_REG1 after SetLowNoise(false) = %#x, want 0x19", v)
	}

	if err := full.SetResolution(8); err != nil {
		t.Fatalf("SetResolution(8): %v", err)
	}
	// F_READ is bit 1 of CTRL_REG1: 0x19 | 0x02 = 0x1B.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x1B {
		t.Errorf("CTRL_REG1 after SetResolution(8) = %#x, want 0x1B", v)
	}
	// In 8-bit mode the sample is 3 bytes and the counts are 8-bit signed.
	conn.setRegister(mma8451qRegOutXMSB, 0xC0) // -64 counts
	conn.setRegister(mma8451qRegOutYMSB, 0x10) // 16 counts
	conn.setRegister(mma8451qRegOutZMSB, 0x20) // 32 counts
	x, y, z, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32MMA8451Q(x-(-1.0)) > 1e-6 || abs32MMA8451Q(y-0.25) > 1e-6 || abs32MMA8451Q(z-0.5) > 1e-6 {
		t.Errorf("Read() in 8-bit mode = (%v, %v, %v), want ~(-1, 0.25, 0.5)", x, y, z)
	}
	if err := full.SetResolution(12); err == nil {
		t.Error("SetResolution(12): expected an error, got nil")
	}
}

func TestMMA8451QFullResolutionBlockedByFIFO(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetFIFO(1, 16); err != nil {
		t.Fatalf("SetFIFO(1, 16): %v", err)
	}
	if err := full.SetResolution(8); err == nil {
		t.Error("SetResolution(8) with the FIFO enabled: expected an error, got nil")
	}
}

func TestMMA8451QFullStandbyAndActivate(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.Standby(); err != nil {
		t.Fatalf("Standby: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x18 {
		t.Errorf("CTRL_REG1 after Standby = %#x, want 0x18 (ACTIVE cleared)", v)
	}
	if err := full.Activate(); err != nil {
		t.Fatalf("Activate: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x19 {
		t.Errorf("CTRL_REG1 after Activate = %#x, want 0x19 (ACTIVE set)", v)
	}
}

func TestMMA8451QFullOffsets(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetOffset(0.1, -0.05, 0.25); err != nil {
		t.Fatalf("SetOffset: %v", err)
	}
	// 0.1 / 0.002 = 50, -0.05 / 0.002 = -25, 0.25 / 0.002 = 125.
	neg25 := int8(-25)
	offY := byte(neg25)
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegOffX); v != 50 {
		t.Errorf("OFF_X write = %d, want 50", v)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegOffY); v != offY {
		t.Errorf("OFF_Y write = %d, want %d", v, offY)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegOffZ); v != 125 {
		t.Errorf("OFF_Z write = %d, want 125", v)
	}
	ox, oy, oz, err := full.GetOffset()
	if err != nil {
		t.Fatalf("GetOffset: %v", err)
	}
	if abs32MMA8451Q(ox-0.1) > 1e-6 || abs32MMA8451Q(oy+0.05) > 1e-6 || abs32MMA8451Q(oz-0.25) > 1e-6 {
		t.Errorf("GetOffset() = (%v, %v, %v), want ~(0.1, -0.05, 0.25)", ox, oy, oz)
	}
	// Clamping at ±0.256 g.
	if err := full.SetOffset(1.0, -1.0, 0); err != nil {
		t.Fatalf("SetOffset(1, -1, 0): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegOffX); v != 127 {
		t.Errorf("OFF_X write = %d, want 127 (clamped)", v)
	}
	neg128 := int8(-128)
	offYMin := byte(neg128)
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegOffY); v != offYMin {
		t.Errorf("OFF_Y write = %d, want %d (clamped)", v, offYMin)
	}
}

func TestMMA8451QFullThresholdEncoding(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	// 0.5 / 0.063 = 7.94 -> 8.
	if err := full.SetFreefallMotion(false, 0x07, 0.5, 0, false, false); err != nil {
		t.Fatalf("SetFreefallMotion: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTThs); v != 8 {
		t.Errorf("FF_MT_THS write = %d, want 8", v)
	}
	// FF_MT_CFG: ELE = 0, OAE = 0 (freefall), axes 0x07 << 3 = 0x38.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTCfg); v != 0x38 {
		t.Errorf("FF_MT_CFG write = %#x, want 0x38", v)
	}
	// Motion mode sets OAE (bit 6).
	if err := full.SetFreefallMotion(true, 0x07, 0.5, 0, false, false); err != nil {
		t.Fatalf("SetFreefallMotion(motion): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTCfg); v != 0x78 {
		t.Errorf("FF_MT_CFG write = %#x, want 0x78 (OAE set)", v)
	}
	// 0.063 * 16 = 1.008 g -> 16.
	if err := full.SetTransient(0x07, 1.008, 0, false, false); err != nil {
		t.Fatalf("SetTransient: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegTransientT); v != 16 {
		t.Errorf("TRANSIENT_THS write = %d, want 16", v)
	}
	// TRANSIENT_CFG: axes 0x07 << 1 = 0x0E.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegTransientC); v != 0x0E {
		t.Errorf("TRANSIENT_CFG write = %#x, want 0x0E", v)
	}
	// A threshold of 0.2 g -> round(0.2 / 0.063) = 3.
	if err := full.SetFreefallMotion(false, 0x07, 0.2, 0, false, false); err != nil {
		t.Fatalf("SetFreefallMotion(0.2): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTThs); v != 3 {
		t.Errorf("FF_MT_THS write = %d, want 3", v)
	}
}

func TestMMA8451QFullDebounceCount(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	// At 100 Hz in normal mode the debounce step is 10 ms.
	if err := full.SetFreefallMotion(false, 0x07, 0.5, 60, false, false); err != nil {
		t.Fatalf("SetFreefallMotion: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTCount); v != 6 {
		t.Errorf("FF_MT_COUNT write = %d, want 6 (60 ms / 10 ms)", v)
	}
	// DBCNTM is bit 7 of FF_MT_THS.
	if err := full.SetFreefallMotion(false, 0x07, 0.5, 0, false, true); err != nil {
		t.Fatalf("SetFreefallMotion: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFFMTThs); v != 0x80|8 {
		t.Errorf("FF_MT_THS write = %#x, want DBCNTM set", v)
	}
}

func TestMMA8451QFullOrientation(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetOrientation(true, 100, 45, 4, 4, 1); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	// P_L_THS_REG: THS 0x10 << 3 = 0x80, HYS = 4 -> 0x84.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPLThsReg); v != 0x84 {
		t.Errorf("P_L_THS_REG write = %#x, want 0x84", v)
	}
	// PL_BF_ZCOMP: BKFR 1 << 6 = 0x40, ZLOCK 4 -> 0x44.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPLBFZComp); v != 0x44 {
		t.Errorf("PL_BF_ZCOMP write = %#x, want 0x44", v)
	}
	// PL_CFG: PL_EN (bit 6) set.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPLCfg); v != 0x40 {
		t.Errorf("PL_CFG write = %#x, want 0x40", v)
	}
	// PL_COUNT: 100 ms / 10 ms = 10.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPLCount); v != 10 {
		t.Errorf("PL_COUNT write = %d, want 10", v)
	}
	// An unsupported trip angle is rejected.
	if err := full.SetOrientation(true, 0, 33, 4, 4, 1); err == nil {
		t.Error("SetOrientation(33 degrees): expected an error, got nil")
	}
	// THS + HYS must be in 1..31: 15 degrees is 0x07 = 7, HYS = 7 -> 14 is
	// fine, but 75 degrees is 0x19 = 25 and HYS = 7 -> 32 is out of range.
	if err := full.SetOrientation(true, 0, 75, 7, 4, 1); err == nil {
		t.Error("SetOrientation(75 degrees, HYS 7): expected an error, got nil")
	}
}

func TestMMA8451QFullReadOrientation(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	// NEWLP | LO | LAPO = 10 (landscape right) | BAFRO (back).
	conn.setRegister(mma8451qRegPLStatus, 0x80|0x40|0x04|0x01)
	changed, lockout, lapo, back, err := full.ReadOrientation()
	if err != nil {
		t.Fatalf("ReadOrientation: %v", err)
	}
	if !changed || !lockout || lapo != 2 || !back {
		t.Errorf("ReadOrientation() = (%v, %v, %d, %v), want (true, true, 2, true)",
			changed, lockout, lapo, back)
	}
}

func TestMMA8451QFullFIFO(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetFIFO(1, 16); err != nil {
		t.Fatalf("SetFIFO(1, 16): %v", err)
	}
	// F_SETUP: F_MODE = 01 (circular) -> 0x40, watermark 16 -> 0x10.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegFSetup); v != 0x50 {
		t.Errorf("F_SETUP write = %#x, want 0x50", v)
	}
	// F_STATUS: overflow | watermark | count 20.
	conn.setRegister(mma8451qRegStatus, 0x80|0x40|20)
	ovf, wmrk, count, err := full.ReadFIFOStatus()
	if err != nil {
		t.Fatalf("ReadFIFOStatus: %v", err)
	}
	if !ovf || !wmrk || count != 20 {
		t.Errorf("ReadFIFOStatus() = (%v, %v, %d), want (true, true, 20)", ovf, wmrk, count)
	}
	level, err := full.FIFOLevel()
	if err != nil {
		t.Fatalf("FIFOLevel: %v", err)
	}
	if level != 20 {
		t.Errorf("FIFOLevel() = %d, want 20", level)
	}
	if err := full.SetFIFO(4, 0); err == nil {
		t.Error("SetFIFO(4): expected an error, got nil")
	}
}

func TestMMA8451QFullFIFOTrigger(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetFIFOTrigger(true, false, true, false); err != nil {
		t.Fatalf("SetFIFOTrigger: %v", err)
	}
	// TRIG_CFG: Trig_TRANS (bit 5) | Trig_PULSE (bit 3) = 0x28.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegTrigCfg); v != 0x28 {
		t.Errorf("TRIG_CFG write = %#x, want 0x28", v)
	}
	if err := full.SetFIFOTrigger(false, false, false, false); err == nil {
		t.Error("SetFIFOTrigger(no source): expected an error, got nil")
	}
}

func TestMMA8451QFullAutoSleep(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	wake := MMA8451QSourcePulse | MMA8451QSourceFFMT
	if err := full.SetAutoSleep(true, 12.5, 10, wake); err != nil {
		t.Fatalf("SetAutoSleep: %v", err)
	}
	// CTRL_REG2: SLPE (bit 2) set.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg2); v&0x04 == 0 {
		t.Errorf("CTRL_REG2 write = %#x, want SLPE (bit 2) set", v)
	}
	// CTRL_REG1: ASLP_RATE = 01 -> 0x40, so 0x19 | 0x40 = 0x59.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg1); v != 0x59 {
		t.Errorf("CTRL_REG1 after SetAutoSleep = %#x, want 0x59", v)
	}
	// ASLP_COUNT: 10 s / 0.32 s = 31.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegASLPCount); v != 31 {
		t.Errorf("ASLP_COUNT write = %d, want 31", v)
	}
	// CTRL_REG3: WAKE_PULSE (bit 4) | WAKE_FF_MT (bit 3) = 0x18.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg3); v != 0x18 {
		t.Errorf("CTRL_REG3 write = %#x, want 0x18", v)
	}
	if err := full.SetAutoSleep(true, 50, 10, MMA8451QSourceDataReady); err == nil {
		t.Error("SetAutoSleep with an unsupported wake source: expected an error, got nil")
	}
}

func TestMMA8451QFullInterruptEnableDisable(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.EnableInterrupt(MMA8451QSourceOrientation, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	// CTRL_REG4: INT_EN_LNDPRT (bit 4) = 0x10.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg4); v != 0x10 {
		t.Errorf("CTRL_REG4 write = %#x, want 0x10", v)
	}
	// CTRL_REG5: INT_CFG_LNDPRT = 1 -> INT1.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg5); v != 0x10 {
		t.Errorf("CTRL_REG5 write = %#x, want 0x10", v)
	}
	// Route the same source to INT2.
	if err := full.EnableInterrupt(MMA8451QSourceOrientation, 2); err != nil {
		t.Fatalf("EnableInterrupt(pin 2): %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg5); v != 0x00 {
		t.Errorf("CTRL_REG5 write = %#x, want 0x00 (routed to INT2)", v)
	}
	if err := full.DisableInterrupt(MMA8451QSourceOrientation); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg4); v != 0x00 {
		t.Errorf("CTRL_REG4 write = %#x, want 0x00", v)
	}
	if err := full.EnableInterrupt(MMA8451QSourceOrientation, 3); err == nil {
		t.Error("EnableInterrupt(pin 3): expected an error, got nil")
	}
}

func TestMMA8451QFullPollInterruptClearing(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	conn.setRegister(mma8451qRegIntSource,
		MMA8451QSourceOrientation|MMA8451QSourcePulse|MMA8451QSourceDataReady)
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != MMA8451QSourceOrientation|MMA8451QSourcePulse|MMA8451QSourceDataReady {
		t.Errorf("PollInterrupt() = %#x, want the INT_SOURCE content", status)
	}
	// PL_STATUS and PULSE_SRC must have been read to clear the flags.
	plRead := false
	pulseRead := false
	for _, r := range conn.regReads {
		if r[0] == uint32(mma8451qRegPLStatus) {
			plRead = true
		}
		if r[0] == uint32(mma8451qRegPulseSrc) {
			pulseRead = true
		}
	}
	if !plRead {
		t.Error("PollInterrupt did not read PL_STATUS to clear the orientation flag")
	}
	if !pulseRead {
		t.Error("PollInterrupt did not read PULSE_SRC to clear the pulse flag")
	}
}

func TestMMA8451QFullPollInterruptFIFOAndSleep(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	conn.setRegister(mma8451qRegIntSource,
		MMA8451QSourceFIFO|MMA8451QSourceAutoSleep|MMA8451QSourceTransient|MMA8451QSourceFFMT)
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status == 0 {
		t.Error("PollInterrupt() = 0, want the INT_SOURCE content")
	}
	seen := map[uint32]bool{}
	for _, r := range conn.regReads {
		seen[r[0]] = true
	}
	for _, reg := range []uint32{
		uint32(mma8451qRegStatus),
		uint32(mma8451qRegSysMod),
		uint32(mma8451qRegTransientS),
		uint32(mma8451qRegFFMTSrc),
	} {
		if !seen[reg] {
			t.Errorf("PollInterrupt did not read register %#x", reg)
		}
	}
}

func TestMMA8451QFullSetInterruptPins(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetInterruptPins(true, true); err != nil {
		t.Fatalf("SetInterruptPins: %v", err)
	}
	// IPOL (bit 1) | PP_OD (bit 0) = 0x03.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg3); v != 0x03 {
		t.Errorf("CTRL_REG3 write = %#x, want 0x03", v)
	}
	if err := full.SetInterruptPins(false, false); err != nil {
		t.Fatalf("SetInterruptPins: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg3); v != 0x00 {
		t.Errorf("CTRL_REG3 write = %#x, want 0x00", v)
	}
}

func TestMMA8451QFullSetFIFOGate(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetFIFOGate(true); err != nil {
		t.Fatalf("SetFIFOGate: %v", err)
	}
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegCtrlReg3); v != 0x80 {
		t.Errorf("CTRL_REG3 write = %#x, want 0x80 (FIFO_GATE set)", v)
	}
}

func TestMMA8451QFullSetPulse(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetPulse(0, 0x04, [3]float32{0.5, 0.5, 1.0}, 40, 100, 300, true, false, true, true); err != nil {
		t.Fatalf("SetPulse: %v", err)
	}
	// PULSE_CFG: ELE (bit 6) | ZDPEFE (bit 5) = 0x60.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseCfg); v != 0x60 {
		t.Errorf("PULSE_CFG write = %#x, want 0x60", v)
	}
	// PULSE_THSX: 0.5 / 0.063 = 7.94 -> 8.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseThsX); v != 8 {
		t.Errorf("PULSE_THSX write = %d, want 8", v)
	}
	// PULSE_THSZ: 1.0 / 0.063 = 15.87 -> 16.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseThsZ); v != 16 {
		t.Errorf("PULSE_THSZ write = %d, want 16", v)
	}
	// PULSE_TMLT: 40 ms / 10 ms (100 Hz, normal mode, LPF on) = 4.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseTMLT); v != 4 {
		t.Errorf("PULSE_TMLT write = %d, want 4", v)
	}
	// PULSE_LTCY: 100 ms / 20 ms = 5.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseLTCY); v != 5 {
		t.Errorf("PULSE_LTCY write = %d, want 5", v)
	}
	// PULSE_WIND: 300 ms / 20 ms = 15.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegPulseWIND); v != 15 {
		t.Errorf("PULSE_WIND write = %d, want 15", v)
	}
	// HP_FILTER_CUTOFF: Pulse_HPF_BYP clear, Pulse_LPF_EN set.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegHPFilterCut); v != 0x10 {
		t.Errorf("HP_FILTER_CUTOFF write = %#x, want 0x10", v)
	}
	if err := full.SetPulse(0, 0, [3]float32{0.5, 0.5, 0.5}, 30, 100, 300, false, false, true, true); err == nil {
		t.Error("SetPulse without an axis: expected an error, got nil")
	}
}

func TestMMA8451QFullReadSourceRegisters(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	conn.setRegister(mma8451qRegFFMTSrc, 0x80)
	conn.setRegister(mma8451qRegTransientS, 0x40)
	conn.setRegister(mma8451qRegPulseSrc, 0x20)
	if v, err := full.ReadMotionSource(); err != nil || v != 0x80 {
		t.Errorf("ReadMotionSource() = %#x, %v, want 0x80, nil", v, err)
	}
	if v, err := full.ReadTransientSource(); err != nil || v != 0x40 {
		t.Errorf("ReadTransientSource() = %#x, %v, want 0x40, nil", v, err)
	}
	if v, err := full.ReadPulseSource(); err != nil || v != 0x20 {
		t.Errorf("ReadPulseSource() = %#x, %v, want 0x20, nil", v, err)
	}
}

func TestMMA8451QFullStatusAndSystemMode(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	conn.setRegister(mma8451qRegStatus, 0x08)
	ready, err := full.NewDataAvailable()
	if err != nil {
		t.Fatalf("NewDataAvailable: %v", err)
	}
	if !ready {
		t.Error("NewDataAvailable() = false, want true (STATUS.ZYXDR set)")
	}
	status, err := full.ReadStatus()
	if err != nil || status != 0x08 {
		t.Errorf("ReadStatus() = %#x, %v, want 0x08, nil", status, err)
	}
	conn.setRegister(mma8451qRegSysMod, 0x02)
	mode, err := full.ReadSystemMode()
	if err != nil {
		t.Fatalf("ReadSystemMode: %v", err)
	}
	if mode != MMA8451QModeSleep {
		t.Errorf("ReadSystemMode() = %d, want %d (sleep)", mode, MMA8451QModeSleep)
	}
}

func TestMMA8451QFullReset(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	ctrl2 := allWritesToMMA8451Q(conn, mma8451qRegCtrlReg2)
	if len(ctrl2) == 0 || ctrl2[len(ctrl2)-1]&mma8451qCtrl2RST == 0 {
		t.Errorf("CTRL_REG2 writes = %v, want the last one to set RST", ctrl2)
	}
}

func TestMMA8451QFullHighPassOutput(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetHighPassOutput(true, 2); err != nil {
		t.Fatalf("SetHighPassOutput: %v", err)
	}
	// HPF_OUT (bit 4) | SEL = 2 -> 0x12.
	if v, _ := lastWriteToMMA8451Q(conn, mma8451qRegXYZDataCfg); v != 0x12 {
		t.Errorf("XYZ_DATA_CFG write = %#x, want 0x12", v)
	}
	if err := full.SetHighPassOutput(false, 4); err == nil {
		t.Error("SetHighPassOutput(cutoff 4): expected an error, got nil")
	}
}

func TestMMA8451QFullSelfTest(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	// The mock always returns the same sample, so the delta is zero and the
	// self-test must report a failure without an error.
	ok, dx, dy, dz, err := full.SelfTest()
	if err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if ok {
		t.Error("SelfTest() = true, want false (no movement in the mock)")
	}
	if dx != 0 || dy != 0 || dz != 0 {
		t.Errorf("SelfTest() deltas = (%d, %d, %d), want (0, 0, 0)", dx, dy, dz)
	}
	// The self-test bit must have been set and cleared again.
	ctrl2 := allWritesToMMA8451Q(conn, mma8451qRegCtrlReg2)
	sawST := false
	for _, v := range ctrl2 {
		if v&mma8451qCtrl2ST != 0 {
			sawST = true
		}
	}
	if !sawST {
		t.Error("SelfTest did not set CTRL_REG2.ST")
	}
}

func TestMMA8451QFullReadFIFO(t *testing.T) {
	conn := newMMA8451QConnection()
	full, err := NewMMA8451QFull(conn)
	if err != nil {
		t.Fatalf("NewMMA8451QFull: %v", err)
	}
	if err := full.SetFIFO(1, 8); err != nil {
		t.Fatalf("SetFIFO: %v", err)
	}
	// Two samples in the FIFO.
	conn.setRegister(mma8451qRegStatus, 0x02)
	samples, err := full.ReadFIFO(-1)
	if err != nil {
		t.Fatalf("ReadFIFO: %v", err)
	}
	if len(samples) != 2 {
		t.Errorf("ReadFIFO(-1) returned %d samples, want 2", len(samples))
	}
	if len(samples) > 0 {
		if abs32MMA8451Q(samples[0][0]-(-0.5)) > 1e-6 {
			t.Errorf("ReadFIFO sample x = %v, want -0.5", samples[0][0])
		}
	}
}

// Avoid an "imported and not used" error for math in case all uses are stripped.
var _ = math.Pi
