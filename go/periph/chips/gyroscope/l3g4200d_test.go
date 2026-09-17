package gyroscope

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

type mockConn struct {
	writes  [][]byte
	readSeq []byte
	readIdx int
}

func (m *mockConn) Write(data []byte) error {
	cp := make([]byte, len(data))
	copy(cp, data)
	m.writes = append(m.writes, cp)
	return nil
}

func (m *mockConn) Read(n int) ([]byte, error) {
	if m.readIdx >= len(m.readSeq) {
		out := make([]byte, n)
		return out, nil
	}
	end := m.readIdx + n
	if end > len(m.readSeq) {
		end = len(m.readSeq)
	}
	out := make([]byte, end-m.readIdx)
	copy(out, m.readSeq[m.readIdx:end])
	m.readIdx = end
	return out, nil
}

func (m *mockConn) WriteRead(data []byte, n int) ([]byte, error) {
	cp := make([]byte, len(data))
	copy(cp, data)
	m.writes = append(m.writes, cp)
	return m.Read(n)
}

func (m *mockConn) Enable()  {}
func (m *mockConn) Disable() {}
func (m *mockConn) Close() error { return nil }
func (m *mockConn) IsEnabled() bool { return true }
func (m *mockConn) IntPin() connection.InputPin { return nil }
func (m *mockConn) EnPin() connection.OutputPin { return nil }

var _ connection.Connection = (*mockConn)(nil)

func lastWriteForReg(writes [][]byte, reg uint8) (uint8, bool) {
	for i := len(writes) - 1; i >= 0; i-- {
		w := writes[i]
		if len(w) >= 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func TestL3G4200DFullAPI(t *testing.T) {
	conn := &mockConn{}
	d, err := NewL3G4200DFull(conn, false)
	if err != nil {
		t.Fatalf("init: %v", err)
	}

	ctrl1, ok := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg1)
	if !ok || ctrl1 != l3g4200dCtrlReg1Default {
		t.Errorf("init CTRL_REG1: got 0x%02X (ok=%v), want 0x%02X", ctrl1, ok, l3g4200dCtrlReg1Default)
	}
	ctrl4, ok := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg4)
	if !ok || ctrl4 != l3g4200dCtrlReg4Default {
		t.Errorf("init CTRL_REG4: got 0x%02X (ok=%v), want 0x%02X", ctrl4, ok, l3g4200dCtrlReg4Default)
	}

	// configure(ODR_200HZ=1, bw=0, FS_500) -> CTRL_REG1=(1<<6)|0x0F=0x4F, CTRL_REG4=(1<<4)|0x80=0x90.
	if err := d.Configure(L3G4200DODR200Hz, 0, L3G4200DFS500DPS); err != nil {
		t.Fatalf("configure: %v", err)
	}
	if c1, _ := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg1); c1 != 0x4F {
		t.Errorf("configure CTRL_REG1: 0x%02X, want 0x4F", c1)
	}
	if c4, _ := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg4); c4 != 0x90 {
		t.Errorf("configure CTRL_REG4: 0x%02X, want 0x90", c4)
	}
	if d.fullScale != L3G4200DFS500DPS {
		t.Errorf("fullScale after configure: %d, want %d", d.fullScale, L3G4200DFS500DPS)
	}

	// set_full_scale(2000): read CTRL_REG4 returns 0x90, write with FS=10 -> 0xA0.
	conn.readSeq = []byte{0x90}
	if err := d.SetFullScale(L3G4200DFS2000DPS); err != nil {
		t.Fatalf("set_full_scale: %v", err)
	}
	if c4, _ := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg4); c4 != 0xA0 {
		t.Errorf("set_full_scale CTRL_REG4: 0x%02X, want 0xA0", c4)
	}

	// enable_fifo(FIFO_STREAM=2, watermark=10): read CTRL_REG5 returns 0x00,
	// then writes CTRL_REG5=0x40 and FIFO_CTRL=((2<<5)|10)=0x4A.
	conn.readSeq = []byte{0x00}
	if err := d.EnableFIFO(L3G4200DFIFOStream, 10); err != nil {
		t.Fatalf("enable_fifo: %v", err)
	}
	if c5, _ := lastWriteForReg(conn.writes, l3g4200dRegCtrlReg5); c5 != 0x40 {
		t.Errorf("enable_fifo CTRL_REG5: 0x%02X, want 0x40", c5)
	}
	if fc, _ := lastWriteForReg(conn.writes, l3g4200dRegFifoCtrl); fc != 0x4A {
		t.Errorf("enable_fifo FIFO_CTRL: 0x%02X, want 0x4A", fc)
	}

	// fifo_samples(): FIFO_SRC returns 0x1A (26).
	conn.readSeq = []byte{0x1A}
	if n, err := d.FIFOSamples(); err != nil || n != 26 {
		t.Errorf("fifo_samples: got %d err=%v, want 26", n, err)
	}

	// set_interrupt(latch=true, x_high, y_high, z_high): writes INT1_CFG = 0x40|0x20|0x08|0x02 = 0x6A.
	if err := d.SetInterrupt(true, false, true, false, true, false, false, true); err != nil {
		t.Fatalf("set_interrupt: %v", err)
	}
	if ic, _ := lastWriteForReg(conn.writes, l3g4200dRegInt1Cfg); ic != 0x6A {
		t.Errorf("set_interrupt INT1_CFG: 0x%02X, want 0x6A", ic)
	}

	// set_threshold('x', 87.5): full_scale is 2000, sens=0.07, raw=int(87.5/0.07)=1250=0x04E2.
	//   XH = (1250>>8)&0x7F = 0x04, XL = 0xE2.
	if err := d.SetThreshold('x', 87.5); err != nil {
		t.Fatalf("set_threshold: %v", err)
	}
	if xh, _ := lastWriteForReg(conn.writes, l3g4200dRegInt1ThsXH); xh != 0x04 {
		t.Errorf("set_threshold XH: 0x%02X, want 0x04", xh)
	}
	if xl, _ := lastWriteForReg(conn.writes, l3g4200dRegInt1ThsXL); xl != 0xE2 {
		t.Errorf("set_threshold XL: 0x%02X, want 0xE2", xl)
	}

	// set_duration(4, wait=true): writes INT1_DURATION = 0x80|4 = 0x84.
	if err := d.SetDuration(4, true); err != nil {
		t.Fatalf("set_duration: %v", err)
	}
	if dur, _ := lastWriteForReg(conn.writes, l3g4200dRegInt1Dur); dur != 0x84 {
		t.Errorf("set_duration: 0x%02X, want 0x84", dur)
	}
}

