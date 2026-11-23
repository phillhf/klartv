# Projektspezifische R8-Regeln. Media3 und Coil bringen ihre eigenen Regeln mit.

# libVLC greift aus nativem Code auf seine Klassen zu
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# Wird über den Namen im Manifest geladen
-keep class io.github.phillhf.iptv.cast.CastOptionsProvider { *; }

# Netzlaufwerk (smbj): Ereignisse über Annotationen, Kryptografie per Reflexion
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
