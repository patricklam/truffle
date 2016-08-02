package com.oracle.truffle.object;

public class RaceDetectorWrapper {
    static {
        System.load("/home/andrei/Desktop/nodeJs-race-detector/truffle/truffle/com.oracle.truffle.object/src/com/oracle/truffle/object/nativelib.so");
    }

    public native String addOp(int type, String loc);

    public native void beginEvent(int id);

    public native void endEvent();

    public native void denoteCurrentEventAfter(int id);

    public static void main(String[] args) {

        RaceDetectorWrapper test = new RaceDetectorWrapper();
        test.beginEvent(0);
        test.addOp(0, "a");

    }
}
