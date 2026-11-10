package adcdac

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mcp4xxxMock models the MCP4XXX command protocol: sixteen nine-bit memory cells, increment /
// decrement of the volatile wipers, two-byte reads. log holds every WriteReg payload.
type mcp4xxxMock struct {
	mockConnection
	mem     [16]int
	maxCode int
	status  int
	log     [][]byte
	reads   []byte // command bytes of every ReadReg
}

func newMCP4XXXMock(maxCode int) *mcp4xxxMock {
	m := &mcp4xxxMock{mockConnection: *newMockConnection(), maxCode: maxCode, status: 0x1F0}
	m.mem[0], m.mem[1], m.mem[2], m.mem[3] = maxCode/2, maxCode/2, maxCode/2, maxCode/2
	m.mem[4] = 0x1FF
	return m
}

func (m *mcp4xxxMock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.reads = append(m.reads, byte(reg))
	addr := int(reg >> 4)
	v := m.mem[addr]
	if addr == 5 {
		v = m.status
	}
	return []byte{byte(v >> 8 & 1), byte(v)}[:length], nil
}

func (m *mcp4xxxMock) WriteReg(reg uint32, data []byte) error {
	buf := append([]byte{byte(reg)}, data...)
	m.log = append(m.log, buf)
	for i := 0; i < len(buf); {
		cmd := int(buf[i])
		addr, op := cmd>>4, cmd>>2&3
		if op == 0 {
			m.mem[addr] = (cmd&1)<<8 | int(buf[i+1])
			i += 2
			continue
		}
		if addr < 2 {
			if op == 1 && m.mem[addr] < m.maxCode {
				m.mem[addr]++
			} else if op == 2 && m.mem[addr] > 0 {
				m.mem[addr]--
			}
		}
		i++
	}
	return nil
}

var _ connection.RegisterConnection = (*mcp4xxxMock)(nil)

type mcp4xxxPin struct{ log []string }

func (p *mcp4xxxPin) Set(high bool) error {
	if high {
		p.log = append(p.log, "HVC1")
	} else {
		p.log = append(p.log, "HVC0")
	}
	return nil
}

func newMCP4XXXChip(t *testing.T, part string, hvc connection.OutputPin) (*mcp4xxxMock, *MCP4XXXFull) {
	t.Helper()
	info, err := MCP4XXXLookup(part)
	if err != nil {
		t.Fatal(err)
	}
	conn := newMCP4XXXMock(info.MaxCode)
	chip, err := NewMCP4XXXFull(conn, part, 10000, 75, hvc, 1)
	if err != nil {
		t.Fatal(err)
	}
	return conn, chip
}

func lastLog(m *mcp4xxxMock) []byte { return m.log[len(m.log)-1] }

func TestMCP4XXXPartTable(t *testing.T) {
	if len(MCP4XXXParts()) != 16 {
		t.Fatalf("part table has %d rows, want 16", len(MCP4XXXParts()))
	}
	for part, want := range map[string]uint8{"mcp4531": 0x2E, "MCP4632": 0x58, "MCP4661": 0x28} {
		if got, err := MCP4XXXDefaultAddress(part); err != nil || got != want {
			t.Errorf("default address of %s = %#x, %v, want %#x", part, got, err, want)
		}
	}
	if _, err := NewMCP4XXXMinimal(newMCP4XXXMock(128), "MCP9999", 10000, 75); err == nil {
		t.Errorf("unknown part did not return an error")
	}
}

func TestMCP4XXXInitReadsOnly(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4662", nil)
	if err := chip.Init(); err != nil {
		t.Fatal(err)
	}
	if len(conn.log) != 0 || string(conn.reads) != string([]byte{0x0C, 0x1C, 0x4C, 0x5C}) {
		t.Errorf("init traffic: writes=%v reads=%v", conn.log, conn.reads)
	}
	if chip.Channels() != 2 || chip.Steps() != 257 || chip.IsPotentiometer() || !chip.IsNonvolatile() {
		t.Errorf("properties of MCP4662 wrong")
	}
}

