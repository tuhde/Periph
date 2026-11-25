package other

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// pca9541Mock is a register-map mock of the PCA9541 (this master's view) that
// recomputes the read-only N bits of CONTROL after a write, with NACK injection.
type pca9541Mock struct {
	regs   map[uint8]uint8
	writes [][]byte
	failN  int
}

func newPCA9541Mock(control, ie, istat uint8) *pca9541Mock {
	return &pca9541Mock{regs: map[uint8]uint8{0x00: ie, 0x01: control, 0x02: istat, 0x10: ie, 0x11: control, 0x12: istat}}
}

func (m *pca9541Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if m.failN > 0 {
		m.failN--
		return nil, errors.New("nack")
	}
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[uint8(reg)+uint8(i)]
	}
	return out, nil
}

func (m *pca9541Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if m.failN > 0 {
		m.failN--
		return errors.New("nack")
	}
	prior := m.regs[0x01]
	for i, v := range data {
		m.regs[uint8(reg)+uint8(i)] = v
	}
	if reg == 0x01 && len(data) == 1 {
		w := data[0]
		mybus, buson := w&1, (w>>2)&1
		nmybusPrior, nbusonPrior := (prior>>1)&1, (prior>>3)&1
		control := mybus == nmybusPrior
		on := buson != nbusonPrior
		nmybus := 1 - mybus
		if control {
			nmybus = mybus
		}
		nbuson := buson
		if on {
			nbuson = 1 - buson
		}
		m.regs[0x01] = w&0xF5 | nmybus<<1 | nbuson<<3
	}
	return nil
}

func (m *pca9541Mock) Write(data []byte) error                   { return nil }
func (m *pca9541Mock) Read(n int) ([]byte, error)                { return make([]byte, n), nil }
func (m *pca9541Mock) WriteRead(d []byte, n int) ([]byte, error) { return make([]byte, n), nil }
func (m *pca9541Mock) Close() error                              { return nil }
func (m *pca9541Mock) Enable()                                   {}
func (m *pca9541Mock) Disable()                                  {}
func (m *pca9541Mock) IsEnabled() bool                           { return true }
func (m *pca9541Mock) IntPin() connection.InputPin               { return nil }
func (m *pca9541Mock) EnPin() connection.OutputPin               { return nil }

var _ connection.RegisterConnection = (*pca9541Mock)(nil)

func (m *pca9541Mock) dataWrites() [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) > 1 {
			out = append(out, w)
		}
	}
	return out
}

func newPCA9541(t *testing.T, control, ie, istat uint8) (*pca9541Mock, *PCA9541Full) {
	t.Helper()
	m := newPCA9541Mock(control, ie, istat)
	chip, err := NewPCA9541Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return m, chip
}

func TestPCA9541Init(t *testing.T) {
	m, _ := newPCA9541(t, 0x04, 0, 0)
	if len(m.dataWrites()) != 0 {
		t.Errorf("init wrote: %v", m.dataWrites())
	}
	if len(m.writes) != 2 || m.writes[0][0] != 0x01 || m.writes[1][0] != 0x02 {
		t.Errorf("init must read CONTROL then ISTAT: %v", m.writes)
	}
	bad := newPCA9541Mock(0, 0, 0)
	bad.failN = 1
	if _, err := NewPCA9541Minimal(bad); err == nil {
		t.Error("init must fail when the chip does not acknowledge")
	}
}

func TestPCA9541AcquireTable12(t *testing.T) {
	table := map[uint8]uint8{0x0: 0x4, 0x1: 0x4, 0x2: 0x5, 0x3: 0x5, 0x5: 0x4, 0x6: 0x5,
		0x9: 0x0, 0xA: 0x1, 0xC: 0x0, 0xD: 0x0, 0xE: 0x1, 0xF: 0x1}
	for read, want := range table {
		m, chip := newPCA9541(t, read, 0, 0)
		chip.Acquire(false, 0)
		dw := m.dataWrites()
		if len(dw) == 0 || dw[len(dw)-1][0] != 0x01 || dw[len(dw)-1][1] != want {
			t.Errorf("CONTROL read %#x: wrote %v, want %#x", read, dw, want)
		}
	}
	for _, read := range []uint8{0x4, 0x7, 0x8, 0xB} {
		_, chip := newPCA9541(t, read, 0, 0)
		if ok, _ := chip.Acquire(false, 0); !ok {
			t.Errorf("CONTROL read %#x: acquire must keep control", read)
		}
	}
}

