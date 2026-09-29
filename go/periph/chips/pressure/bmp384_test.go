package pressure

import (
	"math"
	"testing"
)

// Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
// NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
//      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
func preloadBmp384Calibration(conn *mockConnection) {
	conn.setRegister(0x31,
		0x10, 0x6C, // T1 u16 LE
		0x50, 0x6C, // T2 u16 LE
		0x03, // T3 s8
		0x11, 0xF0, // P1 s16 LE
		0x22, 0x03, // P2 s16 LE
		0xF8, // P3 s8
		0x05, // P4 s8
		0x40, 0x80, // P5 u16 LE
		0x10, 0x1E, // P6 u16 LE
		0xF0, // P7 s8
		0x0A, // P8 s8
		0xE0, 0x0F, // P9 s16 LE
		0xFB, // P10 s8
		0x02, // P11 s8
	)
	conn.setRegister(0x00, 0x50) // CHIP_ID
}

func newBmp384Connection() *mockConnection {
	conn := newMockConnection()
	preloadBmp384Calibration(conn)
	return conn
}

// uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
// pressure=1447.6955007429672 hPa (computed independently from the same
// Bosch compensation formula; cross-checked across all language ports).
var bmp384PressBytes = []byte{0x80, 0x8D, 0x5B}
var bmp384TempBytes = []byte{0x00, 0x12, 0x7A}

const bmp384ExpectedTLin = 23.715563300065696
const bmp384ExpectedPressureHPa = 1447.6955007429672

func setBmp384Burst(conn *mockConnection) {
	conn.setRegister(0x04, append(append([]byte{}, bmp384PressBytes...), bmp384TempBytes...)...)
}