func TestL3G4200DAngularRateDecode(t *testing.T) {
	// raw X=+16, Y=0, Z=-16 at full_scale=250 (sens=0.00875).
	// Sub-address for I²C multi-byte read is reg | 0x80 = 0x28 | 0x80 = 0xA8.
	conn := &mockConn{}
	conn.readSeq = []byte{
		0x10, 0x00,
		0x00, 0x00,
		0xF0, 0xFF,
	}
	d, err := NewL3G4200DFull(conn, false)
	if err != nil {
		t.Fatalf("init: %v", err)
	}
	x, y, z, err := d.AngularRate()
	if err != nil {
		t.Fatalf("angular_rate: %v", err)
	}
	const kRad = float32(3.141592653589793 / 180.0)
	expX := 16.0 * 0.00875 * kRad
	expZ := -16.0 * 0.00875 * kRad
	if x < expX-1e-6 || x > expX+1e-6 {
		t.Errorf("angular_rate x: got %v, want %v", x, expX)
	}
	if y != 0 {
		t.Errorf("angular_rate y: got %v, want 0", y)
	}
	if z < expZ-1e-6 || z > expZ+1e-6 {
		t.Errorf("angular_rate z: got %v, want %v", z, expZ)
	}

	// Verify the sub-address sent for the burst read.
	if len(conn.writes) == 0 {
		t.Fatalf("no writes captured")
	}
	w := conn.writes[len(conn.writes)-1]
	if len(w) != 1 || w[0] != 0xA8 {
		t.Errorf("burst sub-address: got %v, want [0xA8]", w)
	}
}
