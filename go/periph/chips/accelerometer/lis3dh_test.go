package accelerometer

import (
	"math"
	"sync"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// lis3dhWrite records one register write of the mock connection.
type lis3dhWrite struct {
	reg  byte
	data []byte
}

// lis3dhFakePin is an InputPin that never fires by itself — the test
// triggers the registered handler explicitly.
type lis3dhFakePin struct {
	mu        sync.Mutex
	handlers  []func()
	unsubs    int
	triggers  []connection.Trigger
	high      bool
	highError error
}

// OnEdge records the handler and returns an unsubscribe closure.
func (p *lis3dhFakePin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.handlers = append(p.handlers, handler)
	p.triggers = append(p.triggers, trigger)
	return func() {
		p.mu.Lock()
		p.unsubs++
		p.mu.Unlock()
	}
}

// IsHigh returns the canned level.
func (p *lis3dhFakePin) IsHigh() (bool, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.high, p.highError
}

// fire calls every registered handler once.
func (p *lis3dhFakePin) fire() {
	p.mu.Lock()
	handlers := make([]func(), len(p.handlers))
	copy(handlers, p.handlers)
	p.mu.Unlock()
	for _, h := range handlers {
		h()
	}
}

// lis3dhMockConnection is an in-memory fake connection.RegisterConnection
// for unit tests — no hardware, no bus.
type lis3dhMockConnection struct {
	mu        sync.Mutex
	registers map[byte]byte
	writes    []lis3dhWrite
	regReads  [][2]uint32
	intPin    *lis3dhFakePin

	// selfTestDelta is the number of counts the mock adds to the
	// acceleration output while the self-test bits are set. A value of 0
	// simulates a chip that does not react to the self-test.
	selfTestDelta int
}

// lis3dhSelfTestDelta is the number of counts a responding chip adds to the
// acceleration output during the self-test.
const lis3dhSelfTestDelta = 100

// newLIS3DHMockConnection returns a mock connection with an empty register
// file.
func newLIS3DHMockConnection() *lis3dhMockConnection {
	return &lis3dhMockConnection{registers: map[byte]byte{}, selfTestDelta: lis3dhSelfTestDelta}
}

// setRegister sets one or more consecutive registers.
func (m *lis3dhMockConnection) setRegister(reg byte, values ...byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

// Write records a raw write.
func (m *lis3dhMockConnection) Write(data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, lis3dhWrite{data: append([]byte(nil), data...)})
	return nil
}

// Read returns n zero bytes.
func (m *lis3dhMockConnection) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}

// WriteRead records a raw write and returns n zero bytes.
func (m *lis3dhMockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	_ = m.Write(data)
	return make([]byte, n), nil
}

// ReadReg reads length bytes starting at reg. Bit 7 of reg is the I²C
// auto-increment bit and is stripped before the register address is used.
func (m *lis3dhMockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.regReads = append(m.regReads, [2]uint32{reg, uint32(length)})
	base := byte(reg & 0x7F)
	out := make([]byte, length)
	for i := 0; i < length; i++ {
		out[i] = m.registers[base+byte(i)]
	}
	// While the self-test bits are set the chip adds a fixed offset to the
	// acceleration output.
	if m.registers[lis3dhRegCtrlReg4]&0x06 != 0 && m.selfTestDelta != 0 {
		for i := 0; i+1 < length; i += 2 {
			r := base + byte(i)
			if r < lis3dhRegOutXL || r > lis3dhRegOutZH-1 {
				continue
			}
			v := uint16(out[i]) | uint16(out[i+1])<<8
			v += uint16(m.selfTestDelta) << 6
			out[i] = byte(v)
			out[i+1] = byte(v >> 8)
		}
	}
	return out, nil
}

// WriteReg writes data to reg.
func (m *lis3dhMockConnection) WriteReg(reg uint32, data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, lis3dhWrite{reg: byte(reg), data: append([]byte(nil), data...)})
	for i, v := range data {
		m.registers[byte(reg)+byte(i)] = v
	}
	return nil
}

// Close does nothing.
func (m *lis3dhMockConnection) Close() error { return nil }

// Enable does nothing.
func (m *lis3dhMockConnection) Enable() {}

// Disable does nothing.
func (m *lis3dhMockConnection) Disable() {}

// IsEnabled always returns true.
func (m *lis3dhMockConnection) IsEnabled() bool { return true }

// IntPin returns the fake INT pin, or nil when none is configured.
func (m *lis3dhMockConnection) IntPin() connection.InputPin {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.intPin == nil {
		return nil
	}
	return m.intPin
}

// EnPin returns nil — the LIS3DH has no enable line.
func (m *lis3dhMockConnection) EnPin() connection.OutputPin { return nil }

// lastWrite returns the last value written to reg.
func (m *lis3dhMockConnection) lastWrite(reg byte) (byte, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg && len(m.writes[i].data) > 0 {
			return m.writes[i].data[0], true
		}
	}
	return 0, false
}

