#JAVA_HOME=/usr/lib/jvm/java-8-oracle/
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/

all:
	mkdir -p ../../../../../../com.oracle.truffle.object/object
	g++ -fPIC -O3 -c VCRaceDetector.cpp
#	g++ -fPIC -O3 -o test VCRaceDetectorTest.cpp VCRaceDetector.o
	javah -cp ../../../../../../com.oracle.truffle.object/src/ com.oracle.truffle.object.RaceDetectorWrapper
	g++ -fPIC -shared -I${JAVA_HOME}/include/ -I${JAVA_HOME}/include/linux/ -I../../../../../../com.oracle.truffle.object/object -o ../../../../../../com.oracle.truffle.object/object/nativelib.so RaceDetectorWrapper.cpp VCRaceDetector.o
