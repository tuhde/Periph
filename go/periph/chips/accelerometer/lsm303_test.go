package accelerometer

import (
	"math"
	"sync"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// lsm303Write is one recorded register write.
type lsm303Write struct {
	reg  uint32
	data []byte
}

// lsm303Read is one recorded register read.
type lsm303Read struct {
	reg uint32
	n   int
}

// lsm303Chip is an in-memory fake of one LSM303DLHC slave. The accelerometer
// (mag = false) honours the SUB bit 7 auto-increment flag; the magnetometer
// (mag = true) uses the address pointer rules from the spec (roll back to 0x03
// after 0x08, wrap to 0x00 from 0x0C upward).
type lsm303Chip struct {
	mu     sync.Mutex
	mag    bool
	regs   map[uint32]uint8
	writes []lsm303Write
	reads  []lsm303Read
}

func newLSM303Chip(mag bool) *lsm303Chip {
	return &lsm303Chip{mag: mag, regs: map[uint32]uint8{}}
}

func (m *lsm303Chip) next(p uint32) uint32 {
	switch {
	case p == 0x08:
		return 0x03
	case p >= 0x0C:
		return 0x00
	}
	return p + 1
}

func (m *lsm303Chip) ReadReg(reg uint32, length int) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.reads = append(m.reads, lsm303Read{reg, length})
	out := make([]byte, length)
	if m.mag {
		p := reg
		for i := 0; i < length; i++ {
			out[i] = m.regs[p]
			p = m.next(p)
		}
		return out, nil
	}
	flagged := reg&lsm303AutoIncrement != 0
	base := reg &^ lsm303AutoIncrement
	for i := 0; i < length; i++ {
		addr := base
		if flagged {
			addr = base + uint32(i)
		}
		out[i] = m.regs[addr]
	}
	return out, nil
}

func (m *lsm303Chip) WriteReg(reg uint32, data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes = append(m.writes, lsm303Write{reg, append([]byte(nil), data...)})
	for i, v := range data {
		m.regs[reg+uint32(i)] = v
	}
	return nil
}

func (m *lsm303Chip) Write(data []byte) error { return nil }
func (m *lsm303Chip) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}
func (m *lsm303Chip) WriteRead(data []byte, n int) ([]byte, error) {
	return make([]byte, n), nil
}
func (m *lsm303Chip) Close() error                { return nil }
func (m *lsm303Chip) Enable()                     {}
func (m *lsm303Chip) Disable()                    {}
func (m *lsm303Chip) IsEnabled() bool             { return true }
func (m *lsm303Chip) IntPin() connection.InputPin { return nil }
func (m *lsm303Chip) EnPin() connection.OutputPin { return nil }

// lastWrite returns the last value written to reg, if any.
func (m *lsm303Chip) lastWrite(reg uint32) (uint8, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := len(m.writes) - 1; i >= 0; i-- {
		if m.writes[i].reg == reg {
			return m.writes[i].data[0], true
		}
	}
	return 0, false
}

// countWrites returns how many times reg was written.
func (m *lsm303Chip) countWrites(reg uint32) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, w := range m.writes {
		if w.reg == reg {
			n++
		}
	}
	return n
}

// didRead reports whether a read of reg with n bytes occurred.
func (m *lsm303Chip) didRead(reg uint32, n int) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, r := range m.reads {
		if r.reg == reg && r.n == n {
			return true
		}
	}
	return false
}

// setRegs sets consecutive register values starting at reg.
func (m *lsm303Chip) setRegs(reg uint32, values ...uint8) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, v := range values {
		m.regs[reg+uint32(i)] = v
	}
}

// newLSM303Pair returns a fresh accelerometer and magnetometer fake with the
// magnetometer identity present.
func newLSM303Pair() (*lsm303Chip, *lsm303Chip) {
	accel := newLSM303Chip(false)
	mag := newLSM303Chip(true)
	mag.setRegs(0x0A, 0x48, 0x34, 0x33)
	return accel, mag
}

func lsm303Close(a, b float32, tol float64) bool {
	return math.Abs(float64(a)-float64(b)) <= tol
}

