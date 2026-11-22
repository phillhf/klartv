# Projektspezifische R8-Regeln. Media3 und Coil bringen ihre eigenen Regeln mit.

# libVLC greift aus nativem Code auf seine Klassen zu
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# Wird über den Namen im Manifest geladen
-keep class io.github.phillhf.iptv.cast.CastOptionsProvider { *; }
