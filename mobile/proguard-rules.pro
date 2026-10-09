# The app parses JSON by hand (org.json), so nothing needs keeping for reflection.

# Python (Chaquopy) calls the progress logger by name: log.log(line).
-keep class io.github.pedrubik2000.kumapie.mobile.local.ProcessWorker$Logger { public void log(java.lang.String); }

# sherpa-onnx (Parakeet, Silero VAD) reads its config objects' fields by name from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ML Kit (device translator) finds its components by class name at run time.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