func TestPCA9541HasControlAndBusInit(t *testing.T) {
	cases := map[uint8]bool{0x04: true, 0x0A: false, 0x00: false, 0x02: false, 0x07: true, 0x05: false}
	for c, want := range cases {
		_, chip := newPCA9541(t, c, 0, 0)
		if got, _ := chip.HasControl(); got != want {
			t.Errorf("HasControl(%#x) = %v", c, got)
		}
	}
	m, chip := newPCA9541(t, 0x0A, 0, 0x02)
	if ok, _ := chip.Acquire(true, 20); !ok {
		t.Error("acquire with bus init failed")
	}
	if dw := m.dataWrites(); dw[len(dw)-1][1] != 0x11 {
		t.Errorf("bus init write = %v", dw[len(dw)-1])
	}
	_, chip2 := newPCA9541(t, 0x0A, 0, 0x00)
	if ok, _ := chip2.Acquire(true, 3); ok {
		t.Error("acquire must time out without BUSINIT")
	}
	m3, chip3 := newPCA9541(t, 0x0A, 0, 0x02)
	if ok, _ := chip3.RecoverBus(20); !ok || m3.dataWrites()[len(m3.dataWrites())-1][1]&0x10 == 0 {
		t.Error("RecoverBus must acquire with bus init")
	}
}

func TestPCA9541ReleaseConnectDisconnect(t *testing.T) {
	m, chip := newPCA9541(t, 0x04, 0, 0)
	chip.Release()
	if m.dataWrites()[0][1] != 0x05 {
		t.Errorf("release write = %v", m.dataWrites())
	}
	if has, _ := chip.HasControl(); has {
		t.Error("release must give the bus away")
	}
	m2, chip2 := newPCA9541(t, 0x0D, 0, 0)
	chip2.Connect()
	if m2.dataWrites()[0][1] != 0x01 {
		t.Errorf("connect write = %v", m2.dataWrites())
	}
	m3, chip3 := newPCA9541(t, 0x04, 0, 0)
	chip3.Disconnect()
	if conn, _ := chip3.IsConnected(); conn || m3.dataWrites()[0][1] != 0x00 {
		t.Errorf("disconnect write = %v", m3.dataWrites())
	}
	chip3.Connect()
	if conn, _ := chip3.IsConnected(); !conn {
		t.Error("connect must connect")
	}
}

func TestPCA9541StatusAndRegisters(t *testing.T) {
	_, chip := newPCA9541(t, 0xD4, 0x03, 0x08)
	st, _ := chip.Status()
	if c, _ := chip.GetControl(); c != 0xD4 || !st.HasControl || !st.Connected || !st.BusInitRequested || !st.OwnTest || !st.OtherTest {
		t.Errorf("status = %+v", st)
	}
	n := 0
	m, chip2 := newPCA9541(t, 0x04, 0x03, 0x08)
	n = len(m.writes)
	ie, c, s, _ := chip2.ReadAll()
	if ie != 3 || c != 4 || s != 8 || len(m.writes) != n+1 || m.writes[len(m.writes)-1][0] != 0x10 {
		t.Errorf("ReadAll = %d %d %d, writes %v", ie, c, s, m.writes[n:])
	}
}

func TestPCA9541InterruptMaskAndTest(t *testing.T) {
	m, chip := newPCA9541(t, 0x0D, 0, 0)
	chip.SetInterruptMask(true, false, false, true)
	if v, _ := chip.GetInterruptMask(); v != 0x09 {
		t.Errorf("mask = %#x", v)
	}
	chip.SetInterruptMask(false, false, false, false)
	chip.DisableInterrupt(PCA9541SourceBusInit)
	if m.regs[0] != 0x02 {
		t.Errorf("disable: %#x", m.regs[0])
	}
	chip.DisableInterrupt(PCA9541SourceIntIn)
	chip.EnableInterrupt(PCA9541SourceBusInit)
	if m.regs[0] != 0x01 {
		t.Errorf("enable: %#x", m.regs[0])
	}
	n := len(m.dataWrites())
	chip.EnableInterrupt(PCA9541SourceTest)
	if len(m.dataWrites()) != n {
		t.Error("test sources need no enable")
	}
	bits := m.regs[1] & 0x05
	chip.TestInterrupt(true, false)
	if m.regs[1]&0xC5 != bits|0x40 {
		t.Errorf("own test: %#x", m.regs[1])
	}
	chip.TestInterrupt(false, true)
	if m.regs[1]&0xC0 != 0x80 {
		t.Errorf("other test: %#x", m.regs[1])
	}
	chip.TestInterrupt(false, false)
	if m.regs[1]&0xC0 != 0 || m.regs[1]&0x05 != bits {
		t.Errorf("clear test: %#x", m.regs[1])
	}
}

func TestPCA9541PollAndCallback(t *testing.T) {
	m, chip := newPCA9541(t, 0x04, 0, 0x0A)
	if v, _ := chip.PollInterrupt(); v != 0x0A {
		t.Errorf("poll = %#x", v)
	}
	var got []uint8
	chip.callback = func(s uint8) { got = append(got, s) }
	chip.handleEdge()
	m.regs[2] = 0
	chip.handleEdge()
	if len(got) != 1 || got[0] != 0x0A {
		t.Errorf("callback calls = %v", got)
	}
	chip.OffInterrupt()
	if chip.callback != nil {
		t.Error("OffInterrupt must clear the callback")
	}
}
