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
	return m.WriteRead([]byte{byte(reg)}, length)
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

// Avoid an "imported and not used" error for math in case all uses are stripped.
var _ = math.Pi
