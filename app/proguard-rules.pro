# NanoHTTPD reflection rules
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ZXing rules
-keep class com.google.zxing.** { *; }

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.mainul.mishare.model.** { *; }
