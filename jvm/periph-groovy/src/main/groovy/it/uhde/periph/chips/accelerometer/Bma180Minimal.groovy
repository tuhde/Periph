@CompileStatic
class Bma180Minimal {

    static final int REG_CHIP_ID          = 0x00
    static final int REG_VERSION          = 0x01
    static final int REG_ACC_X_LSB        = 0x02
    static final int REG_ACC_X_MSB        = 0x03
    static final int REG_ACC_Y_LSB        = 0x04
    static final int REG_ACC_Y_MSB        = 0x05
    static final int REG_ACC_Z_LSB        = 0x06
    static final int REG_ACC_Z_MSB        = 0x07
    static final int REG_TEMP             = 0x08
    static final int REG_STATUS_REG1      = 0x09
    static final int REG_STATUS_REG2      = 0x0A
    static final int REG_STATUS_REG3      = 0x0B
    static final int REG_STATUS_REG4      = 0x0C
    static final int REG_CTRL_REG0        = 0x0D
    static final int REG_CTRL_REG1        = 0x0E
    static final int REG_CTRL_REG2        = 0x0F
    static final int REG_RESET            = 0x10
    static final int REG_BW_TCS           = 0x20
    static final int REG_CTRL_REG3        = 0x21
    static final int REG_CTRL_REG4        = 0x22
    static final int REG_HY               = 0x23
    static final int REG_SLOPE_TAPSENS    = 0x24
    static final int REG_HIGH_LOW_INFO    = 0x25
    static final int REG_LOW_DUR          = 0x26
    static final int REG_HIGH_DUR         = 0x27
    static final int REG_TAPSENS_TH       = 0x28
    static final int REG_LOW_TH           = 0x29
    static final int REG_HIGH_TH          = 0x2A
    static final int REG_SLOPE_TH         = 0x2B
    static final int REG_CD1              = 0x2C
    static final int REG_CD2              = 0x2D
    static final int REG_TCO_X            = 0x2E
    static final int REG_TCO_Y            = 0x2F
    static final int REG_TCO_Z            = 0x30
    static final int REG_GAIN_T           = 0x31
    static final int REG_GAIN_X           = 0x32
    static final int REG_GAIN_Y           = 0x33
    static final int REG_GAIN_Z           = 0x34
    static final int REG_OFFSET_LSB1      = 0x35
    static final int REG_OFFSET_LSB2      = 0x36
    static final int REG_OFFSET_T         = 0x37
    static final int REG_OFFSET_X         = 0x38
    static final int REG_OFFSET_Y         = 0x39
    static final int REG_OFFSET_Z         = 0x3A

    static final int CHIP_ID_VALUE = 0x03
    static final int CHIP_ID_MASK  = 0x07

    static final int CTRL_REG0_EE_W       = 0x10
    static final int CTRL_REG0_RESET_INT  = 0x40
    static final int CTRL_REG0_UPDATE_IMG = 0x20
    static final int CTRL_REG0_ST0        = 0x04
    static final int CTRL_REG0_SLEEP      = 0x02

    static final int SOFT_RESET_CMD = 0xB6

    static final int RANGE_1G_MASK   = 0x00
    static final int RANGE_1_5G_MASK = 0x02
    static final int RANGE_2G_MASK   = 0x04
    static final int RANGE_3G_MASK   = 0x06
    static final int RANGE_4G_MASK   = 0x08
    static final int RANGE_8G_MASK   = 0x0A
    static final int RANGE_16G_MASK  = 0x0C

    static final float FULL_SCALE_1G   = 8192.0f
    static final float FULL_SCALE_1_5G = 5460.0f
    static final float FULL_SCALE_2G   = 4096.0f
    static final float FULL_SCALE_3G   = 2730.0f
    static final float FULL_SCALE_4G   = 2048.0f
    static final float FULL_SCALE_8G   = 1024.0f
    static final float FULL_SCALE_16G  = 512.0f

    private static final int[] BW_HZ   = [10, 20, 40, 75, 150, 300, 600, 1200] as int[]
    private static final int[] BW_CODE = [0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70] as int[]
    private static final int[] TAP_MS   = [50, 75, 100, 150, 250, 500, 750, 1000] as int[]
    private static final int[] TAP_CODE = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07] as int[]
    private static final float DUR_LSB_MS = 2.085f

    protected final RegisterConnection connection
    protected float rangeG = 2.0f
    protected int rangeBits = RANGE_2G_MASK

    Bma180Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection
        // First transaction must NOT be an acc LSB read.
        int id = readReg(REG_CHIP_ID)
        if ((id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw new IOException(String.format(
                "BMA180 CHIP_ID: expected 0x%02X, got 0x%02X",
                CHIP_ID_VALUE, id & CHIP_ID_MASK))
        }
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_EE_W)
        int olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 & 0xF1) | RANGE_2G_MASK)
        int bw = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (bw & 0x0F) | 0x40)
    }

    float[] read() throws IOException {
        byte[] raw = connection.read(REG_ACC_X_LSB, 6)
        int rx = Register.toSigned(((raw[1] & 0xFF) << 6) | ((raw[0] & 0xFF) >> 2), 14)
        int ry = Register.toSigned(((raw[3] & 0xFF) << 6) | ((raw[2] & 0xFF) >> 2), 14)
        int rz = Register.toSigned(((raw[5] & 0xFF) << 6) | ((raw[4] & 0xFF) >> 2), 14)
        float scale
        if      (rangeG == 1.0f)   scale = FULL_SCALE_1G
        else if (rangeG == 1.5f) scale = FULL_SCALE_1_5G
        else if (rangeG == 2.0f) scale = FULL_SCALE_2G
        else if (rangeG == 3.0f) scale = FULL_SCALE_3G
        else if (rangeG == 4.0f) scale = FULL_SCALE_4G
        else if (rangeG == 8.0f) scale = FULL_SCALE_8G
        else if (rangeG == 16.0f) scale = FULL_SCALE_16G
        else                      scale = FULL_SCALE_2G
        return new float[] { rx / scale, ry / scale, rz / scale }
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[] { (byte) (value & 0xFF) })
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF
    }
}