package adcdac

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ds3502Mock is a register-map mock of the DS3502 with NACK injection.
type ds3502Mock struct {
	regs      map[uint8]uint8
	writes    [][]byte
	failN     int // number of upcoming bus transactions that NACK
	failAfter int // transactions to let through before failing
}

func newDS3502Mock(wr uint8) *ds3502Mock {
	return &ds3502Mock{regs: map[uint8]uint8{0x00: wr, 0x02: 0x00}}
}

func (m *ds3502Mock) nack() bool {
	if m.failAfter > 0 {
		m.failAfter--
		return false
	}
	if m.failN > 0 {
		m.failN--
		return true
	}
	return false
}

func (m *ds3502Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if m.nack() {
		return nil, errors.New("nack")
	}
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[uint8(reg)+uint8(i)]
	}
	return out, nil
}

func (m *ds3502Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if m.nack() {
		return errors.New("nack")
	}
	for i, v := range data {
		m.regs[uint8(reg)+uint8(i)] = v
	}
	return nil
}

func (m *ds3502Mock) Write(data []byte) error                   { return nil }
func (m *ds3502Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *ds3502Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *ds3502Mock) Close() error                              { return nil }
func (m *ds3502Mock) Enable()                                   {}
func (m *ds3502Mock) Disable()                                  {}
func (m *ds3502Mock) IsEnabled() bool                           { return true }
func (m *ds3502Mock) IntPin() connection.InputPin               { return nil }
func (m *ds3502Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*ds3502Mock)(nil)

func (m *ds3502Mock) dataWrites() [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			out = append(out, w)
		}
	}
	return out
}

func ds3502Near(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-3 }

func TestDS3502Init(t *testing.T) {
	m := newDS3502Mock(0x23)
	pot, err := NewDS3502Full(m, 1.0, 0.0)
	if err != nil {
		t.Fatal(err)
	}
	if m.regs[0x02] != 0x80 {
		t.Errorf("init must set MODE = 1: CR = %#x", m.regs[0x02])
	}
	if dw := m.dataWrites(); len(dw) != 1 || dw[0][0] != 0x02 {
		t.Errorf("init must write only CR: %v", dw)
	}
	if m.regs[0x00] != 0x23 {
		t.Errorf("init must not touch the wiper: WR = %#x", m.regs[0x00])
	}
	if v, _ := pot.GetWiper(); v != 0x23 {
		t.Errorf("GetWiper = %#x", v)
	}
}

func TestDS3502InitRetriesAfterNack(t *testing.T) {
	m := newDS3502Mock(0x40)
	m.failN = 1
	if _, err := NewDS3502Minimal(m, 1, 0); err != nil {
		t.Fatalf("retry should succeed: %v", err)
	}
	if m.regs[0x02] != 0x80 || len(m.dataWrites()) != 2 {
		t.Errorf("CR = %#x, data writes %v", m.regs[0x02], m.dataWrites())
	}
	m = newDS3502Mock(0x40)
	m.failN = 2
	if _, err := NewDS3502Minimal(m, 1, 0); err == nil {
		t.Error("two NACKs must fail the init")
	}
}

func TestDS3502WiperAndVoltage(t *testing.T) {
	m := newDS3502Mock(0x40)
	pot, _ := NewDS3502Minimal(m, 15.0, 0.0)
	pot.SetWiper(100)
	if m.regs[0] != 100 {
		t.Errorf("set = %d", m.regs[0])
	}
	pot.SetWiper(300)
	if m.regs[0] != 127 {
		t.Errorf("clamp hi = %d", m.regs[0])
	}
	pot.SetWiper(-5)
	if m.regs[0] != 0 {
		t.Errorf("clamp lo = %d", m.regs[0])
	}
	m.regs[0] = 0xFF
	if v, _ := pot.GetWiper(); v != 127 {
		t.Errorf("bit 7 not masked: %d", v)
	}
	pot.SetVoltage(6.0)
	if m.regs[0] != 51 {
		t.Errorf("SetVoltage(6) code = %d, want 51", m.regs[0])
	}
	if v, _ := pot.GetVoltage(); !ds3502Near(v, 15.0*51.0/127.0) {
		t.Errorf("GetVoltage = %v", v)
	}
	pot.SetVoltage(99)
	if m.regs[0] != 127 {
		t.Errorf("voltage clamp hi = %d", m.regs[0])
	}
	pot.SetVoltage(-3)
	if m.regs[0] != 0 {
		t.Errorf("voltage clamp lo = %d", m.regs[0])
	}

	m2 := newDS3502Mock(0x40)
	p2, _ := NewDS3502Minimal(m2, 12.0, 2.0)
	p2.SetVoltage(7.0)
	if m2.regs[0] != 64 {
		t.Errorf("offset low = %d", m2.regs[0])
	}
	m3 := newDS3502Mock(0x40)
	p3, _ := NewDS3502Minimal(m3, 5.0, 5.0)
	p3.SetVoltage(5.0)
	if m3.regs[0] != 0 {
		t.Errorf("zero span = %d", m3.regs[0])
	}
}