func TestBmp384MinimalConstructionAndConfig(t *testing.T) {
	conn := newBmp384Connection()
	if _, err := NewBMP384Minimal(conn); err != nil {
		t.Fatalf("NewBMP384Minimal: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1C); v != (1<<3)|4 {
		t.Errorf("OSR write = %#x, want %#x", v, (1<<3)|4)
	}
	if v, _ := lastWriteToReg(conn, 0x1F); v != 2<<1 {
		t.Errorf("CONFIG write = %#x, want %#x", v, 2<<1)
	}
	if v, _ := lastWriteToReg(conn, 0x1D); v != 0x03 {
		t.Errorf("ODR write = %#x, want 0x03", v)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (0x03<<4)|0x02|0x01 {
		t.Errorf("PWR_CTRL write = %#x, want %#x", v, (0x03<<4)|0x02|0x01)
	}
}

func TestBmp384MinimalTemperatureAndPressure(t *testing.T) {
	conn := newBmp384Connection()
	chip, err := NewBMP384Minimal(conn)
	if err != nil {
		t.Fatalf("NewBMP384Minimal: %v", err)
	}
	setBmp384Burst(conn)
	temp, err := chip.Temperature()
	if err != nil || math.Abs(float64(temp)-bmp384ExpectedTLin) > 1e-3 {
		t.Errorf("Temperature() = %v, %v, want ~%v, nil", temp, err, bmp384ExpectedTLin)
	}

	setBmp384Burst(conn)
	p, err := chip.Pressure()
	if err != nil || math.Abs(float64(p)-bmp384ExpectedPressureHPa) > 1e-3 {
		t.Errorf("Pressure() = %v, %v, want ~%v, nil", p, err, bmp384ExpectedPressureHPa)
	}
}

func TestBmp384MinimalForcedModeTriggers(t *testing.T) {
	conn := newBmp384Connection()
	chip, err := NewBMP384Minimal(conn)
	if err != nil {
		t.Fatalf("NewBMP384Minimal: %v", err)
	}
	chip.mode = bmp384ModeForced
	setBmp384Burst(conn)
	if _, err := chip.Temperature(); err != nil {
		t.Fatalf("Temperature: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (bmp384ModeForced<<4)|0x02|0x01 {
		t.Errorf("forced-mode PWR_CTRL write = %#x, want %#x", v, (bmp384ModeForced<<4)|0x02|0x01)
	}
}

func TestBmp384FullConfigure(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.Configure(1, 1, 2, 0x03); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1C); v != (1<<3)|1 {
		t.Errorf("OSR write = %#x, want %#x", v, (1<<3)|1)
	}
	if v, _ := lastWriteToReg(conn, 0x1F); v != 2<<1 {
		t.Errorf("CONFIG write = %#x, want %#x", v, 2<<1)
	}
	if v, _ := lastWriteToReg(conn, 0x1D); v != 0x03 {
		t.Errorf("ODR write = %#x, want 0x03", v)
	}
}

func TestBmp384FullRead(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	setBmp384Burst(conn)
	p, temp, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if math.Abs(float64(p)-bmp384ExpectedPressureHPa) > 1e-3 {
		t.Errorf("Read() pressure = %v, want ~%v", p, bmp384ExpectedPressureHPa)
	}
	if math.Abs(float64(temp)-bmp384ExpectedTLin) > 1e-3 {
		t.Errorf("Read() temperature = %v, want ~%v", temp, bmp384ExpectedTLin)
	}
}

// Regression: Read() must trigger a forced measurement exactly like
// Temperature()/Pressure()/ReadForced() do — it was previously missing the
// PWR_CTRL trigger write's accompanying behaviour entirely in forced mode.
func TestBmp384FullReadForcedModeTriggers(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.SetMode(BMP384ModeForced); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	setBmp384Burst(conn)
	if _, _, err := full.Read(); err != nil {
		t.Fatalf("Read: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (bmp384ModeForced<<4)|0x02|0x01 {
		t.Errorf("forced-mode PWR_CTRL write = %#x, want %#x", v, (bmp384ModeForced<<4)|0x02|0x01)
	}
}

func TestBmp384FullReadForced(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	setBmp384Burst(conn)
	p, _, err := full.ReadForced()
	if err != nil {
		t.Fatalf("ReadForced: %v", err)
	}
	if math.Abs(float64(p)-bmp384ExpectedPressureHPa) > 1e-3 {
		t.Errorf("ReadForced() pressure = %v, want ~%v", p, bmp384ExpectedPressureHPa)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (bmp384ModeNormal<<4)|0x02|0x01 {
		t.Errorf("ReadForced() did not restore mode: PWR_CTRL = %#x, want %#x", v, (bmp384ModeNormal<<4)|0x02|0x01)
	}
}

func TestBmp384FullSetMode(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.SetMode(BMP384ModeSleep); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (bmp384ModeSleep<<4)|0x02|0x01 {
		t.Errorf("PWR_CTRL write = %#x, want %#x", v, (bmp384ModeSleep<<4)|0x02|0x01)
	}
}

func TestBmp384FullIsDataReady(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	conn.setRegister(0x03, 1<<5)
	ready, err := full.IsDataReady()
	if err != nil || !ready {
		t.Errorf("IsDataReady() = %v, %v, want true, nil", ready, err)
	}
	conn.setRegister(0x03, 0x00)
	ready, err = full.IsDataReady()
	if err != nil || ready {
		t.Errorf("IsDataReady() = %v, %v, want false, nil", ready, err)
	}
}

func TestBmp384FullSoftreset(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.Softreset(); err != nil {
		t.Fatalf("Softreset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x7E); v != 0xB6 {
		t.Errorf("Softreset() CMD write = %#x, want 0xB6", v)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != (bmp384ModeNormal<<4)|0x02|0x01 {
		t.Errorf("Softreset() did not reapply config: PWR_CTRL = %#x", v)
	}
}

func TestBmp384FullFIFOConfig(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.FIFOConfig(true, true, 300, true); err != nil {
		t.Fatalf("FIFOConfig: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x17); v != (1<<4)|(1<<3)|(1<<1)|1 {
		t.Errorf("FIFO_CONFIG_1 write = %#x, want %#x", v, (1<<4)|(1<<3)|(1<<1)|1)
	}
	if v, _ := lastWriteToReg(conn, 0x15); v != byte(300&0xFF) {
		t.Errorf("FIFO_WTM_0 write = %#x, want %#x", v, byte(300&0xFF))
	}
	if v, _ := lastWriteToReg(conn, 0x16); v != byte((300>>8)&0x01) {
		t.Errorf("FIFO_WTM_1 write = %#x, want %#x", v, byte((300>>8)&0x01))
	}
}

func TestBmp384FullFIFORead(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	fifoBytes := []byte{
		0x84, 0x80, 0x8D, 0x5B, // pressure frame
		0x90, 0x00, 0x12, 0x7A, // temperature frame
		0xA0, 0x01, 0x02, 0x03, // sensortime frame
		0x44, // error frame
		0x80, // empty frame
		0xFF, // unknown header
	}
	conn.setRegister(0x12, byte(len(fifoBytes)&0xFF), byte((len(fifoBytes)>>8)&0x01))
	conn.setRegister(0x14, fifoBytes...)
	full.tLin = bmp384ExpectedTLin // so a lone pressure frame is comparable to the fixture

	frames, err := full.FIFORead()
	if err != nil {
		t.Fatalf("FIFORead: %v", err)
	}
	if len(frames) != 6 {
		t.Fatalf("FIFORead() returned %d frames, want 6", len(frames))
	}
	if frames[0].Type != "pressure" || math.Abs(float64(frames[0].Value)-bmp384ExpectedPressureHPa) > 1e-3 {
		t.Errorf("frame 0 = %+v, want pressure ~%v", frames[0], bmp384ExpectedPressureHPa)
	}
	if frames[1].Type != "temperature" || math.Abs(float64(frames[1].Value)-bmp384ExpectedTLin) > 1e-3 {
		t.Errorf("frame 1 = %+v, want temperature ~%v", frames[1], bmp384ExpectedTLin)
	}
	if frames[2].Type != "sensortime" || frames[2].Value != float32(0x030201) {
		t.Errorf("frame 2 = %+v, want sensortime %v", frames[2], float32(0x030201))
	}
	if frames[3].Type != "error" {
		t.Errorf("frame 3 = %+v, want error", frames[3])
	}
	if frames[4].Type != "empty" {
		t.Errorf("frame 4 = %+v, want empty", frames[4])
	}
	if frames[5].Type != "unknown" {
		t.Errorf("frame 5 = %+v, want unknown", frames[5])
	}
}

func TestBmp384FullFIFOReadEmpty(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	conn.setRegister(0x12, 0x00, 0x00)
	frames, err := full.FIFORead()
	if err != nil {
		t.Fatalf("FIFORead: %v", err)
	}
	if len(frames) != 0 {
		t.Errorf("FIFORead() on empty FIFO = %d frames, want 0", len(frames))
	}
}

func TestBmp384FullFIFOFlush(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	if err := full.FIFOFlush(); err != nil {
		t.Fatalf("FIFOFlush: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x7E); v != 0xB0 {
		t.Errorf("FIFOFlush() CMD write = %#x, want 0xB0", v)
	}
}

func TestBmp384FullAltitude(t *testing.T) {
	conn := newBmp384Connection()
	full, err := NewBMP384Full(conn)
	if err != nil {
		t.Fatalf("NewBMP384Full: %v", err)
	}
	setBmp384Burst(conn)
	alt, err := full.Altitude(1013.25)
	if err != nil {
		t.Fatalf("Altitude: %v", err)
	}
	if alt >= 0 {
		t.Errorf("Altitude() = %v, want < 0 (fixture pressure %v hPa is above the sea-level reference)", alt, bmp384ExpectedPressureHPa)
	}
}
