
package fr.seki.dashboard.usb;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import org.usb4java.BufferUtils;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.HotplugCallback;
import org.usb4java.HotplugCallbackHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

/**
 *
 * @author seb
 */
public class K8101 implements HotplugCallback {

    private static Context ctx;
    private static K8101 instance;
    private static final Logger logger = Logger.getLogger(K8101.class.toString());

    private static final int INTERFACE = 1;
    private static final byte EP = 2;
    private static final short VENDOR_ID = 0x10cf;
    private static final short PRODUCT_ID = (short) 0x8101;
    private static Device device;
    private static ArrayList<IK8101Listener> listeners;
    
    //usb events
    
    private static HotplugCallbackHandle callbackHandle;
    private static EventHandlingThread thread;
    
    /**
     * This is the event handling thread. libusb doesn't start threads by its
     * own so it is our own responsibility to give libusb time to handle the
     * events in our own thread.
     */
    private static class EventHandlingThread extends Thread {

        /**
         * If thread should abort.
         */
        private volatile boolean abort;

        /**
         * Aborts the event handling thread.
         */
        public void abort() {
            this.abort = true;
        }

        @Override
        public void run() {
            while (!this.abort) {
                // Let libusb handle pending events. This blocks until events
                // have been handled, a hotplug callback has been deregistered
                // or the specified time of 1 second (Specified in
                // Microseconds) has passed.
                int result = LibUsb.handleEventsTimeout(null, 1000000);
                if (result != LibUsb.SUCCESS) {
                    throw new LibUsbException("Unable to handle events", result);
                }
            }
        }
    }

    private K8101() throws LibUsbException {
        listeners = new ArrayList<IK8101Listener>();

        ctx = new Context();
        int result = LibUsb.init(ctx);
        if (result != LibUsb.SUCCESS) {
            throw new LibUsbException("Unable to initialize", result);
        }
        logger.log(Level.INFO, "Libusb version " + LibUsb.getVersion());
        LibUsb.setDebug(ctx, 1);
        
        if (LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER)) {
            logger.info("This platform supports kernel drivers detaching");
        } else {
            logger.info("Cannot detach kernel drivers on this platform");
        } 

        device = findDevice(VENDOR_ID, PRODUCT_ID);
        if (device == null){
            logger.log(Level.WARNING, "Device not found");
        }

