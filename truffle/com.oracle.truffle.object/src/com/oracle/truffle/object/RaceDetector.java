package com.oracle.truffle.object;

import java.util.HashMap;
import java.util.Stack;

class Epoch {
    private int counter = 0;
    private Stack<Integer> epochStack = new Stack<>();

    public Epoch() {
        epochStack.push(counter);
    }

    public int startEpoch() {
        counter++;
        epochStack.push(counter);
        return counter;
    }

    public int endEpoch() {
        epochStack.pop();
        return epochStack.peek();
    }

    public int getValue() {
        return epochStack.peek();
    }

    @Override
    public String toString() {
        return Integer.toString(getValue());
    }
}

public class RaceDetector {

    public static boolean instrumentWriteFlag = false;
    public static boolean instrumentReadFlag = false;

    private static RaceDetectorWrapper rd = new RaceDetectorWrapper();
    private static Epoch epoch = new Epoch();
    private static HashMap<Object, Integer> listenerToEpoch = new HashMap<>();

    static {
        rd.beginEvent(0);
    }

    public static void addListener(Object o, String type, Object listener) {
        System.out.println(epoch + " ADDLISTENER object: " + o + " listener: " + listener + " / " + type);
        listenerToEpoch.put(listener, epoch.getValue());
    }

    public static void emitEvent(Object o, String type) {
        System.out.println(epoch + " EMIT object: " + o + " / " + type);
    }

    public static void startHandler(Object o, Object handler) {
        System.out.println(epoch + " CALLING on: " + o + " handler: " + handler);
        epoch.startEpoch();

        /* begin an event */
        rd.beginEvent(epoch.getValue());

        if (listenerToEpoch.containsKey(handler)) {
            System.out.println("listener defined in epoch: " + listenerToEpoch.get(handler));
        } else {
            System.out.println("ERROR: listener epoch not found");
        }
    }

    public static void endHandler() {
        System.out.println("ending epoch: " + epoch);
        epoch.endEpoch();

        /* end an event */
        rd.endEvent();
    }

    public static void add(OpType type, String location) {
        System.out.println(type + " " + location);

        /* add operation */
        rd.addOp((type == OpType.WRITE) ? 0 : 1, location);
    }

    public static void main(String[] args) {

    }
}