func TestDS3502RatioResistanceIncrement(t *testing.T) {
	m := newDS3502Mock(0x40)
	pot, _ := NewDS3502Full(m, 1, 0)
	pot.SetRatio(0.5)
	if m.regs[0] != 64 {
		t.Errorf("ratio = %d", m.regs[0])
	}
	pot.SetRatio(2)
	if m.regs[0] != 127 {
		t.Errorf("ratio clamp = %d", m.regs[0])
	}
	if r, _ := pot.GetRatio(); r != 1 {
		t.Errorf("GetRatio = %v", r)
	}
	m.regs[0] = 0
	rl, _ := pot.GetResistance('L')
	rh, _ := pot.GetResistance('H')
	if rl != 0 || rh != 10000 {
		t.Errorf("resistance at 0: %v %v", rl, rh)
	}
	m.regs[0] = 127
	rl, _ = pot.GetResistance('L')
	rh, _ = pot.GetResistance('H')
	if rl != 10000 || rh != 0 {
		t.Errorf("resistance at 127: %v %v", rl, rh)
	}
	if _, err := pot.GetResistance('X'); err == nil {
		t.Error("bad terminal must fail")
	}
	m.regs[0] = 0x40
	if c, _ := pot.Increment(5); c != 69 || m.regs[0] != 69 {
		t.Errorf("increment = %d", c)
	}
	if c, _ := pot.Increment(-10); c != 59 {
		t.Errorf("decrement = %d", c)
	}
	m.regs[0] = 125
	if c, _ := pot.Increment(10); c != 127 {
		t.Errorf("clamp hi = %d", c)
	}
	m.regs[0] = 3
	if c, _ := pot.Increment(-10); c != 0 {
		t.Errorf("clamp lo = %d", c)
	}
}

func TestDS3502ModeAndStore(t *testing.T) {
	m := newDS3502Mock(0x40)
	pot, _ := NewDS3502Full(m, 1, 0)
	if eeprom, _ := pot.GetMode(); eeprom {
		t.Error("init mode must be SRAM")
	}
	pot.SetMode(true)
	if eeprom, _ := pot.GetMode(); !eeprom || m.regs[2] != 0x00 {
		t.Errorf("SetMode(true): CR = %#x", m.regs[2])
	}
	pot.SetMode(false)
	if m.regs[2] != 0x80 {
		t.Errorf("SetMode(false): CR = %#x", m.regs[2])
	}

	n := len(m.dataWrites())
	if err := pot.StoreWiper(77); err != nil {
		t.Fatal(err)
	}
	seq := m.dataWrites()[n:]
	if len(seq) != 3 || seq[0][0] != 0x02 || seq[0][1] != 0x00 || seq[1][0] != 0x00 || seq[1][1] != 77 || seq[2][0] != 0x02 || seq[2][1] != 0x80 {
		t.Errorf("StoreWiper sequence = %v", seq)
	}
	if m.regs[2] != 0x80 {
		t.Errorf("StoreWiper must restore MODE = 1: CR = %#x", m.regs[2])
	}
	m.regs[0] = 33
	pot.StoreWiper(-1)
	if m.regs[0] != 33 {
		t.Errorf("StoreWiper(-1) stored %d", m.regs[0])
	}
}

func TestDS3502StoreWiperRestoresModeOnTimeout(t *testing.T) {
	m := newDS3502Mock(0x40)
	pot, _ := NewDS3502Full(m, 1, 0)
	m.failAfter = 2 // CR <- 0, WR <- code succeed
	m.failN = 31    // all 31 polls NACK
	if err := pot.StoreWiper(10); !errors.Is(err, ErrDS3502Timeout) {
		t.Errorf("err = %v, want timeout", err)
	}
	if m.regs[2] != 0x80 {
		t.Errorf("MODE not restored: CR = %#x", m.regs[2])
	}
}

func TestDS3502WaitReadyAndEndpoints(t *testing.T) {
	m := newDS3502Mock(0x40)
	pot, _ := NewDS3502Full(m, 1, 0)
	m.failN = 3
	if err := pot.WaitReady(30); err != nil {
		t.Errorf("WaitReady: %v", err)
	}
	m.failN = 1000
	if err := pot.WaitReady(5); !errors.Is(err, ErrDS3502Timeout) {
		t.Errorf("WaitReady timeout: %v", err)
	}
	m.failN = 0
	n := len(m.writes)
	pot.SetEndpoints(10, 0)
	if len(m.writes) != n {
		t.Error("SetEndpoints must not touch the bus")
	}
	m.regs[0] = 127
	if v, _ := pot.GetVoltage(); !ds3502Near(v, 10) {
		t.Errorf("GetVoltage after SetEndpoints = %v", v)
	}
}