        if (LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG)) {
            // Start the event handling thread
            EventHandlingThread thread = new EventHandlingThread();
            thread.start();

            // Register the hotplug callback
            callbackHandle = new HotplugCallbackHandle();
            result = LibUsb.hotplugRegisterCallback(null,
                    LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED
                    | LibUsb.HOTPLUG_EVENT_DEVICE_LEFT,
                    LibUsb.HOTPLUG_ENUMERATE,
                    LibUsb.HOTPLUG_MATCH_ANY,
                    LibUsb.HOTPLUG_MATCH_ANY,
                    LibUsb.HOTPLUG_MATCH_ANY,
                    this, null, callbackHandle); //new Callback()
            if (result != LibUsb.SUCCESS) {
                throw new LibUsbException("Unable to register hotplug callback",
                        result);
            }
        }
        
        // for desctructor
        // Unregister the hotplug callback and stop the event handling thread
        //thread.abort();
        //LibUsb.hotplugDeregisterCallback(null, callbackHandle);
        //thread.join();
        //
        // Deinitialize the libusb context
        //LibUsb.exit(null);
    }

    @Override
    protected void finalize() throws Throwable {
        // Unregister the hotplug callback and stop the event handling thread
        thread.abort();
        LibUsb.hotplugDeregisterCallback(null, callbackHandle);
        thread.join();
        //
        // Deinitialize the libusb context
        //LibUsb.exit(null);

        super.finalize(); //To change body of generated methods, choose Tools | Templates.
    }

    public static K8101 getinstance() {
        if (instance == null) {
            instance = new K8101();
        }
        return instance;
    }

    private static Device findDevice(int vendorId, int productId) {
        // Read the USB device list
        DeviceList list = new DeviceList();
        int result = LibUsb.getDeviceList(ctx, list);
        if (result < 0) {
            throw new LibUsbException("Unable to get device list", result);
        }

        try {
            // Iterate over all devices and scan for the right one
            for (Device device : list) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                result = LibUsb.getDeviceDescriptor(device, descriptor);
                if (result != LibUsb.SUCCESS) {
                    throw new LibUsbException("Unable to read device descriptor", result);
                }
                if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId) {
                    LibUsb.freeDeviceList(list, true);
                    return device;
                }
            }
        } finally {
            // Ensure the allocated device list is freed
            if (list.getSize() > 0){
                LibUsb.freeDeviceList(list, true);
            }
        }

        System.out.println("Device not found");
        return null;
    }

    public static boolean isDeviceAttached() {
        return device != null;
    }

    private static boolean showNumericTime = false;
    
    public static void showNumericTime(boolean show){
        showNumericTime = show;
    }
    
    public static void drawClock() {
        LocalTime now = LocalTime.now();
        int hour = now.getHour();//calendar.get(Calendar.HOUR);        // gets hour in 12h format
        int minutes = now.getMinute(); //calendar.get(Calendar.MINUTE);

        for (int tick : new int[]{0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55}) {
            drawHand(tick, 60, 31, 28);
        }
        //drawHand(hour, 12, 15); // this is drawing a "fixed" hand in front of the hour
        drawHand(hour * 5 + (minutes * 5) / 60, 60, 15);
        drawHand(minutes, 60, 25);
        
        if (showNumericTime){
            String txt = String.format("%02d:%02d", hour, minutes);
            drawText(txt, 0, 0, txt.length() * TextSize.big.getWidth(), TextSize.big);
        }
    }

    private static void drawHand(int value, int maxValue, int length, int... start) {
        double angle = timeToAngle(value, maxValue);
        int orix = 64; // X center of screen
        int oriy = 34; // Y center of screen
        int x1, y1;
        
        if (start.length > 0) {
            x1 = (int) (orix + (start[0] * cos(angle)));
            y1 = (int) (oriy - (start[0] * sin(angle)));
        } else {
            x1 = orix;
            y1 = oriy;
        }
        int x2 = (int) (orix + (length * cos(angle)));
        int y2 = (int) (oriy - (length * sin(angle)));
        drawLine(x1, y1, x2, y2);
    }

    private static double timeToAngle(int val, int full) {
        double a = -((360 / full * val) - 90); // angle
        a = a * 3.14159265358979 / 180; // convert radians 
        return a;
    }

    private enum Command {
        backlight(20),
        beep(6),
        bigtext(4),
        bitmap(1),
        clearall(2),
        clearfg(3),
        contrast(17),
        drawline(18),
        drawpixel(9),
        drawrect(7),
        eraseline(19),
        erasepixel(16),
        eraserect(8),
        invert(21),
        //read,
        smalltext(5);
        private final byte code;

        private Command(int code) {
            this.code = (byte) code;
        }

        private int getCode() {
            return code;
        }
    }

    public enum TextSize {
        /* text is 6x8 or 4x8 */
        small(4),
        big(6);

        private int size;
        private TextSize(int size) {
            this.size = size;
        }
        
        public int getHeight(){
            return 8;
        }
        
        public int getWidth(){
            return size;
        }
    }

    /* a command is: AA, lsb(size), msb(size), cmd, [data,] chk, 55
       thus Size it is 6 bytes + data (if any)
       and checksum is SUM( size + command + params bytes, not including start & stop) modulo 256 
     */
    private static int[] makeText(String txt) {
        int[] payload = new int[txt.length()];
        for (int i = 0; i < txt.length(); i++) {
            payload[i] = txt.charAt(i);
        }
        return payload;
    }

    private static byte checkSum(byte[] cmd) {
        int sum = 0;
        for (byte b : cmd) {
            sum += b;
        }
        return (byte) (sum % 256);
    }

    private static byte[] makeCommand(Command cmd, int[] params) {
        int length = 0;
        switch (cmd) {
            case bitmap:
                length = 6 + 1024 + 1; //extra byte needed by µcontroller of k8101 
                //possible off-by-one in device fw
                break;
            case bigtext:
            case smalltext:
                length = 6 + params.length + 1; //possible off-by-one in device fw
                break;
            case drawline:
            case drawrect:
            case eraseline:
            case eraserect:
                length = 6 + 4; // 4 args
                break;
            case drawpixel:
            case erasepixel:
                length = 6 + 2; // 2 args
                break;
            case beep:
            case backlight:
            case contrast:
            case invert:
                length = 6 + 1; // 1 arg
                break;
            case clearall:
            case clearfg:
                length = 6; // no arg
                break;
//            case read:
        }
        byte[] payload = new byte[length];
        int size = params.length + 6;
        payload[1] = (byte) (size & 0xff); //lsb
        switch (cmd) {
            case drawline:
            case eraseline:
            case drawrect:
            case eraserect:
                payload[1] += 6; // bug in PIC?
        }

        payload[2] = (byte) (size >> 8 & 0xff); //msb
        payload[3] = (byte) cmd.getCode();
        for (int i = 0; i < params.length; i++) {
            payload[4 + i] = (byte) params[i];
        }
        payload[payload.length - 2] = checkSum(payload);
        payload[0] = (byte) 0xAA;
        payload[payload.length - 1] = (byte) 0x55;
        return payload;
    }

    public static void beep(int num) {
        int[] params = {num};
        byte[] cmd = makeCommand(Command.beep, params);
        send(cmd);
    }

    public static void backlight(int seconds) {
        int[] params = {seconds};
        byte[] cmd = makeCommand(Command.backlight, params);
        send(cmd);
    }

    public static void clearAll() {
        int[] params = {};
        byte[] cmd = makeCommand(Command.clearall, params);
        send(cmd);
    }

    public static void clearForeground() {
        int[] params = {};
        byte[] cmd = makeCommand(Command.clearfg, params);
        send(cmd);
    }

    public static void setContrast(int c) {
        int[] params = {c};
        byte[] cmd = makeCommand(Command.contrast, params);
        send(cmd);
    }

    public static void setInverted(boolean inv) {
        int[] params = {0};
        if (inv) {
            params[0] = 1;
        }
        byte[] cmd = makeCommand(Command.invert, params);
        send(cmd);
    }

    public static void drawPixel(int x, int y) {
        int[] params = {x, y};
        byte[] cmd = makeCommand(Command.drawpixel, params);
        send(cmd);
    }

    public static void erasePixel(int x, int y) {
        int[] params = {x, y};
        byte[] cmd = makeCommand(Command.erasepixel, params);
        send(cmd);
    }

    public static void drawLine(int x, int y, int x2, int y2) {
        int[] params = {x, y, x2, y2};
        byte[] cmd = makeCommand(Command.drawline, params);
        send(cmd);
    }

    public static void eraseLine(int x, int y, int x2, int y2) {
        int[] params = {x, y, x2, y2};
        byte[] cmd = makeCommand(Command.eraseline, params);
        send(cmd);
    }

    public static void drawRectangle(int x, int y, int width, int height, boolean plain){
        if (plain){
            drawPlainRectangle(x, y, width, height);
        } else {
            drawBox(x, y, width, height);
        }
    }
    
    public static void eraseRectangle(int x, int y, int width, int height, boolean plain){
        if (plain){
            erasePlainRectangle(x, y, width, height);
        } else {
            eraseBox(x, y, width, height);
        }
    }
    
    private static void drawBox(int x, int y, int width, int height){
        drawLine(x, y, x + width, y);
        drawLine(x + width, y, x + width, y + height);
        drawLine(x + width, y + height, x, y + height);
        drawLine(x, y + height, x, y);
    }
    
    private static void eraseBox(int x, int y, int width, int height){
        eraseLine(x, y, x + width, y);
        eraseLine(x + width, y, x + width, y + height);
        eraseLine(x + width, y + height, x, y + height);
        eraseLine(x, y + height, x, y);
    }
    
    private static void drawPlainRectangle(int x, int y, int width, int height) {
        int[] params = {x, y, width, height};
        byte[] cmd = makeCommand(Command.drawrect, params);
        send(cmd);
    }

    private static void erasePlainRectangle(int x, int y, int width, int height) {
        int[] params = {x, y, width, height};
        byte[] cmd = makeCommand(Command.eraserect, params);
        send(cmd);
    }

    public static void drawText(String txt, int x, int y, int width, TextSize size) {
        int[] params = new int[txt.length() + 3];
        params[0] = x;
        params[1] = y;
        params[2] = width;
        for (int i = 0; i < txt.length(); i++) {
            params[3 + i] = (int) txt.charAt(i);
        }
        byte[] cmd;
        if (size == TextSize.big) {
            cmd = makeCommand(Command.bigtext, params);
        } else {
            cmd = makeCommand(Command.smalltext, params);
        }
        send(cmd);
    }


    public static void drawBitmap(Icon icon) {
        BufferedImage buf = new BufferedImage(
                icon.getIconWidth(),
                icon.getIconHeight(),
                BufferedImage.TYPE_BYTE_BINARY);
        Graphics gfx = buf.createGraphics();
        // paint the Icon to the BufferedImage.
        icon.paintIcon(null, gfx, 0, 0);
        gfx.dispose();

        byte[] pixels = new byte[1024];
        for (int band = 0; band <= 7; band++) {
            for (int x = 0; x <= 127; x++) {
                for (int bit = 0; bit <= 7; bit++) {
                    int rgb = buf.getRGB(x, band * 8 + bit);
                    int r = (rgb >> 16) & 0xff;
                    int g = (rgb >> 8) & 0xff;
                    int b = rgb & 0xff;
                    boolean white = (r > 127) || (g > 127) || (b > 127);
                    bitSet(band * 128 * 8 + x * 8 + bit, pixels, !white);
                }
            }
        }
        int[] intBuf = new int[1024];
        for (int i = 0; i < intBuf.length; i++) {
            intBuf[i] = pixels[i] & 0xff;
        }
        byte[] cmd = makeCommand(Command.bitmap, intBuf);
        send(cmd);
    }

    private static boolean bitGet(int n, byte[] bitField) {
        return (bitField[n >> 3] & 1 << (n & 0x7)) != 0; //or use n / 8 and n % 8
    }

    private static void bitSet(int n, byte[] bitField, boolean value) {
        if (value) {
            bitField[n >> 3] |= 1 << (n & 0x7);
        } else {
            bitField[n >> 3] &= ~(1 << (n & 0x7));
        }
    }

    private static void send(byte[] cmd) {
        DeviceHandle handle = new DeviceHandle();
        try {
            handle = LibUsb.openDeviceWithVidPid(ctx, VENDOR_ID, PRODUCT_ID);
            if (handle == null) {
                System.err.println("cannot open device");
//                System.exit(1);
                return;
            }
            System.out.println("Device open");
//            DeviceDescriptor dd = new DeviceDescriptor();
//            LibUsb.getDeviceDescriptor(device, dd);
//            System.out.println(LibUsb.getStringDescriptor(handle, dd.iManufacturer()));
//            System.out.println(LibUsb.getStringDescriptor(handle, dd.iProduct()));

            if (1 == LibUsb.kernelDriverActive(handle, INTERFACE)) {
                System.out.println("kernel driver is loaded");
            } else {
                System.out.println("kernel driver is NOT loaded");
            }

            int result;

            // Check if kernel driver must be detached
            if (!LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER)) {
                System.out.println("cannot detach kernel drivers on this platform");
            } else {
                result = LibUsb.detachKernelDriver(handle, INTERFACE);
                if (result != LibUsb.SUCCESS) {
                    throw new LibUsbException("cannot detach driver", result);
                }
            }

            result = LibUsb.claimInterface(handle, INTERFACE);
            if (result != LibUsb.SUCCESS) {
                throw new LibUsbException("cannot claim interface", result);
            }
            System.out.println("Interface claimed");

            //byte[] cmd = {(byte) 0xaa, 07, 00, 06, 03, 0x10, 0x55};
            // libusb wants a ByteBuffer but I provide a byte[], we need to convert
            ByteBuffer buffer = BufferUtils.allocateByteBuffer(cmd.length);
            buffer.put(cmd);
            System.out.println(bytesToHex(cmd));

            IntBuffer transferred = IntBuffer.allocate(1);//BufferUtils.allocateIntBuffer();
            result = LibUsb.bulkTransfer(handle, EP, buffer, transferred, 500L);
            if (result != LibUsb.SUCCESS) {
                System.err.println("return was " + result);
                throw new LibUsbException("cannot send data", result);
            }
            System.out.println(transferred.get() + " bytes sent to device");

            LibUsb.releaseInterface(handle, INTERFACE);
            System.out.println("Interface released");

        } catch (LibUsbException lue){
            lue.printStackTrace();
        } finally {
            if (handle != null)
                LibUsb.close(handle);
                System.out.println("Device closed");
        }
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 3];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 3] = HEX_ARRAY[v >>> 4];
            hexChars[j * 3 + 1] = HEX_ARRAY[v & 0x0F];
            hexChars[j * 3 + 2] = ',';
        }
        return new String(hexChars);
    }

    @Override
    public int processEvent(Context context, Device device, int event, Object userData) {
        DeviceDescriptor descriptor = new DeviceDescriptor();
        int result = LibUsb.getDeviceDescriptor(device, descriptor);
        if (result != LibUsb.SUCCESS) {
            throw new LibUsbException("Unable to read device descriptor",
                    result);
        }
        logger.log(Level.INFO, String.format("%s: %04x:%04x",
                event == LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED ? "Connected" : "Disconnected",
                descriptor.idVendor(), descriptor.idProduct()));
        if (descriptor.idVendor() == VENDOR_ID && descriptor.idProduct() == PRODUCT_ID) {
            if (event == LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED) {
                this.device = device;
                for (IK8101Listener listener : listeners) {
                    listener.deviceAttached();
                }
            } else if (event == LibUsb.HOTPLUG_EVENT_DEVICE_LEFT) {
                this.device = null;
                for (IK8101Listener listener : listeners) {
                    listener.deviceDetached();
                }
            }
        }
        return 0;
    }

    public static void addListener(IK8101Listener l) {
        listeners.add(l);
    }

}
