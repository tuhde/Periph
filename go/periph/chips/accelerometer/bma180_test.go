package accelerometer

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// bma180MockConnection is an in-memory fake connection.RegisterConnection for
// unit tests — no hardware, no bus.
type bma180MockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	regReads  [][2]uint32
}

func newBMA180MockConnection() *bma180MockConnection {
	return &bma180MockConnection{registers: map[byte]byte{}}
}

func (m *bma180MockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *bma180MockConnection) Write(data []byte) error {
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

func (m *bma180MockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

func (m *bma180MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *bma180MockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *bma180MockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *bma180MockConnection) Close() error                { return nil }
func (m *bma180MockConnection) Enable()                     {}
func (m *bma180MockConnection) Disable()                    {}
func (m *bma180MockConnection) IsEnabled() bool             { return true }
func (m *bma180MockConnection) IntPin() connection.InputPin { return nil }
func (m *bma180MockConnection) EnPin() connection.OutputPin { return nil }

func newBMA180Connection() *bma180MockConnection {
	c := newBMA180MockConnection()
	c.setRegister(bma180RegChipID, 0x03)
	c.setRegister(bma180RegCtrlReg0, 0x00)
	c.setRegister(bma180RegOffsetLSB1, 0x00)
	c.setRegister(bma180RegBWTCS, 0x00)
	// raw_x = +0x200 (MSB=0x08); raw_y = -0x200 (MSB=0xF8); raw_z = 0.
	c.setRegister(bma180RegAccXLSB, 0x00, 0x08)
	c.setRegister(bma180RegAccYLSB, 0x00, 0xF8)
	c.setRegister(bma180RegAccZLSB, 0x00, 0x00)
	return c
}

func lastWriteToBMA180(conn *bma180MockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func abs32BMA180(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func TestBMA180MinimalConstructionAndRead(t *testing.T) {
	conn := newBMA180Connection()
	chip, err := NewBMA180Minimal(conn)
	if err != nil {
		t.Fatalf("NewBMA180Minimal: %v", err)
	}
	// Init writes ee_w=0x10, OFFSET_LSB1=(0x00 & 0xF1) | 0x04 = 0x04, BW_TCS=(0x00 & 0x0F) | 0x40 = 0x40.
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg0); v&0x10 == 0 {
		t.Errorf("CTRL_REG0 ee_w not set, write = %#x", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegOffsetLSB1); v != 0x04 {
		t.Errorf("OFFSET_LSB1 write = %#x, want 0x04", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegBWTCS); v != 0x40 {
		t.Errorf("BW_TCS write = %#x, want 0x40", v)
	}
	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32BMA180(x-0.125) > 1e-6 || abs32BMA180(y-(-0.125)) > 1e-6 || abs32BMA180(z-0.0) > 1e-6 {
		t.Errorf("Read() = (%v, %v, %v), want ~(0.125, -0.125, 0)", x, y, z)
	}
}

func TestBMA180MinimalConstructionBadChipID(t *testing.T) {
	conn := newBMA180MockConnection()
	conn.setRegister(bma180RegChipID, 0xFF)
	if _, err := NewBMA180Minimal(conn); err == nil {
		t.Error("NewBMA180Minimal with bad CHIP_ID: expected error, got nil")
	}
}

func TestBMA180FullRangeAndBandwidth(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	// (0x04 & 0xF1) | 0x0A = 0x0A.
	if v, _ := lastWriteToBMA180(conn, bma180RegOffsetLSB1); v != 0x0A {
		t.Errorf("SetRange(8) OFFSET_LSB1 write = %#x, want 0x0A", v)
	}
	if err := full.SetBandwidth(40); err != nil {
		t.Fatalf("SetBandwidth: %v", err)
	}
	// (0x40 & 0x0F) | 0x20 = 0x20.
	if v, _ := lastWriteToBMA180(conn, bma180RegBWTCS); v != 0x20 {
		t.Errorf("SetBandwidth(40) BW_TCS write = %#x, want 0x20", v)
	}
	if err := full.SetFilterMode(1); err != nil {
		t.Fatalf("SetFilterMode: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegBWTCS); v&0xF0 != 0x80 {
		t.Errorf("SetFilterMode(1) BW_TCS write = %#x, want bits 7:4 = 0x80", v)
	}
}

func TestBMA180FullReadRaw(t *testing.T) {
	conn := newBMA180Connection()
	conn.setRegister(bma180RegAccXLSB, 0x00, 0x08)
	conn.setRegister(bma180RegAccYLSB, 0x00, 0x00)
	conn.setRegister(bma180RegAccZLSB, 0x00, 0x04)
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	rx, ry, rz, err := full.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if rx != 512 || ry != 0 || rz != 256 {
		t.Errorf("ReadRaw() = (%d, %d, %d), want (512, 0, 256)", rx, ry, rz)
	}
}

func TestBMA180FullReadTemperature(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	conn.setRegister(bma180RegTemp, 0x02)
	if t1, _ := full.ReadTemperature(); abs32BMA180(t1-25.0) > 1e-6 {
		t.Errorf("ReadTemperature(0x02) = %v, want 25.0", t1)
	}
	conn.setRegister(bma180RegTemp, 0x82)
	if t2, _ := full.ReadTemperature(); abs32BMA180(t2-(-39.0)) > 1e-6 {
		t.Errorf("ReadTemperature(0x82) = %v, want -39.0", t2)
	}
}

func TestBMA180FullLowGAndHighG(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	// range=2: setLowG(0.3, 40): round(0.3/2 * 255) = 38.
	conn.setRegister(bma180RegLowDur, 0x01)  // bit 0 (tco_range) pre-set
	if err := full.SetLowG(0.3, 40, 0.05, 0x07, 0, true); err != nil {
		t.Fatalf("SetLowG: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegLowTh); v != 38 {
		t.Errorf("LOW_TH write = %d, want 38", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegLowDur); v&0x01 != 0x01 {
		t.Errorf("LOW_DUR write = %#x, want bit 0 (tco_range) preserved", v)
	}
	// HIGH axes bit 7:5 = 0xE0 set; high_filt bit 4 set.
	conn.setRegister(bma180RegHighLowInfo, 0x00)
	conn.setRegister(bma180RegHighDur, 0x00)
	if err := full.SetHighG(1.8, 20, 0.1, 0x07, 0, true); err != nil {
		t.Fatalf("SetHighG: %v", err)
	}
	// range=2 -> code = round(1.8/2*255) = round(229.5) = 230.
	if v, _ := lastWriteToBMA180(conn, bma180RegHighTh); v != 230 {
		t.Errorf("HIGH_TH write = %d, want 230", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegHighLowInfo); v&0xE0 != 0xE0 {
		t.Errorf("HIGH axes = %#x, want 0xE0 (X+Y+Z high axes enabled)", v)
	}
}

func TestBMA180FullSlopeAndTap(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	if err := full.SetSlope(0.3, 3, 0x07, true); err != nil {
		t.Fatalf("SetSlope: %v", err)
	}
	// range=2 -> round(0.3 / (0.0156 * 2 / 2)) = round(19.23) = 19.
	if v, _ := lastWriteToBMA180(conn, bma180RegSlopeTh); v != 19 {
		t.Errorf("SLOPE_TH write = %d, want 19", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegTCOX); v&0x03 != 0x01 {
		t.Errorf("TCO_X slope_dur = %#x, want 0x01 (3 samples)", v)
	}
	// Last CTRL_REG3 write: slope_int (0x40) + adv_int (0x04) = 0x44.
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg3); v&0x44 != 0x44 {
		t.Errorf("CTRL_REG3 = %#x, want bits 6+2 (slope_int + adv_int) set", v)
	}
	if err := full.SetTap(0.5, 250, 0x07, true); err != nil {
		t.Fatalf("SetTap: %v", err)
	}
	// window 250 -> 0x04 in GAIN_T bits 2:0.
	if v, _ := lastWriteToBMA180(conn, bma180RegGainT); v&0x07 != 0x04 {
		t.Errorf("GAIN_T tap_dur = %#x, want 0x04 (250 ms)", v)
	}
}

func TestBMA180FullSetLatchAndClear(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	if err := full.SetLatch(true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg3); v&0x01 == 0 {
		t.Errorf("CTRL_REG3 = %#x, want lat_int (bit 0) set", v)
	}
	if err := full.ClearInterrupt(); err != nil {
		t.Fatalf("ClearInterrupt: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg0); v&0x40 == 0 {
		t.Errorf("CTRL_REG0 = %#x, want reset_int (bit 6) set", v)
	}
}

func TestBMA180FullSleepWakeAndWakeUp(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	if err := full.Sleep(); err != nil {
		t.Fatalf("Sleep: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg0); v&0x02 == 0 {
		t.Errorf("CTRL_REG0 = %#x, want sleep bit (2) set", v)
	}
	if err := full.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegCtrlReg0); v&0x02 != 0 {
		t.Errorf("CTRL_REG0 = %#x, want sleep bit cleared", v)
	}
	conn.setRegister(bma180RegTCOY, 0x00)
	conn.setRegister(bma180RegGainZ, 0x00)
	if err := full.SetWakeUp(true, 80); err != nil {
		t.Fatalf("SetWakeUp: %v", err)
	}
	// TCO_Y (0x00 & 0xFC) | 0x01 = 0x01.
	if v, _ := lastWriteToBMA180(conn, bma180RegTCOY); v != 0x01 {
		t.Errorf("TCO_Y wake_dur = %#x, want 0x01", v)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegGainZ); v&0x01 == 0 {
		t.Errorf("GAIN_Z wake_up = %#x, want bit 0 set", v)
	}
}

func TestBMA180FullSoftReset(t *testing.T) {
	conn := newBMA180Connection()
	conn.setRegister(bma180RegChipID, 0x03)
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	if err := full.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegReset); v != bma180SoftResetCmd {
		t.Errorf("RESET write = %#x, want 0xB6", v)
	}
}

func TestBMA180FullVersionAndCustomer(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	conn.setRegister(bma180RegVersion, 0xAB)
	al, ml, err := full.ReadVersion()
	if err != nil {
		t.Fatalf("ReadVersion: %v", err)
	}
	if al != 0x0A || ml != 0x0B {
		t.Errorf("ReadVersion() = (%d, %d), want (0xA, 0xB)", al, ml)
	}
	conn.setRegister(bma180RegCD1, 0xA5)
	if v, _ := full.ReadCustomer(0); v != 0xA5 {
		t.Errorf("ReadCustomer(0) = %#x, want 0xA5", v)
	}
	if err := full.WriteCustomer(1, 0x5A); err != nil {
		t.Fatalf("WriteCustomer: %v", err)
	}
	if v, _ := lastWriteToBMA180(conn, bma180RegCD2); v != 0x5A {
		t.Errorf("CD2 write = %#x, want 0x5A", v)
	}
}

func TestBMA180FullPollInterrupt(t *testing.T) {
	conn := newBMA180Connection()
	full, err := NewBMA180Full(conn)
	if err != nil {
		t.Fatalf("NewBMA180Full: %v", err)
	}
	conn.setRegister(bma180RegStatusReg3, 0x80)
	v, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if v != 0x80 {
		t.Errorf("PollInterrupt = %#x, want 0x80", v)
	}
}