func TestLSM303MinimalInitWrites(t *testing.T) {
	accel, mag := newLSM303Pair()
	if _, err := NewLSM303Minimal(accel, mag); err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	if !mag.didRead(0x0A, 3) {
		t.Error("identity was not read as 3 bytes from 0x0A")
	}
	want := map[uint32]uint8{
		0x21: 0x00, 0x22: 0x00, 0x24: 0x00, 0x25: 0x00, 0x2E: 0x00,
		0x23: 0x88, 0x20: 0x57,
	}
	for reg, v := range want {
		if got, ok := accel.lastWrite(reg); !ok || got != v {
			t.Errorf("accel reg 0x%02X = 0x%02X (ok=%v), want 0x%02X", reg, got, ok, v)
		}
	}
	wantMag := map[uint32]uint8{0x00: 0x10, 0x01: 0x20, 0x02: 0x00}
	for reg, v := range wantMag {
		if got, ok := mag.lastWrite(reg); !ok || got != v {
			t.Errorf("mag reg 0x%02X = 0x%02X (ok=%v), want 0x%02X", reg, got, ok, v)
		}
	}
}

func TestLSM303MinimalIdentityMismatch(t *testing.T) {
	accel, mag := newLSM303Pair()
	mag.setRegs(0x0A, 0x00)
	if _, err := NewLSM303Minimal(accel, mag); err == nil {
		t.Error("NewLSM303Minimal with bad magnetometer ID: expected error, got nil")
	}
	accel2, mag2 := newLSM303Pair()
	mag2.setRegs(0x0C, 0x34)
	if _, err := NewLSM303Full(accel2, mag2); err == nil {
		t.Error("NewLSM303Full with bad magnetometer ID: expected error, got nil")
	}
}

func TestLSM303AutoIncrementFlag(t *testing.T) {
	accel, mag := newLSM303Pair()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	if _, _, _, err := chip.ReadAcceleration(); err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if !accel.didRead(0xA8, 6) {
		t.Error("accelerometer burst did not use sub-address 0xA8 (0x28|0x80) with 6 bytes")
	}
	if accel.didRead(0x28, 6) {
		t.Error("accelerometer burst sent sub-address without the auto-increment bit")
	}
	if _, _, _, err := chip.ReadMagnetic(); err != nil {
		t.Fatalf("ReadMagnetic: %v", err)
	}
	if !mag.didRead(0x03, 6) {
		t.Error("magnetometer burst did not read 6 bytes from 0x03")
	}
	for _, r := range mag.reads {
		if r.reg&lsm303AutoIncrement != 0 {
			t.Errorf("magnetometer read used 0x80 flag: reg 0x%02X", r.reg)
		}
	}
}

func TestLSM303AccelerationConversionSignExtension(t *testing.T) {
	accel, mag := newLSM303Pair()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	// X raw 0x4000 >> 4 = 1024 counts -> 1.024 g at ±2 g HR (1 mg/LSB).
	// Y raw 0xFFF0 = -16 >> 4 = -1 count -> -0.001 g.
	// Z raw 0x8000 = -32768 >> 4 = -2048 counts -> -2.048 g.
	accel.setRegs(0x28, 0x00, 0x40, 0xF0, 0xFF, 0x00, 0x80)
	x, y, z, err := chip.ReadAcceleration()
	if err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if !lsm303Close(x, 1.024, 1e-5) || !lsm303Close(y, -0.001, 1e-5) || !lsm303Close(z, -2.048, 1e-5) {
		t.Errorf("ReadAcceleration = (%v, %v, %v), want (1.024, -0.001, -2.048)", x, y, z)
	}
}