func TestMCP4XXXWiperFormats(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4531", nil)
	chip.SetWiper(100, 0)
	if got := lastLog(conn); !bytesEqual(got, []byte{0x00, 100}) {
		t.Errorf("7-bit write = %v", got)
	}
	chip.SetWiper(999, 0)
	if got := lastLog(conn); !bytesEqual(got, []byte{0x00, 128}) {
		t.Errorf("clamp high = %v", got)
	}
	chip.SetWiper(-3, 0)
	if got := lastLog(conn); !bytesEqual(got, []byte{0x00, 0}) {
		t.Errorf("clamp low = %v", got)
	}
	if chip.SetWiper(1, 1) == nil {
		t.Errorf("channel 1 on a single part did not fail")
	}

	conn8, chip8 := newMCP4XXXChip(t, "MCP4652", nil)
	chip8.SetWiper(256, 1)
	if got := lastLog(conn8); !bytesEqual(got, []byte{0x11, 0x00}) {
		t.Errorf("8-bit D8 write = %v", got)
	}
	if v, _ := chip8.GetWiper(1); v != 256 {
		t.Errorf("GetWiper(1) = %d, want 256", v)
	}
	conn8.mem[0] = 0x3FF
	if v, _ := chip8.GetWiper(0); v != 256 {
		t.Errorf("above full scale not clamped: %d", v)
	}
}

func TestMCP4XXXResistanceAndRatio(t *testing.T) {
	_, chip := newMCP4XXXChip(t, "MCP4531", nil)
	chip.SetWiper(64, 0)
	if r, _ := chip.GetResistance(0); math.Abs(r-5075) > 1e-9 {
		t.Errorf("R_WB = %v", r)
	}
	if r, _ := chip.GetResistanceWA(0); math.Abs(r-5075) > 1e-9 {
		t.Errorf("R_WA = %v", r)
	}
	chip.SetResistance(1e9, 0)
	if v, _ := chip.GetWiper(0); v != 128 {
		t.Errorf("clamp high resistance -> %d", v)
	}
	chip.SetResistanceWA(5075, 0)
	if v, _ := chip.GetWiper(0); v != 64 {
		t.Errorf("SetResistanceWA -> %d", v)
	}
	chip.SetRatio(0.5, 0)
	if r, _ := chip.GetRatio(0); math.Abs(r-0.5) > 1e-9 {
		t.Errorf("ratio = %v", r)
	}
	if math.Abs(chip.GetResistanceStep()-10000.0/128) > 1e-9 {
		t.Errorf("step = %v", chip.GetResistanceStep())
	}
	_, rheo := newMCP4XXXChip(t, "MCP4532", nil)
	if _, err := rheo.GetResistanceWA(0); !errors.Is(err, ErrMCP4XXXUnsupported) {
		t.Errorf("rheostat W-A did not return ErrMCP4XXXUnsupported: %v", err)
	}
	if err := rheo.SetBoth(1, 2); !errors.Is(err, ErrMCP4XXXUnsupported) {
		t.Errorf("SetBoth on a single part: %v", err)
	}
}

func TestMCP4XXXIncrementDecrement(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4631", nil)
	chip.SetBoth(10, 20)
	if conn.mem[0] != 10 || conn.mem[1] != 20 {
		t.Errorf("SetBoth -> %d %d", conn.mem[0], conn.mem[1])
	}
	chip.SetWiper(120, 1)
	n := len(conn.log)
	if v, _ := chip.Increment(10, 1); v != 128 {
		t.Errorf("Increment saturation -> %d", v)
	}
	if len(conn.log) != n+1 || len(lastLog(conn)) != 10 || lastLog(conn)[0] != 0x14 {
		t.Errorf("Increment not one continuous transaction: %v", conn.log[n:])
	}
	chip.SetWiper(5, 0)
	if v, _ := chip.Decrement(10, 0); v != 0 {
		t.Errorf("Decrement saturation -> %d", v)
	}
	if v, _ := chip.Increment(1, 0); v != 1 || !bytesEqual(lastLog(conn), []byte{0x04}) {
		t.Errorf("Increment(1) -> %d, %v", v, lastLog(conn))
	}
}

