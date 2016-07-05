#include<stdio.h>
#include<jni.h>
#include<iostream>
using namespace std;
#include "com_oracle_truffle_object_RaceDetectorWrapper.h"
#include "VCRaceDetector.h"

RaceDetector::VCRaceDetector rd;

JNIEXPORT jstring JNICALL Java_com_oracle_truffle_object_RaceDetectorWrapper_addOp(JNIEnv *env, jobject obj, jint type, jstring location) {

    const char *var = env->GetStringUTFChars(location, JNI_FALSE);

    cout << var << endl;

    int opCode = (int)type;
    RaceDetector::Race::Operation op;
    op = (opCode == 0)? RaceDetector::Race::WRITE : RaceDetector::Race::READ;

    std::vector<RaceDetector::Race> races;
    rd.recordOperation(op, var, &races);

    return location;
}

JNIEXPORT void JNICALL Java_com_oracle_truffle_object_RaceDetectorWrapper_beginEvent(JNIEnv *env, jobject obj, jint id) {
    rd.beginEventAction((int)id);
}

JNIEXPORT void JNICALL Java_com_oracle_truffle_object_RaceDetectorWrapper_endEvent(JNIEnv *env, jobject obj) {
    rd.endEventAction();
}

JNIEXPORT void JNICALL Java_com_oracle_truffle_object_RaceDetectorWrapper_denoteCurrentEventAfter(JNIEnv *env, jobject obj, jint id) {
    rd.denoteCurrentEventAfter((int)id);
}

