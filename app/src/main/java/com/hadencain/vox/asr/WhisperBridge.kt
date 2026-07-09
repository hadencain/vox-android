package com.hadencain.vox.asr

object WhisperBridge {
    init { System.loadLibrary("voxnative") }
    external fun init(modelPath: String): Long
    external fun transcribe(handle: Long, samples: FloatArray, biasPrompt: String?): String
    external fun release(handle: Long)
}