func TestMCP4XXXTCON(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4631", nil)
	chip.Shutdown(1)
	if conn.mem[4] != 0x1FF&^0x80 {
		t.Errorf("shutdown ch1 TCON = %#x", conn.mem[4])
	}
	if s, _ := chip.IsShutdown(1); !s {
		t.Errorf("IsShutdown(1) false")
	}
	if s, _ := chip.IsShutdown(0); s {
		t.Errorf("IsShutdown(0) true")
	}
	chip.Resume(1)
	if conn.mem[4] != 0x1FF {
		t.Errorf("resume TCON = %#x", conn.mem[4])
	}
	chip.Shutdown(0)
	if conn.mem[4] != 0x1FF&^0x08 {
		t.Errorf("shutdown ch0 TCON = %#x", conn.mem[4])
	}
	chip.Resume(0)
	chip.SetTerminals(true, false, true, 1)
	if a, w, b, _ := chip.GetTerminals(1); !a || w || !b || conn.mem[4] != 0x1FF&^0x20 {
		t.Errorf("terminals ch1 = %v %v %v, TCON %#x", a, w, b, conn.mem[4])
	}
	chip.SetGeneralCall(false)
	if v, _ := chip.GetTCON(); v&0x100 != 0 {
		t.Errorf("general call still on: %#x", v)
	}
	chip.SetGeneralCall(true)
	if conn.mem[4]&0x100 == 0 {
		t.Errorf("general call not re-enabled")
	}
	chip.SetWiper(3, 0)
	chip.Shutdown(1)
	chip.Reset()
	if conn.mem[0] != 64 || conn.mem[1] != 64 || conn.mem[4] != 0x1FF {
		t.Errorf("reset -> %d %d %#x", conn.mem[0], conn.mem[1], conn.mem[4])
	}
}

func TestMCP4XXXRawAccess(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4532", nil)
	if v, err := chip.ReadRegister(4); err != nil || v != 0x1FF {
		t.Errorf("ReadRegister(4) = %v, %v", v, err)
	}
	chip.WriteRegister(0, 77)
	if conn.mem[0] != 77 {
		t.Errorf("WriteRegister(0) -> %d", conn.mem[0])
	}
	if chip.WriteRegister(5, 0) == nil {
		t.Errorf("write to STATUS accepted")
	}
	for _, a := range []int{2, 16} {
		if _, err := chip.ReadRegister(a); err == nil {
			t.Errorf("read of address %d accepted on a volatile single part", a)
		}
	}
}

func TestMCP4XXXNonvolatileUnsupportedOnVolatile(t *testing.T) {
	_, chip := newMCP4XXXChip(t, "MCP4532", nil)
	checks := map[string]error{
		"ReadStatus":    func() error { _, e := chip.ReadStatus(); return e }(),
		"WaitReady":     chip.WaitReady(5),
		"GetNVWiper":    func() error { _, e := chip.GetNVWiper(0); return e }(),
		"StoreWiper":    func() error { _, e := chip.StoreWiper(0); return e }(),
		"ReadData":      func() error { _, e := chip.ReadData(0); return e }(),
		"SetWriteProt.": chip.SetWriteProtect(true),
	}
	for name, err := range checks {
		if !errors.Is(err, ErrMCP4XXXUnsupported) {
			t.Errorf("%s on a volatile part: %v", name, err)
		}
	}
}