// writeLog returns a copy of the write log.
func (m *lis3dhMockConnection) writeLog() []lis3dhWrite {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]lis3dhWrite, len(m.writes))
	copy(out, m.writes)
	return out
}

// readLog returns a copy of the register-read log.
func (m *lis3dhMockConnection) readLog() [][2]uint32 {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([][2]uint32, len(m.regReads))
	copy(out, m.regReads)
	return out
}

// newLIS3DHConnection returns a mock connection holding a plausible chip
// state: the correct identity, ±2 g / 100 Hz / high-resolution and a
// known acceleration.
func newLIS3DHConnection() *lis3dhMockConnection {
	m := newLIS3DHMockConnection()
	m.setRegister(lis3dhRegWhoAmI, 0x33)
	// X = 0x1000 -> 256 counts, Y = 0xF000 -> -256 counts, Z = 0x0800 -> 128
	// counts in the 12-bit high-resolution mode.
	m.setRegister(lis3dhRegOutXL, 0x00, 0x10)
	m.setRegister(lis3dhRegOutYL, 0x00, 0xF0)
	m.setRegister(lis3dhRegOutZL, 0x00, 0x08)
	return m
}

// abs64 returns the absolute value of v.
func abs64(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

func TestLIS3DHMinimalInitSequence(t *testing.T) {
	conn := newLIS3DHConnection()
	if _, err := NewLIS3DHMinimal(conn, 0x18); err != nil {
		t.Fatalf("NewLIS3DHMinimal: %v", err)
	}
	reads := conn.readLog()
	if len(reads) == 0 || reads[0] != [2]uint32{0x0F, 1} {
		t.Errorf("first read = %v, want WHO_AM_I (0x0F, 1)", reads)
	}
	want := []lis3dhWrite{
		{lis3dhRegCtrlReg2, []byte{0x00}},
		{lis3dhRegCtrlReg3, []byte{0x00}},
		{lis3dhRegCtrlReg5, []byte{0x00}},
		{lis3dhRegCtrlReg6, []byte{0x00}},
		{lis3dhRegFifoCtrl, []byte{0x00}},
		{lis3dhRegTempCfg, []byte{0x00}},
		{lis3dhRegCtrlReg4, []byte{0x88}},
		{lis3dhRegCtrlReg1, []byte{0x57}},
	}
	log := conn.writeLog()
	if len(log) != len(want) {
		t.Fatalf("init wrote %d registers, want %d: %v", len(log), len(want), log)
	}
	for i, w := range want {
		if log[i].reg != w.reg || log[i].data[0] != w.data[0] {
			t.Errorf("init write %d = (0x%02X, 0x%02X), want (0x%02X, 0x%02X)",
				i, log[i].reg, log[i].data[0], w.reg, w.data[0])
		}
	}
}

func TestLIS3DHMinimalBadWhoAmI(t *testing.T) {
	conn := newLIS3DHMockConnection()
	conn.setRegister(lis3dhRegWhoAmI, 0xFF)
	if _, err := NewLIS3DHMinimal(conn, 0x18); err == nil {
		t.Error("NewLIS3DHMinimal with a bad WHO_AM_I: expected an error, got nil")
	}
}

func TestLIS3DHMinimalReadScaling(t *testing.T) {
	conn := newLIS3DHConnection()
	chip, err := NewLIS3DHMinimal(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHMinimal: %v", err)
	}
	x, y, z, err := chip.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	// ±2 g high-resolution: 1 mg/LSB, 12-bit left-justified.
	if abs64(x-0.256) > 1e-9 || abs64(y+0.256) > 1e-9 || abs64(z-0.128) > 1e-9 {
		t.Errorf("Read() = (%v, %v, %v), want (0.256, -0.256, 0.128)", x, y, z)
	}
	// The 6-byte burst must set the auto-increment bit of the sub-address.
	found := false
	for _, r := range conn.readLog() {
		if r == [2]uint32{0xA8, 6} {
			found = true
		}
	}
	if !found {
		t.Errorf("read log = %v, want a 6-byte burst from 0xA8", conn.readLog())
	}
}

func TestLIS3DHFullRangeAndSensitivity(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange(4): %v", err)
	}
	// (0x88 &^ 0x30) | 0x10 = 0x98
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x98 {
		t.Errorf("CTRL_REG4 after SetRange(4) = 0x%02X, want 0x98", v)
	}
	// ±4 g high-resolution: 2 mg/LSB -> 256 counts is 0.512 g.
	x, _, _, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs64(x-0.512) > 1e-9 {
		t.Errorf("Read() x = %v after SetRange(4), want 0.512", x)
	}
	if err := full.SetRange(16); err != nil {
		t.Fatalf("SetRange(16): %v", err)
	}
	// (0x98 &^ 0x30) | 0x30 = 0xB8
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0xB8 {
		t.Errorf("CTRL_REG4 after SetRange(16) = 0x%02X, want 0xB8", v)
	}
	if err := full.SetRange(3); err == nil {
		t.Error("SetRange(3): expected an error, got nil")
	}
}