func TestLSM303FullRangeSensitivity(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	accel.setRegs(0x28, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
	if err := full.SetRange(4); err != nil {
		t.Fatalf("SetRange(4): %v", err)
	}
	// ±4 g HR is 2 mg/LSB: 1024 counts -> 2.048 g.
	x, _, _, err := full.ReadAcceleration()
	if err != nil {
		t.Fatalf("ReadAcceleration: %v", err)
	}
	if !lsm303Close(x, 2.048, 1e-5) {
		t.Errorf("x at ±4 g = %v, want 2.048", x)
	}
	// Raw counts shift by the resolution (HR: >> 4).
	rx, _, _, err := full.ReadAccelerationRaw()
	if err != nil {
		t.Fatalf("ReadAccelerationRaw: %v", err)
	}
	if rx != 1024 {
		t.Errorf("ReadAccelerationRaw x = %d, want 1024", rx)
	}
}

func TestLSM303MagneticReorderAndGain(t *testing.T) {
	accel, mag := newLSM303Pair()
	chip, err := NewLSM303Minimal(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Minimal: %v", err)
	}
	// Wire order is X, Z, Y, high byte first:
	// X = 1000 (0x03E8), Z = 100 (0x0064), Y = -50 (0xFFCE).
	mag.setRegs(0x03, 0x03, 0xE8, 0x00, 0x64, 0xFF, 0xCE)
	x, y, z, err := chip.ReadMagnetic()
	if err != nil {
		t.Fatalf("ReadMagnetic: %v", err)
	}
	// ±1.3 gauss: X/Y gain 1100 LSB/gauss, Z gain 980 LSB/gauss, 1 gauss = 100 µT.
	if !lsm303Close(x, 1000.0/1100*100, 1e-3) {
		t.Errorf("x = %v µT, want %v", x, 1000.0/1100*100)
	}
	if !lsm303Close(y, -50.0/1100*100, 1e-3) {
		t.Errorf("y = %v µT, want %v", y, -50.0/1100*100)
	}
	if !lsm303Close(z, 100.0/980*100, 1e-3) {
		t.Errorf("z = %v µT, want %v", z, 100.0/980*100)
	}
}

func TestLSM303FullBitFieldMasks(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}

	// CTRL4 starts at 0x88; FS (bits 5:4) changes only: 0x88 -> 0xB8.
	if err := full.SetRange(16); err != nil {
		t.Fatalf("SetRange: %v", err)
	}
	if v, _ := accel.lastWrite(0x23); v != 0xB8 {
		t.Errorf("SetRange(16) CTRL4 = 0x%02X, want 0xB8", v)
	}

	// CTRL1 starts at 0x57; ODR (bits 7:4) = 0x7 (400 Hz): 0x57 -> 0x77.
	if err := full.SetDataRate(400); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	if v, _ := accel.lastWrite(0x20); v != 0x77 {
		t.Errorf("SetDataRate(400) CTRL1 = 0x%02X, want 0x77", v)
	}

	// Low-power: LPen (CTRL1 bit 3) set, HR (CTRL4 bit 3) cleared.
	if err := full.SetMode(0); err != nil {
		t.Fatalf("SetMode(0): %v", err)
	}
	if v, _ := accel.lastWrite(0x20); v != 0x7F {
		t.Errorf("SetMode(0) CTRL1 = 0x%02X, want 0x7F", v)
	}
	if v, _ := accel.lastWrite(0x23); v != 0xB0 {
		t.Errorf("SetMode(0) CTRL4 = 0x%02X, want 0xB0", v)
	}

	// Axes: mask 0x07 only; 0x7F -> X and Z on: (0x7F &^ 0x07) | 0x05 = 0x7D.
	if err := full.SetAxes(true, false, true); err != nil {
		t.Fatalf("SetAxes: %v", err)
	}
	if v, _ := accel.lastWrite(0x20); v != 0x7D {
		t.Errorf("SetAxes(X,Z) CTRL1 = 0x%02X, want 0x7D", v)
	}

	// Magnetometer rate keeps TEMP_EN (CRA bit 7) and DO (bits 4:2).
	if err := full.SetMagDataRate(75); err != nil {
		t.Fatalf("SetMagDataRate(75): %v", err)
	}
	if v, _ := mag.lastWrite(0x00); v != 0x18 {
		t.Errorf("SetMagDataRate(75) CRA = 0x%02X, want 0x18", v)
	}
	if err := full.EnableTemperature(true); err != nil {
		t.Fatalf("EnableTemperature: %v", err)
	}
	if v, _ := mag.lastWrite(0x00); v != 0x98 {
		t.Errorf("EnableTemperature(true) CRA = 0x%02X, want 0x98", v)
	}
	if err := full.SetMagDataRate(15); err != nil {
		t.Fatalf("SetMagDataRate(15): %v", err)
	}
	if v, _ := mag.lastWrite(0x00); v != 0x90 {
		t.Errorf("SetMagDataRate(15) with TEMP_EN CRA = 0x%02X, want 0x90", v)
	}

	// Magnetometer gain: GN in bits 7:5 only.
	if err := full.SetMagRange(810); err != nil {
		t.Fatalf("SetMagRange: %v", err)
	}
	if v, _ := mag.lastWrite(0x01); v != 0xE0 {
		t.Errorf("SetMagRange(810) CRB = 0x%02X, want 0xE0", v)
	}
}

func TestLSM303FullMotionInterruptFreeFall(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.SetDataRate(400); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	if err := full.SetFreeFall(1, 0.35, 30); err != nil {
		t.Fatalf("SetFreeFall: %v", err)
	}
	if v, _ := accel.lastWrite(0x30); v != 0x95 {
		t.Errorf("free-fall INT1_CFG = 0x%02X, want 0x95", v)
	}
	// threshold 0.35 g * 128 / 2 g = 22.4 -> 22; duration 30 ms * 400 Hz = 12.
	if v, _ := accel.lastWrite(0x32); v != 22 {
		t.Errorf("free-fall INT1_THS = %d, want 22", v)
	}
	if v, _ := accel.lastWrite(0x33); v != 12 {
		t.Errorf("free-fall INT1_DURATION = %d, want 12", v)
	}
}