func TestMCP4XXXNonvolatile(t *testing.T) {
	pin := &mcp4xxxPin{}
	conn, chip := newMCP4XXXChip(t, "MCP4662", pin)
	if st, _ := chip.ReadStatus(); st.EEPROMBusy || st.WiperLock != [2]bool{} || st.WriteProtect {
		t.Errorf("idle status = %+v", st)
	}
	conn.status = 0x1F0 | 0x08 | 0x02 | 0x01
	if st, _ := chip.ReadStatus(); !st.EEPROMBusy || !st.WiperLock[0] || st.WiperLock[1] || !st.WriteProtect {
		t.Errorf("flag status = %+v", st)
	}
	if l, _ := chip.IsWiperLocked(0); !l {
		t.Errorf("IsWiperLocked(0) false")
	}
	if p, _ := chip.IsWriteProtected(); !p {
		t.Errorf("IsWriteProtected false")
	}
	if err := chip.WaitReady(3); !errors.Is(err, ErrMCP4XXXTimeout) {
		t.Errorf("WaitReady with busy EEPROM: %v", err)
	}
	conn.status = 0x1F0

	chip.SetNVWiper(200, 1)
	if conn.mem[3] != 200 {
		t.Errorf("SetNVWiper -> %d", conn.mem[3])
	}
	chip.SetWiper(150, 0)
	if v, _ := chip.StoreWiper(0); v != 150 || conn.mem[2] != 150 {
		t.Errorf("StoreWiper -> %d / %d", v, conn.mem[2])
	}
	chip.WriteData(3, 0x1AB)
	if v, _ := chip.ReadData(3); v != 0x1AB || !bytesEqual(lastLog(conn), []byte{0x91, 0xAB}) {
		t.Errorf("data register -> %#x, %v", v, lastLog(conn))
	}
	if _, err := chip.ReadData(10); err == nil {
		t.Errorf("index 10 accepted")
	}
	chip.SetWiper(7, 0)
	chip.Reset()
	if conn.mem[0] != 150 || conn.mem[1] != 200 {
		t.Errorf("NV reset -> %d %d", conn.mem[0], conn.mem[1])
	}

	// High-voltage commands: exact single command bytes, pin asserted around the write.
	conn.log = nil
	chip.SetWiperLock(true, 0)
	if !bytesEqual(conn.log[0], []byte{0x28}) || pin.log[len(pin.log)-2] != "HVC1" || pin.log[len(pin.log)-1] != "HVC0" {
		t.Errorf("WiperLock enable 0: %v / %v", conn.log, pin.log)
	}
	for _, c := range []struct {
		fire func()
		want byte
	}{
		{func() { chip.SetWiperLock(false, 1) }, 0x34},
		{func() { chip.SetWiperLock(true, 1) }, 0x38},
		{func() { chip.SetWriteProtect(true) }, 0xF8},
		{func() { chip.SetWriteProtect(false) }, 0xF4},
	} {
		conn.log = nil
		c.fire()
		if len(conn.log) == 0 || !bytesEqual(conn.log[0], []byte{c.want}) {
			t.Errorf("high-voltage command %#x not sent: %v", c.want, conn.log)
		}
	}
	_, nohv := newMCP4XXXChip(t, "MCP4562", nil)
	if err := nohv.SetWiperLock(true, 0); !errors.Is(err, ErrMCP4XXXUnsupported) {
		t.Errorf("hv command without pin: %v", err)
	}
}

func TestMCP4XXXSetRAB(t *testing.T) {
	conn, chip := newMCP4XXXChip(t, "MCP4532", nil)
	n := len(conn.log)
	chip.SetRAB(50000, 100)
	if len(conn.log) != n {
		t.Errorf("SetRAB produced traffic")
	}
	chip.SetWiper(128, 0)
	if r, _ := chip.GetResistance(0); math.Abs(r-50100) > 1e-9 {
		t.Errorf("R after SetRAB = %v", r)
	}
}
