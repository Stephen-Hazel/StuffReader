// Java mirror of sherpa-onnx's Kotlin TTS API (kotlin-api/Tts.kt, v1.13).
// The native library reads these fields by name over JNI, so names and types must match.
package com.k2fsa.sherpa.onnx;

public class OfflineTts {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;
    public OfflineTtsConfig config;

    public OfflineTts(OfflineTtsConfig config) {
        this.config = config;
        ptr = newFromFile(config);
        if (ptr == 0) throw new IllegalArgumentException("Invalid OfflineTtsConfig");
    }

    public int sampleRate() { return getSampleRate(ptr); }
    public int numSpeakers() { return getNumSpeakers(ptr); }
    public GeneratedAudio generate(String text, int sid, float speed) { return generateImpl(ptr, text, sid, speed); }

    public void release() { if (ptr != 0) { delete(ptr); ptr = 0; } }
    @Override protected void finalize() { release(); }

    private native long newFromFile(OfflineTtsConfig config);
    private native void delete(long ptr);
    private native int getSampleRate(long ptr);
    private native int getNumSpeakers(long ptr);
    private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
}