func TestLSM303FullClickAndLatch(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.SetDataRate(400); err != nil {
		t.Fatalf("SetDataRate: %v", err)
	}
	// single Z (mask 4 -> ZS bit 4), double X (mask 1 -> XD bit 1): 0x10 | 0x02.
	if err := full.SetClick(0x04, 0x01, 0.8, 20, 40, 200); err != nil {
		t.Fatalf("SetClick: %v", err)
	}
	if v, _ := accel.lastWrite(0x38); v != 0x12 {
		t.Errorf("CLICK_CFG = 0x%02X, want 0x12", v)
	}
	if v, _ := accel.lastWrite(0x3A); v != 51 {
		t.Errorf("CLICK_THS = %d, want 51", v)
	}
	if v, _ := accel.lastWrite(0x3B); v != 8 {
		t.Errorf("TIME_LIMIT = %d, want 8", v)
	}
	if v, _ := accel.lastWrite(0x3C); v != 16 {
		t.Errorf("TIME_LATENCY = %d, want 16", v)
	}
	if v, _ := accel.lastWrite(0x3D); v != 80 {
		t.Errorf("TIME_WINDOW = %d, want 80", v)
	}

	if err := full.SetLatch(1, true); err != nil {
		t.Fatalf("SetLatch: %v", err)
	}
	// CTRL5 starts at 0x00; LIR_INT1 is bit 3.
	if v, _ := accel.lastWrite(0x24); v != 0x08 {
		t.Errorf("SetLatch(1,true) CTRL5 = 0x%02X, want 0x08", v)
	}
}

func TestLSM303FullRouteAndPoll(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.EnableInterrupt(LSM303SourceDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt(DataReady): %v", err)
	}
	if v, _ := accel.lastWrite(0x22); v != 0x10 {
		t.Errorf("DataReady route CTRL3 = 0x%02X, want 0x10", v)
	}
	if err := full.EnableInterrupt(LSM303SourceIA1, 1); err != nil {
		t.Fatalf("EnableInterrupt(IA1, 1): %v", err)
	}
	if v, _ := accel.lastWrite(0x22); v != 0x50 {
		t.Errorf("IA1 on INT1 CTRL3 = 0x%02X, want 0x50", v)
	}
	if err := full.EnableInterrupt(LSM303SourceIA2, 2); err != nil {
		t.Fatalf("EnableInterrupt(IA2, 2): %v", err)
	}
	if v, _ := accel.lastWrite(0x25); v != 0x20 {
		t.Errorf("IA2 on INT2 CTRL6 = 0x%02X, want 0x20", v)
	}
	if err := full.SetInterruptPolarity(true); err != nil {
		t.Fatalf("SetInterruptPolarity: %v", err)
	}
	if v, _ := accel.lastWrite(0x25); v&0x02 == 0 {
		t.Errorf("active-low CTRL6 = 0x%02X, H_LACTIVE not set", v)
	}

	if err := full.EnableInterrupt(LSM303SourceMagDataReady, 1); err == nil {
		t.Error("EnableInterrupt(MagDataReady): expected error, got nil")
	}
	if err := full.EnableInterrupt(LSM303SourceIA1, 3); err == nil {
		t.Error("EnableInterrupt with pin 3: expected error, got nil")
	}

	// Poll: data ready and IA1 both set; IA2 is disabled so its register must not be read.
	if err := full.DisableInterrupt(LSM303SourceIA2, 2); err != nil {
		t.Fatalf("DisableInterrupt(IA2): %v", err)
	}
	accel.setRegs(0x27, 0x08)
	accel.setRegs(0x31, 0x40)
	status, err := full.PollInterrupt()
	if err != nil {
		t.Fatalf("PollInterrupt: %v", err)
	}
	if status != LSM303SourceDataReady|LSM303SourceIA1 {
		t.Errorf("PollInterrupt = 0x%04X, want 0x%04X", status, LSM303SourceDataReady|LSM303SourceIA1)
	}
	if accel.didRead(0x35, 1) {
		t.Error("PollInterrupt read INT2_SOURCE_A although IA2 is not enabled")
	}
}