func TestLIS3DHFullDataRate(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetDataRate(50); err != nil {
		t.Fatalf("SetDataRate(50): %v", err)
	}
	// (0x57 & 0x0F) | 0x40 = 0x47
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x47 {
		t.Errorf("CTRL_REG1 after SetDataRate(50) = 0x%02X, want 0x47", v)
	}
	// 1620 Hz is low-power mode only.
	if err := full.SetDataRate(1620); err == nil {
		t.Error("SetDataRate(1620) in high-resolution mode: expected an error, got nil")
	}
	if err := full.SetDataRate(999); err == nil {
		t.Error("SetDataRate(999): expected an error, got nil")
	}
	if err := full.SetDataRate(0); err != nil {
		t.Fatalf("SetDataRate(0): %v", err)
	}
	// (0x47 & 0x0F) | 0x00 = 0x07
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x07 {
		t.Errorf("CTRL_REG1 after SetDataRate(0) = 0x%02X, want 0x07", v)
	}
}

func TestLIS3DHFullMode(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetMode(LIS3DHModeNormal); err != nil {
		t.Fatalf("SetMode(normal): %v", err)
	}
	// LPen is already 0 -> CTRL_REG1 stays 0x57, HR = 0 -> 0x88 &^ 0x08 = 0x80.
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x57 {
		t.Errorf("CTRL_REG1 after SetMode(normal) = 0x%02X, want 0x57", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x80 {
		t.Errorf("CTRL_REG4 after SetMode(normal) = 0x%02X, want 0x80", v)
	}
	// 10-bit normal mode: 4 mg/LSB, shift 6 -> 0x1000 >> 6 = 64 counts.
	x, _, _, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs64(x-0.256) > 1e-9 {
		t.Errorf("Read() x = %v in normal mode, want 0.256", x)
	}
	if err := full.SetMode(LIS3DHModeLowPower); err != nil {
		t.Fatalf("SetMode(low-power): %v", err)
	}
	// LPen = 1 -> 0x57 | 0x08 = 0x5F, HR stays 0.
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x5F {
		t.Errorf("CTRL_REG1 after SetMode(low-power) = 0x%02X, want 0x5F", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x80 {
		t.Errorf("CTRL_REG4 after SetMode(low-power) = 0x%02X, want 0x80", v)
	}
	// 8-bit low-power mode: 16 mg/LSB, shift 8 -> 0x1000 >> 8 = 16 counts.
	x, _, _, err = full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs64(x-0.256) > 1e-9 {
		t.Errorf("Read() x = %v in low-power mode, want 0.256", x)
	}
	if err := full.SetMode(LIS3DHModeHighResolution); err != nil {
		t.Fatalf("SetMode(high-resolution): %v", err)
	}
	// LPen = 0 -> 0x5F &^ 0x08 = 0x57, HR = 1 -> 0x80 | 0x08 = 0x88.
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x57 {
		t.Errorf("CTRL_REG1 after SetMode(high-resolution) = 0x%02X, want 0x57", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x88 {
		t.Errorf("CTRL_REG4 after SetMode(high-resolution) = 0x%02X, want 0x88", v)
	}
	if err := full.SetMode(5); err == nil {
		t.Error("SetMode(5): expected an error, got nil")
	}
	// 1344 Hz does not exist in low-power mode.
	if err := full.SetMode(LIS3DHModeLowPower); err != nil {
		t.Fatalf("SetMode(low-power): %v", err)
	}
	if err := full.SetDataRate(1344); err == nil {
		t.Error("SetDataRate(1344) in low-power mode: expected an error, got nil")
	}
}

func TestLIS3DHFullAxes(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetAxes(true, false, true); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	// (0x57 & 0xF8) | 0x05 = 0x55
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x55 {
		t.Errorf("CTRL_REG1 after SetAxes(true,false,true) = 0x%02X, want 0x55", v)
	}
	if err := full.SetAxes(false, false, false); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	// (0x55 & 0xF8) | 0x00 = 0x50
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x50 {
		t.Errorf("CTRL_REG1 after SetAxes(false,false,false) = 0x%02X, want 0x50", v)
	}
}

func TestLIS3DHFullPowerDown(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.PowerDown(); err != nil {
		t.Fatalf("PowerDown: %v", err)
	}
	// 0x57 & 0x0F = 0x07
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x07 {
		t.Errorf("CTRL_REG1 after PowerDown = 0x%02X, want 0x07", v)
	}
}

func TestLIS3DHFullReadRaw(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	rx, ry, rz, err := full.ReadRaw()
	if err != nil {
		t.Fatalf("ReadRaw: %v", err)
	}
	if rx != 256 || ry != -256 || rz != 128 {
		t.Errorf("ReadRaw() = (%d, %d, %d), want (256, -256, 128)", rx, ry, rz)
	}
}

func TestLIS3DHFullStatus(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	conn.setRegister(lis3dhRegStatus, 0x08)
	ready, err := full.NewDataAvailable()
	if err != nil {
		t.Fatalf("NewDataAvailable: %v", err)
	}
	if !ready {
		t.Error("NewDataAvailable() = false, want true (STATUS_REG.ZYXDA)")
	}
	status, err := full.ReadStatus()
	if err != nil {
		t.Fatalf("ReadStatus: %v", err)
	}
	if status != 0x08 {
		t.Errorf("ReadStatus() = 0x%02X, want 0x08", status)
	}
	conn.setRegister(lis3dhRegStatus, 0x00)
	ready, err = full.NewDataAvailable()
	if err != nil {
		t.Fatalf("NewDataAvailable: %v", err)
	}
	if ready {
		t.Error("NewDataAvailable() = true, want false")
	}
}

func TestLIS3DHFullTemperature(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	conn.setRegister(lis3dhRegOutADC3H, 0xF6) // -10
	temp, err := full.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if temp != -10 {
		t.Errorf("ReadTemperatureDelta() = %d, want -10", temp)
	}
	// ADC_EN and TEMP_EN must have been set.
	if v, _ := conn.lastWrite(lis3dhRegTempCfg); v != 0xC0 {
		t.Errorf("TEMP_CFG after ReadTemperatureDelta = 0x%02X, want 0xC0", v)
	}
	conn.setRegister(lis3dhRegOutADC3H, 0x1E) // 30
	temp, err = full.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if temp != 30 {
		t.Errorf("ReadTemperatureDelta() = %d, want 30", temp)
	}
}

func TestLIS3DHFullADC(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	conn.setRegister(lis3dhRegOutADC1L, 0x00, 0x40) // 0x4000 >> 6 = 256 counts
	v, err := full.ReadADC(1)
	if err != nil {
		t.Fatalf("ReadADC(1): %v", err)
	}
	if math.Abs(v-1.4) > 1e-9 {
		t.Errorf("ReadADC(1) = %v, want 1.4", v)
	}
	// The 2-byte ADC read must set the auto-increment bit.
	found := false
	for _, r := range conn.readLog() {
		if r == [2]uint32{0x88, 2} {
			found = true
		}
	}
	if !found {
		t.Errorf("read log = %v, want a 2-byte burst from 0x88", conn.readLog())
	}
	if err := full.EnableADC(true); err != nil {
		t.Fatalf("EnableADC: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegTempCfg); v&0x80 == 0 {
		t.Errorf("TEMP_CFG after EnableADC(true) = 0x%02X, want ADC_EN set", v)
	}
	if err := full.EnableADC(false); err != nil {
		t.Fatalf("EnableADC: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegTempCfg); v&0x80 != 0 {
		t.Errorf("TEMP_CFG after EnableADC(false) = 0x%02X, want ADC_EN cleared", v)
	}
	if _, err := full.ReadADC(4); err == nil {
		t.Error("ReadADC(4): expected an error, got nil")
	}
	// Channel 3 is the temperature sensor while TEMP_EN = 1.
	conn.setRegister(lis3dhRegTempCfg, 0x40)
	if _, err := full.ReadADC(3); err == nil {
		t.Error("ReadADC(3) with TEMP_EN set: expected an error, got nil")
	}
}

func TestLIS3DHFullHighPassAndReference(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetHighPass(3, 2, true, true, false, true); err != nil {
		t.Fatalf("SetHighPass: %v", err)
	}
	// HPM=3 (0xC0) | HPCF=2 (0x20) | FDS (0x08) | HPCLICK (0x04) | HP_IA1 (0x01)
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg2); v != 0xED {
		t.Errorf("CTRL_REG2 after SetHighPass = 0x%02X, want 0xED", v)
	}
	if err := full.SetHighPass(4, 0, false, false, false, false); err == nil {
		t.Error("SetHighPass with mode 4: expected an error, got nil")
	}
	if err := full.SetHighPass(0, 9, false, false, false, false); err == nil {
		t.Error("SetHighPass with cutoff 9: expected an error, got nil")
	}
	conn.setRegister(lis3dhRegReference, 0x37)
	ref, err := full.ResetHighPass()
	if err != nil {
		t.Fatalf("ResetHighPass: %v", err)
	}
	if ref != 0x37 {
		t.Errorf("ResetHighPass() = 0x%02X, want 0x37", ref)
	}
	if err := full.SetReference(0x42); err != nil {
		t.Fatalf("SetReference: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegReference); v != 0x42 {
		t.Errorf("REFERENCE after SetReference = 0x%02X, want 0x42", v)
	}
	if err := full.SetReference(256); err == nil {
		t.Error("SetReference(256): expected an error, got nil")
	}
}

func TestLIS3DHFullFIFO(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetFIFO(2, 16, false); err != nil {
		t.Fatalf("SetFIFO: %v", err)
	}
	// FM=10 (0x80) | FTH=16 (0x10) = 0x90
	if v, _ := conn.lastWrite(lis3dhRegFifoCtrl); v != 0x90 {
		t.Errorf("FIFO_CTRL after SetFIFO(2,16,false) = 0x%02X, want 0x90", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x40 == 0 {
		t.Errorf("CTRL_REG5 after SetFIFO = 0x%02X, want FIFO_EN set", v)
	}
	if err := full.SetFIFO(0, 0, false); err != nil {
		t.Fatalf("SetFIFO: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegFifoCtrl); v != 0x00 {
		t.Errorf("FIFO_CTRL after SetFIFO(0,0,false) = 0x%02X, want 0x00", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x40 != 0 {
		t.Errorf("CTRL_REG5 after SetFIFO(0) = 0x%02X, want FIFO_EN cleared", v)
	}
	if err := full.SetFIFO(2, 16, true); err != nil {
		t.Fatalf("SetFIFO: %v", err)
	}
	// FM=10 (0x80) | TR (0x20) | FTH=16 (0x10) = 0xB0
	if v, _ := conn.lastWrite(lis3dhRegFifoCtrl); v != 0xB0 {
		t.Errorf("FIFO_CTRL after SetFIFO(2,16,true) = 0x%02X, want 0xB0", v)
	}
	if err := full.SetFIFO(4, 0, false); err == nil {
		t.Error("SetFIFO with mode 4: expected an error, got nil")
	}
	if err := full.SetFIFO(1, 32, false); err == nil {
		t.Error("SetFIFO with watermark 32: expected an error, got nil")
	}

	conn.setRegister(lis3dhRegFifoSrc, 0x05)
	level, err := full.FIFOLevel()
	if err != nil {
		t.Fatalf("FIFOLevel: %v", err)
	}
	if level != 5 {
		t.Errorf("FIFOLevel() = %d, want 5", level)
	}
	conn.setRegister(lis3dhRegFifoSrc, 0xE5)
	wm, ovrn, empty, lvl, err := full.ReadFIFOStatus()
	if err != nil {
		t.Fatalf("ReadFIFOStatus: %v", err)
	}
	if !wm || !ovrn || !empty || lvl != 5 {
		t.Errorf("ReadFIFOStatus() = (%v, %v, %v, %d), want (true, true, true, 5)", wm, ovrn, empty, lvl)
	}

	conn.setRegister(lis3dhRegFifoSrc, 0x05)
	samples, err := full.ReadFIFO(0)
	if err != nil {
		t.Fatalf("ReadFIFO: %v", err)
	}
	if len(samples) != 5 {
		t.Fatalf("ReadFIFO(0) returned %d samples, want 5", len(samples))
	}
	if abs64(samples[0].X-0.256) > 1e-9 || abs64(samples[0].Y+0.256) > 1e-9 || abs64(samples[0].Z-0.128) > 1e-9 {
		t.Errorf("ReadFIFO(0)[0] = %+v, want {0.256 -0.256 0.128}", samples[0])
	}
	samples, err = full.ReadFIFO(2)
	if err != nil {
		t.Fatalf("ReadFIFO: %v", err)
	}
	if len(samples) != 2 {
		t.Errorf("ReadFIFO(2) returned %d samples, want 2", len(samples))
	}
}

func TestLIS3DHFullGenerators(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetMotionInterrupt(1, 0x3F, 0.5, 10, true); err != nil {
		t.Fatalf("SetMotionInterrupt: %v", err)
	}
	// AOI (0x80) | events (0x3F) = 0xBF
	if v, _ := conn.lastWrite(lis3dhRegInt1Cfg); v != 0xBF {
		t.Errorf("INT1_CFG after SetMotionInterrupt = 0x%02X, want 0xBF", v)
	}
	// round(0.5 * 1000 / 16) = 31
	if v, _ := conn.lastWrite(lis3dhRegInt1Ths); v != 31 {
		t.Errorf("INT1_THS after SetMotionInterrupt = %d, want 31", v)
	}
	// round(10 * 100 / 1000) = 1
	if v, _ := conn.lastWrite(lis3dhRegInt1Dur); v != 1 {
		t.Errorf("INT1_DURATION after SetMotionInterrupt = %d, want 1", v)
	}
	if err := full.SetMotionInterrupt(2, 0x01, 0.2, 0, false); err != nil {
		t.Fatalf("SetMotionInterrupt: %v", err)
	}
	// events (0x01) without AOI = 0x01
	if v, _ := conn.lastWrite(lis3dhRegInt2Cfg); v != 0x01 {
		t.Errorf("INT2_CFG after SetMotionInterrupt = 0x%02X, want 0x01", v)
	}
	// round(0.2 * 1000 / 16) = 13 (12.5 rounds away from zero)
	if v, _ := conn.lastWrite(lis3dhRegInt2Ths); v != 13 {
		t.Errorf("INT2_THS after SetMotionInterrupt = %d, want 13", v)
	}
	if err := full.SetMotionInterrupt(3, 0x01, 0.2, 0, false); err == nil {
		t.Error("SetMotionInterrupt with generator 3: expected an error, got nil")
	}
	if err := full.SetMotionInterrupt(1, 0x40, 0.2, 0, false); err == nil {
		t.Error("SetMotionInterrupt with a 7-bit mask: expected an error, got nil")
	}
}

func TestLIS3DHFullFreeFall(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetFreeFall(1, 0.35, 60); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegInt1Cfg); v != 0x95 {
		t.Errorf("INT1_CFG after SetFreeFall = 0x%02X, want 0x95", v)
	}
	// round(0.35 * 1000 / 16) = 22
	if v, _ := conn.lastWrite(lis3dhRegInt1Ths); v != 22 {
		t.Errorf("INT1_THS after SetFreeFall = %d, want 22", v)
	}
	// round(60 * 100 / 1000) = 6
	if v, _ := conn.lastWrite(lis3dhRegInt1Dur); v != 6 {
		t.Errorf("INT1_DURATION after SetFreeFall = %d, want 6", v)
	}
	if err := full.SetFreeFall(2, 0.35, 30); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegInt2Cfg); v != 0x95 {
		t.Errorf("INT2_CFG after SetFreeFall = 0x%02X, want 0x95", v)
	}
	if err := full.SetFreeFall(3, 0.35, 30); err == nil {
		t.Error("SetFreeFall with generator 3: expected an error, got nil")
	}
}

func TestLIS3DHFullOrientation(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetOrientation(1, false, false); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	// 6D movement recognition: 0x40 | 0x3F = 0x7F
	if v, _ := conn.lastWrite(lis3dhRegInt1Cfg); v != 0x7F {
		t.Errorf("INT1_CFG after SetOrientation = 0x%02X, want 0x7F", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x04 != 0 {
		t.Errorf("CTRL_REG5 after SetOrientation = 0x%02X, want D4D_INT1 cleared", v)
	}
	if err := full.SetOrientation(2, true, true); err != nil {
		t.Fatalf("SetOrientation: %v", err)
	}
	// 6D position recognition with 4D: 0xC0 | 0x0F = 0xCF
	if v, _ := conn.lastWrite(lis3dhRegInt2Cfg); v != 0xCF {
		t.Errorf("INT2_CFG after SetOrientation = 0x%02X, want 0xCF", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x01 == 0 {
		t.Errorf("CTRL_REG5 after SetOrientation = 0x%02X, want D4D_INT2 set", v)
	}
}

func TestLIS3DHFullLatch(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetLatch(1, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x08 == 0 {
		t.Errorf("CTRL_REG5 after SetLatch(1,true) = 0x%02X, want LIR_INT1 set", v)
	}
	if err := full.SetLatch(2, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x02 == 0 {
		t.Errorf("CTRL_REG5 after SetLatch(2,true) = 0x%02X, want LIR_INT2 set", v)
	}
	if err := full.SetLatch(1, false); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg5); v&0x08 != 0 {
		t.Errorf("CTRL_REG5 after SetLatch(1,false) = 0x%02X, want LIR_INT1 cleared", v)
	}
	if err := full.SetLatch(3, true); err == nil {
		t.Error("SetLatch with generator 3: expected an error, got nil")
	}
}

func TestLIS3DHFullInterruptSource(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	conn.setRegister(lis3dhRegInt1Src, 0x60)
	src, err := full.ReadInterruptSource(1)
	if err != nil {
		t.Fatalf("ReadInterruptSource: %v", err)
	}
	if src != 0x60 {
		t.Errorf("ReadInterruptSource(1) = 0x%02X, want 0x60", src)
	}
	conn.setRegister(lis3dhRegInt2Src, 0x40)
	src, err = full.ReadInterruptSource(2)
	if err != nil {
		t.Fatalf("ReadInterruptSource: %v", err)
	}
	if src != 0x40 {
		t.Errorf("ReadInterruptSource(2) = 0x%02X, want 0x40", src)
	}
	if _, err := full.ReadInterruptSource(0); err == nil {
		t.Error("ReadInterruptSource(0): expected an error, got nil")
	}
}

func TestLIS3DHFullClick(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetClick(0, 0x04, 0.8, 20, 40, 200, true); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	// double Z (0x04) -> ZD (0x04 << 3) = 0x20
	if v, _ := conn.lastWrite(lis3dhRegClickCfg); v != 0x20 {
		t.Errorf("CLICK_CFG after SetClick = 0x%02X, want 0x20", v)
	}
	// round(0.8 * 1000 / 16) = 50, latched -> 0x80 | 0x32 = 0xB2
	if v, _ := conn.lastWrite(lis3dhRegClickThs); v != 0xB2 {
		t.Errorf("CLICK_THS after SetClick = 0x%02X, want 0xB2", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegTimeLimit); v != 2 {
		t.Errorf("TIME_LIMIT after SetClick = %d, want 2", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegTimeLatency); v != 4 {
		t.Errorf("TIME_LATENCY after SetClick = %d, want 4", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegTimeWindow); v != 20 {
		t.Errorf("TIME_WINDOW after SetClick = %d, want 20", v)
	}
	if err := full.SetClick(0, 0, 0.5, 10, 20, 50, false); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	// round(0.5 * 1000 / 16) = 31
	if v, _ := conn.lastWrite(lis3dhRegClickThs); v != 31 {
		t.Errorf("CLICK_THS after SetClick = %d, want 31", v)
	}
	if err := full.SetClick(8, 0, 0.5, 10, 20, 50, false); err == nil {
		t.Error("SetClick with an 8-bit axis mask: expected an error, got nil")
	}
	conn.setRegister(lis3dhRegClickSrc, 0x50)
	src, err := full.ReadClickSource()
	if err != nil {
		t.Fatalf("ReadClickSource: %v", err)
	}
	if src != 0x50 {
		t.Errorf("ReadClickSource() = 0x%02X, want 0x50", src)
	}
}

func TestLIS3DHFullActivity(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetActivity(0.1, 100); err != nil {
		t.Fatalf("SetActivity: %v", err)
	}
	// round(0.1 * 1000 / 16) = 6
	if v, _ := conn.lastWrite(lis3dhRegActThs); v != 6 {
		t.Errorf("ACT_THS after SetActivity = %d, want 6", v)
	}
	// round((100 * 100 / 1000 - 1) / 8) = round(1.125) = 1
	if v, _ := conn.lastWrite(lis3dhRegActDur); v != 1 {
		t.Errorf("ACT_DUR after SetActivity = %d, want 1", v)
	}
	if err := full.SetActivity(0.001, 100); err == nil {
		t.Error("SetActivity with a sub-LSB threshold: expected an error, got nil")
	}
}

func TestLIS3DHFullPolarity(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetInterruptPolarity(true); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg6); v&0x02 == 0 {
		t.Errorf("CTRL_REG6 after SetInterruptPolarity(true) = 0x%02X, want INT_POLARITY set", v)
	}
	if err := full.SetInterruptPolarity(false); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg6); v&0x02 != 0 {
		t.Errorf("CTRL_REG6 after SetInterruptPolarity(false) = 0x%02X, want INT_POLARITY cleared", v)
	}
}

func TestLIS3DHFullInterruptRouting(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.EnableInterrupt(SourceClick, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg3); v&0x80 == 0 {
		t.Errorf("CTRL_REG3 after EnableInterrupt(SourceClick,1) = 0x%02X, want I1_CLICK set", v)
	}
	if err := full.DisableInterrupt(SourceClick, 1); err != nil {
		t.Fatalf("DisableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg3); v&0x80 != 0 {
		t.Errorf("CTRL_REG3 after DisableInterrupt(SourceClick,1) = 0x%02X, want I1_CLICK cleared", v)
	}
	// A source that exists on both pins follows the requested pin.
	if err := full.EnableInterrupt(SourceIA1, 2); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg6); v&0x40 == 0 {
		t.Errorf("CTRL_REG6 after EnableInterrupt(SourceIA1,2) = 0x%02X, want I2_IA1 set", v)
	}
	// The INT2-only sources ignore the pin.
	if err := full.EnableInterrupt(SourceActivity, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg6); v&0x08 == 0 {
		t.Errorf("CTRL_REG6 after EnableInterrupt(SourceActivity,1) = 0x%02X, want I2_ACT set", v)
	}
	if err := full.EnableInterrupt(SourceDataReady, 2); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg3); v&0x10 == 0 {
		t.Errorf("CTRL_REG3 after EnableInterrupt(SourceDataReady,2) = 0x%02X, want I1_ZYXDA set", v)
	}
	if err := full.EnableInterrupt(SourceBoot, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg6); v&0x10 == 0 {
		t.Errorf("CTRL_REG6 after EnableInterrupt(SourceBoot,1) = 0x%02X, want I2_BOOT set", v)
	}
	if err := full.EnableInterrupt(SourceClick, 3); err == nil {
		t.Error("EnableInterrupt with pin 3: expected an error, got nil")
	}
	if err := full.EnableInterrupt(0x8000, 1); err == nil {
		t.Error("EnableInterrupt with an unknown source: expected an error, got nil")
	}
}

func TestLIS3DHFullPollInterrupt(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.EnableInterrupt(SourceDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if err := full.EnableInterrupt(SourceIA1, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if err := full.EnableInterrupt(SourceClick, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if err := full.EnableInterrupt(SourceWatermark, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if err := full.EnableInterrupt(SourceOverrun, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	if err := full.EnableInterrupt(SourceAuxDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}

	// No source is asserted yet.
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != 0 {
		t.Errorf("PollInterrupt() = 0x%04X, want 0x0000", status)
	}

	conn.setRegister(lis3dhRegStatus, 0x08)
	conn.setRegister(lis3dhRegStatusAux, 0x08)
	conn.setRegister(lis3dhRegInt1Src, 0x40)
	conn.setRegister(lis3dhRegClickSrc, 0x40)
	conn.setRegister(lis3dhRegFifoSrc, 0xC0)
	status, err = full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	want := SourceDataReady | SourceAuxDataReady | SourceWatermark | SourceOverrun | SourceIA1 | SourceClick
	if status != want {
		t.Errorf("PollInterrupt() = 0x%04X, want 0x%04X", status, want)
	}

	// A source that is not enabled is never reported.
	conn.setRegister(lis3dhRegInt2Src, 0x40)
	status, err = full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status&SourceIA2 != 0 {
		t.Errorf("PollInterrupt() = 0x%04X, want no SourceIA2", status)
	}
}

func TestLIS3DHFullOnOffInterrupt(t *testing.T) {
	conn := newLIS3DHConnection()
	pin := &lis3dhFakePin{}
	conn.intPin = pin
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.EnableInterrupt(SourceIA1, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	conn.setRegister(lis3dhRegInt1Src, 0x40)

	got := make(chan uint16, 4)
	if err := full.OnInterrupt(func(status uint16) { got <- status }); err != nil {
		t.Fatalf("OnInterrupt: %v", err)
	}
	if len(pin.handlers) != 1 {
		t.Fatalf("registered %d handlers, want 1", len(pin.handlers))
	}
	if pin.triggers[0] != connection.Falling {
		t.Errorf("trigger = %v, want Falling", pin.triggers[0])
	}
	pin.fire()
	select {
	case status := <-got:
		if status != SourceIA1 {
			t.Errorf("callback status = 0x%04X, want 0x%04X", status, SourceIA1)
		}
	default:
		t.Error("callback was not invoked")
	}
	if err := full.OffInterrupt(); err != nil {
		t.Fatalf("OffInterrupt: %v", err)
	}
	if pin.unsubs != 1 {
		t.Errorf("unsubscribe called %d times, want 1", pin.unsubs)
	}
}

func TestLIS3DHFullOnInterruptWithoutPint(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	// No INT pin is wired: the driver falls back to a 5 ms polling pin.
	if err := full.OnInterrupt(func(status uint16) {}); err != nil {
		t.Fatalf("OnInterrupt: %v", err)
	}
	if err := full.OffInterrupt(); err != nil {
		t.Fatalf("OffInterrupt: %v", err)
	}
}

func TestLIS3DHFullSDOPullup(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetSDOPullup(true); err != nil {
		t.Fatalf("SetSDOPullup: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg0); v != 0x10 {
		t.Errorf("CTRL_REG0 after SetSDOPullup(true) = 0x%02X, want 0x10", v)
	}
	if err := full.SetSDOPullup(false); err != nil {
		t.Fatalf("SetSDOPullup: %v", err)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg0); v != 0x90 {
		t.Errorf("CTRL_REG0 after SetSDOPullup(false) = 0x%02X, want 0x90", v)
	}
}

func TestLIS3DHFullReboot(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	if err := full.SetRange(8); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	if err := full.Reboot(); err != nil {
		t.Fatalf("Reboot: %v", err)
	}
	// BOOT = 1 must have been set in CTRL_REG5 ...
	log := conn.writeLog()
	boot := false
	for _, w := range log {
		if w.reg == lis3dhRegCtrlReg5 && w.data[0]&0x80 != 0 {
			boot = true
		}
	}
	if !boot {
		t.Errorf("no CTRL_REG5 write with BOOT set after Reboot: %v", log)
	}
	// ... and the initialization sequence must have run again.
	last := log[len(log)-1]
	if last.reg != lis3dhRegCtrlReg1 || last.data[0] != 0x57 {
		t.Errorf("last write after Reboot = (0x%02X, 0x%02X), want (0x20, 0x57)", last.reg, last.data[0])
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x88 {
		t.Errorf("CTRL_REG4 after Reboot = 0x%02X, want 0x88", v)
	}
	// The cached range must be back to the ±2 g default.
	x, _, _, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs64(x-0.256) > 1e-9 {
		t.Errorf("Read() x = %v after Reboot, want 0.256 (±2 g default)", x)
	}
}

func TestLIS3DHFullSelfTest(t *testing.T) {
	conn := newLIS3DHConnection()
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	ok, err := full.SelfTest(1)
	if err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if !ok {
		t.Error("SelfTest(1) = false, want true")
	}
	// The previous configuration must have been restored.
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg1); v != 0x57 {
		t.Errorf("CTRL_REG1 after SelfTest = 0x%02X, want 0x57", v)
	}
	if v, _ := conn.lastWrite(lis3dhRegCtrlReg4); v != 0x88 {
		t.Errorf("CTRL_REG4 after SelfTest = 0x%02X, want 0x88", v)
	}
	if _, err := full.SelfTest(3); err == nil {
		t.Error("SelfTest(3): expected an error, got nil")
	}
}

func TestLIS3DHFullSelfTestFailure(t *testing.T) {
	conn := newLIS3DHConnection()
	// A chip that does not react to the self-test must be reported as a
	// failure.
	conn.selfTestDelta = 0
	full, err := NewLIS3DHFull(conn, 0x18)
	if err != nil {
		t.Fatalf("NewLIS3DHFull: %v", err)
	}
	ok, err := full.SelfTest(1)
	if err != nil {
		t.Fatalf("SelfTest: %v", err)
	}
	if ok {
		t.Error("SelfTest(1) = true on a chip without a self-test response, want false")
	}
}
