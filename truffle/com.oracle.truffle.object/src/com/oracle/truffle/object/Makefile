all:
	g++ -fPIC -O3 -c VCRaceDetector.cpp
#	g++ -fPIC -O3 -o test VCRaceDetectorTest.cpp VCRaceDetector.o
	javah -cp ~/Desktop/nodeJs-race-detector/truffle/truffle/com.oracle.truffle.object/src/ com.oracle.truffle.object.RaceDetectorWrapper
	g++ -fPIC -shared -I/usr/lib/jvm/java-8-oracle/include/ -I/usr/lib/jvm/java-8-oracle/include/linux/ -I/home/andrei/Desktop/nodeJs-race-detector/truffle/truffle/com.oracle.truffle.object/src/com/oracle/truffle/object/ -o /home/andrei/Desktop/nodeJs-race-detector/truffle/truffle/com.oracle.truffle.object/src/com/oracle/truffle/object/nativelib.so RaceDetectorWrapper.cpp VCRaceDetector.o