func TestLSM303FullHeading(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// Y = 100 µT (raw 1100), X = Z = 0 -> 90 degrees.
	mag.setRegs(0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x4C)
	h, err := full.Heading(false)
	if err != nil {
		t.Fatalf("Heading(false): %v", err)
	}
	if math.Abs(float64(h)-90) > 0.01 {
		t.Errorf("flat heading = %v, want 90", h)
	}
	// Level sensor (Z = +1 g, raw 1000 at HR -> 0x3E80 << 4 ...): tilt matches flat.
	accel.setRegs(0x28, 0x00, 0x00, 0x00, 0x00, 0x80, 0x3E)
	h, err = full.Heading(true)
	if err != nil {
		t.Fatalf("Heading(true): %v", err)
	}
	if math.Abs(float64(h)-90) > 0.01 {
		t.Errorf("tilt-compensated level heading = %v, want 90", h)
	}
}

func TestLSM303FullTemperatureDelta(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	// Raw 0xFF00 = -256; >> 4 = -16 counts; / 8 = -2 °C.
	mag.setRegs(0x31, 0xFF, 0x00)
	temp, err := full.ReadTemperatureDelta()
	if err != nil {
		t.Fatalf("ReadTemperatureDelta: %v", err)
	}
	if !lsm303Close(temp, -2, 1e-5) {
		t.Errorf("temperature delta = %v, want -2", temp)
	}
	if v, ok := mag.lastWrite(0x00); !ok || v&0x80 == 0 {
		t.Errorf("TEMP_EN not set on first use: CRA = 0x%02X (ok=%v)", v, ok)
	}
}

func TestLSM303FullFifoBypassAndWatermark(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.SetFifo(1, 16, false); err != nil {
		t.Fatalf("SetFifo(1): %v", err)
	}
	if v, _ := accel.lastWrite(0x2E); v != 0x50 {
		t.Errorf("FIFO_CTRL = 0x%02X, want 0x50", v)
	}
	if v, _ := accel.lastWrite(0x24); v != 0x40 {
		t.Errorf("FIFO_EN in CTRL5 = 0x%02X, want 0x40", v)
	}
	if err := full.SetFifo(0, 0, false); err != nil {
		t.Fatalf("SetFifo(0): %v", err)
	}
	if v, _ := accel.lastWrite(0x24); v != 0x00 {
		t.Errorf("bypass CTRL5 = 0x%02X, want 0x00", v)
	}
	accel.setRegs(0x2F, 0x85) // WTM=1, OVRN=0, EMPTY=0, FSS=5
	wtm, ovr, empty, level, err := full.ReadFifoStatus()
	if err != nil {
		t.Fatalf("ReadFifoStatus: %v", err)
	}
	if !wtm || ovr || empty || level != 5 {
		t.Errorf("ReadFifoStatus = (%v,%v,%v,%d), want (true,false,false,5)", wtm, ovr, empty, level)
	}
}

func TestLSM303FullErrors(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.SetRange(3); err == nil {
		t.Error("SetRange(3): expected error")
	}
	if err := full.SetDataRate(1620); err == nil {
		t.Error("SetDataRate(1620) in high-resolution mode: expected error")
	}
	if err := full.SetMagRange(999); err == nil {
		t.Error("SetMagRange(999): expected error")
	}
	if err := full.SetMagDataRate(5); err == nil {
		t.Error("SetMagDataRate(5): expected error")
	}
	if err := full.SetFifo(4, 0, false); err == nil {
		t.Error("SetFifo(4): expected error")
	}
	if err := full.SetMode(0); err != nil {
		t.Fatalf("SetMode(0): %v", err)
	}
	if err := full.SetDataRate(1344); err == nil {
		t.Error("SetDataRate(1344) in low-power mode: expected error")
	}
	if err := full.OnInterrupt(2, func(uint16) {}); err == nil {
		t.Error("OnInterrupt(pin 2): expected error")
	}
}

func TestLSM303FullOnInterruptPollingFallback(t *testing.T) {
	accel, mag := newLSM303Pair()
	full, err := NewLSM303Full(accel, mag)
	if err != nil {
		t.Fatalf("NewLSM303Full: %v", err)
	}
	if err := full.EnableInterrupt(LSM303SourceDataReady, 1); err != nil {
		t.Fatalf("EnableInterrupt: %v", err)
	}
	accel.setRegs(0x27, 0x08)
	got := make(chan uint16, 1)
	if err := full.OnInterrupt(1, func(status uint16) {
		select {
		case got <- status:
		default:
		}
	}); err != nil {
		t.Fatalf("OnInterrupt: %v", err)
	}
	select {
	case s := <-got:
		if s&LSM303SourceDataReady == 0 {
			t.Errorf("callback status = 0x%04X, want DataReady bit", s)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("callback not invoked by polling fallback")
	}
	if err := full.OffInterrupt(1); err != nil {
		t.Fatalf("OffInterrupt: %v", err)
	}
}
